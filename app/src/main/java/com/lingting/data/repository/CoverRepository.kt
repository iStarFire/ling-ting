package com.lingting.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import com.lingting.data.model.CoverCrop
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CoverRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository
) {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 从豆瓣搜索候选封面 URL（只搜索不下载），供 UI 先选图再裁剪确认。
     * 返回去重后的多个候选，无匹配时失败并带提示。
     */
    open suspend fun searchDoubanCovers(title: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            findDoubanCovers(title)
                .ifEmpty { throw IOException("未在豆瓣找到匹配封面") }
        }
    }

    /**
     * 下载候选封面到临时缓存文件，供预览/裁剪使用（裁剪确认后调用 [importDoubanCover] 保存）。
     */
    open suspend fun downloadToTemp(imageUrl: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val file = tempCoverFile(imageUrl)
            downloadImageBytes(imageUrl)?.let { bytes ->
                file.writeBytes(bytes)
                Uri.fromFile(file)
            } ?: throw IOException("封面下载失败")
        }
    }

    /**
     * 加载候选封面缩略图（进程级内存缓存），用于候选列表预览。非 Blocking。
     */
    open suspend fun loadThumbnail(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        thumbnailCache.get(imageUrl)?.let { return@withContext it }
        val bitmap = runCatching {
            downloadImageBytes(imageUrl)?.let { bytes ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        }.getOrNull()
        bitmap?.let { thumbnailCache.put(imageUrl, it) }
        bitmap
    }

    /** 下载候选封面并裁剪保存为书籍封面。 */
    open suspend fun importDoubanCover(bookId: Long, imageUrl: String, crop: CoverCrop? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val coverFile = coverFile(bookId, "jpg")
            downloadImageBytes(imageUrl)?.let { bytes ->
                if (crop == null) {
                    coverFile.writeBytes(bytes)
                } else {
                    // 先写入临时文件再解码裁剪，避免直接操作字节流时裁剪坐标系失真。
                    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("无法解析下载的封面")
                    val cropped = source.cropSquare(crop)
                    coverFile.outputStream().use { output ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
                    }
                    if (cropped !== source) cropped.recycle()
                    source.recycle()
                }
            } ?: throw IOException("封面下载失败")
            val coverPath = Uri.fromFile(coverFile).toString()
            bookRepository.updateCover(bookId, coverPath)
            coverPath
        }
    }

    open suspend fun scrapeFromDouban(bookId: Long, title: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val imageUrl = findDoubanCovers(title).firstOrNull()
                ?: throw IOException("未在豆瓣找到匹配封面")
            importDoubanCover(bookId, imageUrl, crop = null).getOrThrow()
        }
    }

    open suspend fun importLocalCover(bookId: Long, uri: Uri, crop: CoverCrop? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val coverFile = coverFile(bookId, "jpg")
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("无法读取所选图片")
            val cropped = crop?.let { source.cropSquare(it) } ?: source.centerCropSquare()
            coverFile.outputStream().use { output ->
                cropped.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
            }
            if (cropped !== source) cropped.recycle()
            source.recycle()
            val coverPath = Uri.fromFile(coverFile).toString()
            bookRepository.updateCover(bookId, coverPath)
            coverPath
        }
    }

    /** 收集豆瓣搜索/详情页的所有候选封面 URL，跨页累积并去重。 */
    private fun findDoubanCovers(title: String): List<String> {
        val query = URLEncoder.encode(title.trim().ifBlank { return emptyList() }, Charsets.UTF_8.name())
        val searchPages = listOf(
            "https://search.douban.com/book/subject_search?search_text=$query&cat=1001",
            "https://www.douban.com/search?cat=1001&q=$query",
            "https://www.douban.com/search?cat=1002&q=$query",
            "https://www.douban.com/search?q=$query"
        )
        val seen = LinkedHashSet<String>()
        for (url in searchPages) {
            val html = getHtml(url)
            DOUBAN_COVER_REGEX.findAll(html).forEach { seen += normalizeDoubanImage(it.value) }
            DOUBAN_SUBJECT_REGEX.find(html)?.let { subject ->
                seen += findDoubanCoversOnSubject(subject.value)
            }
        }
        return seen.toList()
    }

    private fun findDoubanCoversOnSubject(url: String): List<String> {
        val html = getHtml(url)
        val covers = DOUBAN_COVER_REGEX.findAll(html).map { normalizeDoubanImage(it.value) }.toList()
        if (covers.isNotEmpty()) return covers
        return SUBJECT_COVER_META_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeDoubanImage)
            ?.let(::listOf)
            .orEmpty()
    }

    private fun getHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("豆瓣请求失败：HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    /**
     * 下载图片字节。豆瓣对无 Referer/UA 不完整的图片请求返回 HTTP 418（I'm a teapot，反爬）。
     * img1/2/3.doubanio.com 是互为镜像的三个图床，任一可用即可；先按当前主机试一次，
     * 失败则按 img1→img2→img3 顺序轮询，避免单主机被风控拦截时直接报错。
     */
    private fun downloadImageBytes(imageUrl: String): ByteArray? {
        val candidates = buildImageCandidates(imageUrl)
        var lastError: IOException? = null
        for (candidate in candidates) {
            try {
                return doDownloadImageBytes(candidate)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("封面下载失败")
    }

    private fun doDownloadImageBytes(imageUrl: String): ByteArray {
        val request = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://book.douban.com/")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("封面下载失败：HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("封面下载内容为空")
        }
    }

    private fun tempCoverFile(imageUrl: String): File {
        val dir = File(context.cacheDir, "covers_tmp").apply { mkdirs() }
        val extension = imageUrl.substringBefore('?')
            .substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it in SUPPORTED_IMAGE_EXTENSIONS }
            ?: "jpg"
        return File.createTempFile("cover_", ".$extension", dir)
    }

    /** 生成 img 主机候选列表：当前主机优先，再依次尝试 img2/img3。 */
    private fun buildImageCandidates(imageUrl: String): List<String> {
        val match = Regex("""(img)(\d)(\.doubanio\.com)""").find(imageUrl)
            ?: return listOf(imageUrl)
        val currentIdx = match.groupValues[2].toIntOrNull() ?: return listOf(imageUrl)
        val indices = listOf(currentIdx, (currentIdx % 3) + 1, ((currentIdx + 1) % 3) + 1).distinct()
        return indices.map { idx ->
            imageUrl.replace(match.value, "img$idx.doubanio.com")
        }
    }

    private fun coverFile(bookId: Long, extension: String): File {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        dir.listFiles { file -> file.name.startsWith("book_$bookId.") }
            ?.forEach { it.delete() }
        return File(dir, "book_$bookId.$extension")
    }

    private fun Bitmap.cropSquare(crop: CoverCrop): Bitmap {
        val safeSize = crop.size.coerceAtLeast(1).coerceAtMost(width).coerceAtMost(height)
        val safeLeft = crop.left.coerceIn(0, width - safeSize)
        val safeTop = crop.top.coerceIn(0, height - safeSize)
        val square = Bitmap.createBitmap(this, safeLeft, safeTop, safeSize, safeSize)
        return Bitmap.createScaledBitmap(square, COVER_SIZE_PX, COVER_SIZE_PX, true).also {
            if (it !== square) square.recycle()
        }
    }

    private fun Bitmap.centerCropSquare(): Bitmap {
        val size = minOf(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        return cropSquare(CoverCrop(left, top, size))
    }

    private fun normalizeDoubanImage(url: String): String =
        url.replace("\\/", "/")
            .replace("img1.doubanio.com/view/subject/s/public", "img1.doubanio.com/view/subject/l/public")
            .replace("img2.doubanio.com/view/subject/s/public", "img2.doubanio.com/view/subject/l/public")
            .replace("img3.doubanio.com/view/subject/s/public", "img3.doubanio.com/view/subject/l/public")

    private companion object {
        const val COVER_SIZE_PX = 1024
        const val COVER_JPEG_QUALITY = 92
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        /** 候选封面缩略图内存缓存，避免候选列表反复下载。 */
        val thumbnailCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        val DOUBAN_COVER_REGEX =
            Regex("""https:\\?/\\?/img\d\.doubanio\.com/view/subject/[a-z]/public/[^"'<>\\]+\.(?:jpg|jpeg|png|webp)""")
        val DOUBAN_SUBJECT_REGEX =
            Regex("""https://(?:book|movie|music)\.douban\.com/subject/\d+/?""")
        val SUBJECT_COVER_META_REGEX =
            Regex("""property=["']og:image["']\s+content=["']([^"']+)["']""")
    }
}
