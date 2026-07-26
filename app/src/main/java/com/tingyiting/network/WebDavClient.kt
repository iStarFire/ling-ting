package com.tingyiting.network

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.tingyiting.data.model.WebDavFile
import java.io.StringReader
import java.util.concurrent.TimeUnit

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
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/")
            .method("PROPFIND", null)
            .header("Authorization", credentials)
            .header("Depth", "0")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code} - 服务器返回错误，请检查 WebDAV 地址是否正确（Alist 需要 /dav 路径）")
        }
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
        parsePropfindResponse(body, path)
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

    private fun parsePropfindResponse(xml: String, parentPath: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var currentName = ""
        var currentPath = ""
        var currentIsDir = false
        var currentSize = 0L
        var currentContentType = ""
        var inResponse = false
        var inHref = false
        var inDisplayName = false
        var inResourceType = false
        var inCollection = false
        var inContentLength = false
        var inContentType = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name.lowercase()
                    when {
                        tagName == "response" -> {
                            inResponse = true
                            currentName = ""
                            currentPath = ""
                            currentIsDir = false
                            currentSize = 0L
                            currentContentType = ""
                            inCollection = false
                        }
                        tagName == "href" -> inHref = true
                        tagName == "displayname" -> inDisplayName = true
                        tagName == "resourcetype" -> inResourceType = true
                        tagName == "collection" -> if (inResourceType) inCollection = true
                        tagName == "getcontentlength" -> inContentLength = true
                        tagName == "getcontenttype" -> inContentType = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (inHref) currentPath = text
                    if (inDisplayName) currentName = text
                    if (inContentLength) currentSize = text.toLongOrNull() ?: 0L
                    if (inContentType) currentContentType = text
                }
                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.lowercase()
                    when {
                        tagName == "response" -> {
                            if (inResponse && currentPath.isNotBlank()) {
                                // 跳过根路径自身
                                val displayName = currentName.ifBlank {
                                    currentPath.trimEnd('/').substringAfterLast('/')
                                }
                                if (displayName.isNotBlank() && currentPath.trimEnd('/') != parentPath.trimEnd('/')) {
                                    files.add(
                                        WebDavFile(
                                            name = displayName,
                                            path = currentPath,
                                            isDirectory = inCollection,
                                            size = currentSize,
                                            contentType = currentContentType
                                        )
                                    )
                                }
                            }
                            inResponse = false
                        }
                        tagName == "href" -> inHref = false
                        tagName == "displayname" -> inDisplayName = false
                        tagName == "resourcetype" -> inResourceType = false
                        tagName == "collection" -> inCollection = false
                        tagName == "getcontentlength" -> inContentLength = false
                        tagName == "getcontenttype" -> inContentType = false
                    }
                }
            }
            eventType = parser.next()
        }
        return files
    }
}
