package com.tingyiting.data.repository

import com.tingyiting.data.model.WebDavFile
import com.tingyiting.network.WebDavClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavRepository @Inject constructor() {

    private var client: WebDavClient? = null

    fun configure(baseUrl: String, username: String, password: String) {
        client = WebDavClient(baseUrl, username, password)
    }

    fun isConfigured(): Boolean = client != null

    fun testConnection(): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("请先配置服务器"))
        return c.testConnection()
    }

    fun listFiles(path: String = "/"): Result<List<WebDavFile>> {
        val c = client ?: return Result.failure(IllegalStateException("请先配置服务器"))
        return c.listFiles(path)
    }

    fun buildFileUrl(filePath: String): String {
        val c = client ?: throw IllegalStateException("请先配置服务器")
        return c.buildFileUrl(filePath)
    }

    fun getAuthHeader(): String {
        val c = client ?: throw IllegalStateException("请先配置服务器")
        return c.getAuthHeader()
    }
}
