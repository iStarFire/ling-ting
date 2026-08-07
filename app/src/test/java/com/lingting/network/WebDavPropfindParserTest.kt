package com.lingting.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WebDavPropfindParser] 的单元测试（纯 JVM，不依赖 Android 框架）。
 *
 * 覆盖本次修复的两个核心问题：
 * 1. 服务器返回带命名空间前缀的标签（<D:href> 等）时仍能正确解析；
 * 2. 将服务器返回的含挂载点前缀的绝对路径（/dav/... 或完整 URL）归一成相对 baseUrl 的本地路径。
 */
class WebDavPropfindParserTest {

    private val baseUrl = "http://example.com:5244/dav"

    @Test
    fun rootListingSkipsSelfAndReturnsSubdirs() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<multistatus xmlns:D="DAV:">
<response><D:href>/dav/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>root</D:displayname></D:prop></D:propstat></response>
<response><D:href>/dav/%E5%A4%B8%E5%85%8B/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>夸克</D:displayname></D:prop></D:propstat></response>
</multistatus>"""
        val files = WebDavPropfindParser.parse(xml, baseUrl, "/")
        assertEquals(1, files.size)
        assertEquals("夸克", files[0].name)
        assertEquals("/夸克/", files[0].path)
        assertEquals(true, files[0].isDirectory)
    }

    @Test
    fun subdirListingDecodesNamesAndSkipsSelf() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<multistatus xmlns:D="DAV:">
<response><D:href>/dav/%E5%A4%B8%E5%85%8B/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>夸克</D:displayname></D:prop></D:propstat></response>
<response><D:href>/dav/%E5%A4%B8%E5%85%8B/%E6%9C%89%E5%A3%B0%E4%B9%A6/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>有声书</D:displayname></D:prop></D:propstat></response>
<response><D:href>/dav/%E5%A4%B8%E5%85%8B/song.mp3</D:href><D:propstat><D:prop><D:getcontenttype>audio/mpeg</D:getcontenttype></D:prop></D:propstat></response>
</multistatus>"""
        val files = WebDavPropfindParser.parse(xml, baseUrl, "/夸克/")
        // 自身 + 2 个子项 => 2
        assertEquals(2, files.size)
        assertEquals("有声书", files[0].name)
        assertEquals("/夸克/有声书/", files[0].path)
        assertEquals(true, files[0].isDirectory)
        assertEquals("song.mp3", files[1].name)
        assertEquals("/夸克/song.mp3", files[1].path)
        assertEquals(false, files[1].isDirectory)
    }

    @Test
    fun fullUrlHrefIsHandled() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<multistatus xmlns="DAV:">
<response><href>http://example.com:5244/dav/folder/</href><propstat><prop><resourcetype><collection/></resourcetype><displayname>folder</displayname></prop></propstat></response>
</multistatus>"""
        val files = WebDavPropfindParser.parse(xml, baseUrl, "/")
        assertEquals(1, files.size)
        assertEquals("/folder/", files[0].path)
    }

    @Test
    fun unprefixedTagsAlsoWork() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<multistatus xmlns="DAV:">
<response><href>/dav/music/</href><propstat><prop><resourcetype><collection/></resourcetype><displayname>music</displayname></prop></propstat></response>
</multistatus>"""
        val files = WebDavPropfindParser.parse(xml, baseUrl, "/")
        assertEquals(1, files.size)
        assertEquals("music", files[0].name)
        assertEquals("/music/", files[0].path)
    }

    @Test
    fun toRelativePathStripsBasePath() {
        assertEquals(
            "/夸克/",
            WebDavPropfindParser.toRelativePath("/dav/%E5%A4%B8%E5%85%8B/", "/dav")
        )
        assertEquals(
            "/folder/",
            WebDavPropfindParser.toRelativePath("http://example.com:5244/dav/folder/", "/dav")
        )
    }

    @Test
    fun decodePercentDecodesUtf8() {
        assertEquals(
            "/dav/夸克/",
            WebDavPropfindParser.decodePercent("/dav/%E5%A4%B8%E5%85%8B/")
        )
    }

    @Test
    fun basePathOfReturnsMountPath() {
        assertEquals("/dav", WebDavPropfindParser.basePathOf("http://example.com:5244/dav"))
        assertEquals("/", WebDavPropfindParser.basePathOf("http://example.com:5244"))
    }
}
