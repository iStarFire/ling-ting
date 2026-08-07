package com.lingting.data.repository

import android.util.Log
import com.lingting.data.model.WebDavFile
import com.lingting.data.store.WebDavConfig
import com.lingting.data.store.WebDavConfigStore
import com.lingting.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class WebDavRepository @Inject constructor(
    private val configStore: WebDavConfigStore
) {
    private var client: WebDavClient? = null
    private var currentConfig: WebDavConfig? = null

    private val _configFlow = MutableStateFlow<WebDavConfig?>(null)
    val configFlow: StateFlow<WebDavConfig?> = _configFlow.asStateFlow()

    /** 应用启动时调用：从加密存储恢复 WebDAV 配置。失败静默降级。 */
    fun restore(): Boolean {
        val config = runCatching { configStore.load() }.getOrNull() ?: return false.also {
            Log.d("WebDavRepo", "restore: 无已保存配置")
        }
        applyConfig(config)
        Log.d("WebDavRepo", "restore: 已恢复配置 baseUrl=${config.baseUrl} user=${config.username}")
        return true
    }

    /** 仅测试连接（不修改已保存状态）。网络调用切到 IO 线程。 */
    suspend fun testConnection(baseUrl: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            WebDavClient(baseUrl, username, password).testConnection()
        }

    /** 测试成功后保存并应用配置（落盘）。 */
    suspend fun persist(baseUrl: String, username: String, password: String) {
        applyConfig(WebDavConfig(baseUrl, username, password))
        val saved = withContext(Dispatchers.IO) {
            runCatching { configStore.save(currentConfig!!) }.isSuccess
        }
        Log.d("WebDavRepo", "persist: baseUrl=$baseUrl user=$username 落盘${if (saved) "成功" else "失败(已忽略)"}")
    }

    fun getConfig(): WebDavConfig? = currentConfig

    fun isConfigured(): Boolean = client != null

    suspend fun clearConfig() {
        client = null
        currentConfig = null
        _configFlow.value = null
        withContext(Dispatchers.IO) { runCatching { configStore.clear() } }
    }

    suspend fun listFiles(path: String = "/"): Result<List<WebDavFile>> {
        val c = client ?: return Result.failure(IllegalStateException("请先配置服务器"))
        return withContext(Dispatchers.IO) { c.listFiles(path) }
    }

    /**
     * 递归收集 rootPath 目录（含所有子目录）下的全部音频文件。
     * 仅收集 isAudio；限制遍历深度与总数以防失控；结果按相对路径「数字感知」自然排序。
     *
     * @param onProgress 每扫描完一个目录时回调 (已扫描目录数, 已知总目录数, 已发现音频数)，
     *                    用于 UI 以「i/total」形式展示确定进度。
     *                    totalDirs 采用「已扫描 + 队列剩余」的滚动最大值，随遍历自然收敛到真实总数，
     *                    保证进度条单调递增并最终达到 100%。
     */
    open suspend fun collectAudioFiles(
        rootPath: String,
        onProgress: (scannedDirs: Int, totalDirs: Int, audioCount: Int) -> Unit = { _, _, _ -> }
    ): Result<List<WebDavFile>> =
        withContext(Dispatchers.IO) {
            val collected = mutableListOf<WebDavFile>()
            val queue: ArrayDeque<Pair<String, Int>> = ArrayDeque()
            queue.add(rootPath to 0)
            val visited = mutableSetOf<String>()
            var scannedDirs = 0
            var totalDirsSeen = 0
            while (queue.isNotEmpty()) {
                val (path, depth) = queue.removeFirst()
                if (depth > MAX_COLLECT_DEPTH || path in visited) continue
                visited.add(path)
                scannedDirs++
                val files = listFiles(path).fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        // 根目录失败直接报错；子目录失败则跳过该分支继续
                        if (path == rootPath) return@withContext Result.failure(e)
                        return@fold emptyList()
                    }
                )
                for (f in files) {
                    when {
                        f.isAudio -> if (collected.size < MAX_COLLECT_FILES) collected.add(f)
                        f.isDirectory -> queue.add(f.path to depth + 1)
                    }
                }
                totalDirsSeen = maxOf(totalDirsSeen, scannedDirs + queue.size)
                onProgress(scannedDirs, totalDirsSeen, collected.size)
                if (collected.size >= MAX_COLLECT_FILES) break
            }
            val root = rootPath.trimEnd('/')
            collected.sortWith { a, b ->
                naturalCompare(relativePath(root, a.path), relativePath(root, b.path))
            }
            Log.d("WebDavRepo", "collectAudioFiles: root=$root 收集到 ${collected.size} 个音频文件")
            Result.success(collected)
        }

    open fun buildFileUrl(filePath: String): String {
        val c = client ?: throw IllegalStateException("请先配置服务器")
        return c.buildFileUrl(filePath)
    }

    fun getAuthHeader(): String {
        val c = client ?: throw IllegalStateException("请先配置服务器")
        return c.getAuthHeader()
    }

    private fun applyConfig(config: WebDavConfig) {
        client = WebDavClient(config.baseUrl, config.username, config.password)
        currentConfig = config
        _configFlow.value = config
    }

    companion object {
        private const val MAX_COLLECT_DEPTH = 12
        private const val MAX_COLLECT_FILES = 1000
    }
}

private fun relativePath(root: String, path: String): String {
    val r = root.trimEnd('/')
    return if (path.startsWith(r) && path.length > r.length) path.substring(r.length) else path
}

/**
 * 数字感知自然排序：把字符串切分为「数字段 / 非数字段」交替的块，
 * 数字段按数值比较（保证 第2集 < 第10集），非数字段按字典序比较。
 */
private fun naturalCompare(a: String, b: String): Int {
    val ca = naturalChunks(a)
    val cb = naturalChunks(b)
    val n = minOf(ca.size, cb.size)
    for (i in 0 until n) {
        val (ta, na) = ca[i]
        val (tb, nb) = cb[i]
        val res = if (na && nb) {
            val va = ta.toLongOrNull() ?: 0L
            val vb = tb.toLongOrNull() ?: 0L
            when {
                va != vb -> va.compareTo(vb)
                else -> ta.length.compareTo(tb.length)
            }
        } else {
            ta.compareTo(tb)
        }
        if (res != 0) return res
    }
    return ca.size.compareTo(cb.size)
}

private fun naturalChunks(s: String): List<Pair<String, Boolean>> {
    val chunks = mutableListOf<Pair<String, Boolean>>()
    val sb = StringBuilder()
    var prevDigit: Boolean? = null
    for (ch in s) {
        val isDigit = ch.isDigit()
        if (prevDigit != null && isDigit != prevDigit) {
            chunks.add(sb.toString() to prevDigit)
            sb.clear()
        }
        sb.append(ch)
        prevDigit = isDigit
    }
    if (sb.isNotEmpty() && prevDigit != null) chunks.add(sb.toString() to prevDigit)
    return chunks
}
