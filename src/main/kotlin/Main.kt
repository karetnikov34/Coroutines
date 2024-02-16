package ru.netology

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val BASE_URL = "http://127.0.0.1:9999/api" // http://127.0.0.1:9999/api/slow
private val gson = Gson()

private val client = OkHttpClient.Builder()
//    .connectTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .build()

private suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(this::newCall)
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

            })
    }
}

private suspend fun <T> makeRequest(client: OkHttpClient, url: String, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

private suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest(
        client = client,
        url = "$BASE_URL/posts",
        typeToken = object : TypeToken<List<Post>>() {}
    )

private suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest(
        client = client,
        url = "$BASE_URL/posts/$id/comments",
        typeToken = object : TypeToken<List<Comment>>() {}
    )

private suspend fun getAuthor(client: OkHttpClient, id: Long): Author =
    makeRequest(
        client = client,
        url = "$BASE_URL/authors/$id",
        typeToken = object : TypeToken<Author>() {}
    )

fun main() {
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                val posts = getPosts(client)
                    .map { post ->
                        async {
                            val author = getAuthor(client, post.authorId)
                            val comments = getComments(client, post.id)
                                .map { comment ->
                                    FullComment(comment, getAuthor(client, comment.authorId))
                                }
                            FullPost(post, author, comments)
                        }
                    }.awaitAll()
                posts.forEach {
                    println()
                    println("Пост автора ${it.author?.name}: $it ")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    Thread.sleep(10_000L)
}