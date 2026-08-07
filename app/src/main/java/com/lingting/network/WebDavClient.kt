package com.lingting.network

import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lingting.data.model.WebDavFile
import java.util.concurrent.TimeUnit

private const val TAG = "WebDavClient"

class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val credentials: String = Credentials.basic(username, password)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 测试连接 */
    fun testConnection(): Result<Unit> = runCatching {
        val url = baseUrl.trimEnd('/') + "/"
        // 注意：不打印 credentials（含明文账号密码 base64）
        Log.d(TAG, "testConnection url=$url user=$username method=PROPFIND depth=0")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credentials)
            .header("Depth", "0")
            .build()
        val response = client.newCall(request).execute()
        val bodyText = response.body?.string()
        if (!response.isSuccessful) {
            Log.e(TAG, "testConnection HTTP ${response.code} url=$url body=${bodyText?.take(500)}")
            throw IllegalStateException("HTTP ${response.code} - 服务器返回错误，请检查 WebDAV 地址是否正确（Alist 需要 /dav 路径）")
        }
        Log.d(TAG, "testConnection OK (HTTP ${response.code})")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "testConnection exception url=${baseUrl.trimEnd('/')}/", e)
        Unit
    }

    /** 获取指定路径下的文件列表 */
    fun listFiles(path: String = "/"): Result<List<WebDavFile>> = runCatching {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credentials)
            .header("Depth", "1")
            .build()

        val response = client.newCall(request).execute()
        require(response.isSuccessful) { "获取文件列表失败: ${response.code}" }

        val body = response.body?.string() ?: ""
        WebDavPropfindParser.parse(body, baseUrl, path)
    }

    /** 获取授权 Header，供 Media3 DataSource 使用 */
    fun getAuthHeader(): String = credentials

    /** 构建完整的 WebDAV 文件 URL */
    fun buildFileUrl(filePath: String): String = buildUrl(filePath)

    private fun buildUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return if (cleanPath.isEmpty()) "$base/" else "$base/$cleanPath"
    }
}
