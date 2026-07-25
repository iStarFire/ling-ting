package com.tingyiting.data.repository

import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.model.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao
) {
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks().map { entities ->
        entities.map { it.toBook() }
    }

    suspend fun getBookById(id: Long): Book? = bookDao.getBookById(id)?.toBook()

    suspend fun getBookByUrl(url: String): Book? = bookDao.getBookByUrl(url)?.toBook()

    suspend fun addBook(title: String, author: String, webdavUrl: String, coverUrl: String = ""): Long {
        val existing = bookDao.getBookByUrl(webdavUrl)
        if (existing != null) return existing.id
        return bookDao.insert(
            BookEntity(
                title = title,
                author = author,
                webdavUrl = webdavUrl,
                coverUrl = coverUrl,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteBook(id: Long) {
        bookDao.getBookById(id)?.let { bookDao.delete(it) }
    }

    suspend fun updateProgress(id: Long, position: Long, duration: Long) {
        bookDao.updateProgress(id, position, duration, System.currentTimeMillis())
    }

    private fun BookEntity.toBook() = Book(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        webdavUrl = webdavUrl,
        duration = duration,
        position = position
    )
}
