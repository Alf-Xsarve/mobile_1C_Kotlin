package kg.accounting.mobile.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kg.accounting.mobile.core.*

data class HttpResult(val status: Int, val body: String)

interface Transport : AutoCloseable {
    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        xml: Boolean = false,
    ): HttpResult

    override fun close() {}
}

class UrlTransport(profile: Profile) : Transport {
    private var authorization =
        "Basic " +
            Base64.getEncoder()
                .encodeToString(
                    "${profile.username}:${profile.password}".toByteArray(Charsets.UTF_8)
                )
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    override fun request(url: String, method: String, body: String?, xml: Boolean): HttpResult {
        if (closed || Thread.currentThread().isInterrupted) throw AppError("Подключение закрыто.")
        val connection = URL(url).openConnection() as HttpURLConnection
        connections.add(connection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty(
                "Accept",
                if (xml) "application/xml" else "application/json",
            )
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val limit =
                if (xml) 16 * 1024 * 1024 else if (status in 200..299) 4 * 1024 * 1024 else 8192
            val deadline = System.nanoTime() + 25_000_000_000
            stream?.use { input ->
                while (true) {
                    if (
                        closed ||
                            Thread.currentThread().isInterrupted ||
                            System.nanoTime() > deadline
                    )
                        throw AppError("Загрузка прервана или сервер не ответил вовремя.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) {
                        if (status !in 200..299) break
                        throw AppError("Ответ сервера слишком большой.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return HttpResult(status, output.toString("UTF-8").removePrefix("\uFEFF"))
        } finally {
            connections.remove(connection)
            connection.disconnect()
        }
    }

    override fun close() {
        closed = true
        authorization = ""
        connections.forEach { it.disconnect() }
        connections.clear()
    }
}

fun HttpResult.checked(): String {
    if (status in 200..299) return body
    val details =
        runCatching {
                Json.objectValue(body)["odata.error"].row()["message"].let {
                        if (it is Map<*, *>) it["value"].text() else it.text()
                    }
                    .take(2000)
            }
            .getOrDefault("")
    throw AppError(
        when (status) {
            401 -> "Неверный логин или пароль либо сервер требует другой способ входа."
            403 -> "У пользователя нет доступа к этим данным."
            404 -> "Объект не найден. Проверьте публикацию OData."
            in 300..399 -> "Сервер перенаправляет запрос. Укажите конечный адрес OData."
            502 -> "Apache не получил ответ от сервера 1С (HTTP 502)."
            else -> "Ошибка сервера 1С (HTTP $status)."
        },
        status,
        details.ifEmpty { null },
    )
}
