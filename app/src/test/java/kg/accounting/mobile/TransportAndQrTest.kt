package kg.accounting.mobile

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kg.accounting.mobile.core.*
import kg.accounting.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class TransportAndQrTest {
    @Test
    fun qrDecoderReadsSameRussianCredentialsAsPreviousApp() {
        val sample =
            Profile.create("https://example.test/base/odata/standard.odata/", "Тест", "пароль")
        val matrix =
            QRCodeWriter()
                .encode(
                    Json.encode(sample.json()),
                    BarcodeFormat.QR_CODE,
                    400,
                    400,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
                )
        val pixels =
            IntArray(400 * 400) { i -> if (matrix[i % 400, i / 400]) 0xFF000000.toInt() else -1 }
        val decoded =
            MultiFormatReader()
                .decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(400, 400, pixels))))
        val profile = Profile.fromCode(decoded.text)
        assertEquals(sample.url, profile.url)
        assertEquals(sample.username, profile.username)
        assertEquals(sample.password, profile.password)
    }

    @Test
    fun realHttpUsesUtf8BasicAndRejectsRedirectWithoutFollowingIt() {
        val socket = ServerSocket(0)
        val authorization = AtomicReference<String>()
        val server =
            Thread {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        reader.readLine()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", true))
                                authorization.set(line.substringAfter(':').trim())
                        }
                        client
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:9/foreign\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        val p =
            Profile.create(
                "http://127.0.0.1:${socket.localPort}/base/odata/standard.odata/",
                "Тест",
                "пароль",
            )
        val c = ODataClient(p, registry())
        try {
            val e = assertThrows(AppError::class.java) { c.page("Номенклатура") }
            assertEquals(302, e.status)
            assertEquals(
                "Basic " +
                    Base64.getEncoder().encodeToString("Тест:пароль".toByteArray(Charsets.UTF_8)),
                authorization.get(),
            )
        } finally {
            c.close()
            socket.close()
            server.join(1000)
        }
    }
}
