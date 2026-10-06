package com.fourseveneightnine.tv.client.data

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** One canned answer. [delayMillis] lets a test make one add-on the slow one. */
data class FakeAnswer(
    val code: Int = 200,
    val body: String = "{}",
    val delayMillis: Long = 0,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * An OkHttp client that never touches the network.
 *
 * An application interceptor that returns its own [Response] short-circuits the whole chain, so
 * every fixture test runs on a plain JVM with no server to boot and no port to fight over.
 */
class FakeHttp(private val route: (Request) -> FakeAnswer) {

    /** Every URL that was asked for, in order. Tests assert on the route, not just the body. */
    val requests: MutableList<Request> = CopyOnWriteArrayList()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                requests += request
                val answer = route(request)
                if (answer.delayMillis > 0) Thread.sleep(answer.delayMillis)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.code)
                    .message(if (answer.code in 200..299) "OK" else "Error")
                    .body(answer.body.toResponseBody("application/json".toMediaType()))
                    .apply { answer.headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            },
        )
        .build()

    val urls: List<String> get() = requests.map { it.url.toString() }

    fun urlsContaining(fragment: String): List<String> = urls.filter { it.contains(fragment) }
}

/** Read a fixture out of `src/test/resources`. */
fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.classLoader.getResourceAsStream(name)) {
        "missing fixture: $name"
    }.bufferedReader().use { it.readText() }
