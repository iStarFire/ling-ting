package com.lingting.network

import com.lingting.data.model.WebDavFile
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * 解析 WebDAV PROPFIND 的 multistatus 响应。
 *
 * 设计要点：
 * 1. 使用 SAX 且 [SAXParserFactory.isNamespaceAware] = true，以 [localName] 匹配标签，
 *    从而忽略服务器返回的命名空间前缀（如 `<D:href>`、`<D:collection>`），
 *    这正是此前子目录“不显示”的根因——旧解析器按 `D:href` 精确匹配导致全部跳过。
 * 2. 将服务器返回的 href（可能是带挂载点前缀的绝对路径，如 `/dav/夸克/` 或完整 URL）
 *    归一成相对 [baseUrl] 的本地路径（如 `/夸克/`），避免导航时拼接出 `…/dav/dav/…` 的双前缀。
 *
 * 不依赖任何 Android 框架类，可直接在 JVM 单元测试中运行。
 */
object WebDavPropfindParser {

    fun parse(responseBody: String, baseUrl: String, requestPath: String): List<WebDavFile> {
        val basePath = basePathOf(baseUrl)
        val files = mutableListOf<WebDavFile>()

        val handler = object : DefaultHandler() {
            private var currentHref = ""
            private var displayName = ""
            private var currentSize = 0L
            private var currentContentType: String? = null
            private var inResourceType = false
            private var inCollection = false

            private var captureHref = false
            private var captureDisplayName = false
            private var captureContentLength = false
            private var captureContentType = false

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                when (tag(localName, qName)) {
                    "href" -> captureHref = true
                    "displayname" -> captureDisplayName = true
                    "getcontentlength" -> captureContentLength = true
                    "getcontenttype" -> captureContentType = true
                    "resourcetype" -> inResourceType = true
                    "collection" -> if (inResourceType) inCollection = true
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                val chars = ch ?: return
                val text = String(chars, start, length)
                when {
                    captureHref -> currentHref += text
                    captureDisplayName -> displayName += text
                    captureContentLength -> currentSize = text.toLongOrNull() ?: 0L
                    captureContentType -> currentContentType = text.trim().ifBlank { null }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                    "href" -> captureHref = false
                    "displayname" -> captureDisplayName = false
                    "getcontentlength" -> captureContentLength = false
                    "getcontenttype" -> captureContentType = false
                    "resourcetype" -> inResourceType = false
                    "response" -> {
                        val rawHref = currentHref.trim().ifBlank { null } ?: return
                        val relative = toRelativePath(rawHref, basePath)
                        val parentPath = requestPath.trimEnd('/')
                        if (relative.trimEnd('/') != parentPath) {
                            val name = displayName.ifBlank { null }
                                ?: relative.trimEnd('/').substringAfterLast('/').ifBlank { relative }
                            files.add(
                                WebDavFile(
                                    path = relative,
                                    name = name,
                                    isDirectory = inCollection,
                                    size = currentSize,
                                    contentType = currentContentType ?: ""
                                )
                            )
                        }
                        currentHref = ""
                        displayName = ""
                        currentSize = 0L
                        currentContentType = null
                        inCollection = false
                        inResourceType = false
                    }
                }
            }
        }

        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newSAXParser()!!
        parser.parse(InputSource(StringReader(responseBody)), handler)
        return files
    }

    private fun tag(localName: String?, qName: String?): String =
        (localName?.takeIf { it.isNotEmpty() } ?: qName ?: "").substringAfter(':')

    internal fun toRelativePath(rawHref: String, basePath: String): String {
        val hrefPath = extractPathFromHref(rawHref)
        val decoded = decodePercent(hrefPath)
        return decoded.removePrefix(basePath).ifBlank { "/" }
    }

    private fun extractPathFromHref(rawHref: String): String {
        return if (rawHref.startsWith("http", ignoreCase = true)) {
            val afterScheme = rawHref.substringAfter("://")
            "/${afterScheme.substringAfter("/")}"
        } else {
            rawHref
        }
    }

    internal fun basePathOf(baseUrl: String): String {
        val afterScheme = baseUrl.substringAfter("://")
        val path = if (afterScheme.contains("/")) afterScheme.substringAfter("/") else ""
        return if (path.isBlank()) "/" else "/$path".trimEnd('/')
    }

    internal fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val b = runCatching { hex.toInt(16).toByte() }.getOrNull()
                if (b != null) {
                    bytes.write(b.toInt() and 0xFF)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code)
            i++
        }
        return String(bytes.toByteArray(), charset("UTF-8"))
    }
}
