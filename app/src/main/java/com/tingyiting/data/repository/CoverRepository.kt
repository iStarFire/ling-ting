package com.tingyiting.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tingyiting.data.model.CoverCrop
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

    open suspend fun scrapeFromDouban(bookId: Long, title: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val imageUrl = findDoubanCoverUrl(title)
                ?: throw IOException("未在豆瓣找到匹配封面")
            val coverPath = downloadCover(bookId, imageUrl)
            bookRepository.updateCover(bookId, coverPath)
            coverPath
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

    private fun findDoubanCoverUrl(title: String): String? {
        val query = URLEncoder.encode(title.trim().ifBlank { return null }, Charsets.UTF_8.name())
        val searchPages = listOf(
            "https://search.douban.com/book/subject_search?search_text=$query&cat=1001",
            "https://www.douban.com/search?cat=1001&q=$query",
            "https://www.douban.com/search?cat=1002&q=$query",
            "https://www.douban.com/search?q=$query"
        )
        for (url in searchPages) {
            val html = getHtml(url)
            DOUBAN_COVER_REGEX.find(html)?.let { return normalizeDoubanImage(it.value) }
            DOUBAN_SUBJECT_REGEX.find(html)?.let { subject ->
                findDoubanCoverOnSubject(subject.value)?.let { return it }
            }
        }
        return null
    }

    private fun findDoubanCoverOnSubject(url: String): String? {
        val html = getHtml(url)
        return DOUBAN_COVER_REGEX.find(html)?.let { normalizeDoubanImage(it.value) }
            ?: SUBJECT_COVER_META_REGEX.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::normalizeDoubanImage)
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

    private fun downloadCover(bookId: Long, imageUrl: String): String {
        val request = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("封面下载失败：HTTP ${response.code}")
            val extension = imageUrl.substringBefore('?')
                .substringAfterLast('.', "jpg")
                .lowercase()
                .takeIf { it in SUPPORTED_IMAGE_EXTENSIONS }
                ?: "jpg"
            val coverFile = coverFile(bookId, extension)
            response.body?.byteStream()?.use { input ->
                coverFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("封面下载内容为空")
            Uri.fromFile(coverFile).toString()
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
        val DOUBAN_COVER_REGEX =
            Regex("""https:\\?/\\?/img\d\.doubanio\.com/view/subject/[a-z]/public/[^"'<>\\]+\.(?:jpg|jpeg|png|webp)""")
        val DOUBAN_SUBJECT_REGEX =
            Regex("""https://(?:book|movie|music)\.douban\.com/subject/\d+/?""")
        val SUBJECT_COVER_META_REGEX =
            Regex("""property=["']og:image["']\s+content=["']([^"']+)["']""")
    }
}
