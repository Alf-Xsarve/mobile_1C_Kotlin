package kg.accounting.mobile

import android.content.Context
import android.graphics.Bitmap
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kg.accounting.mobile.core.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.runner.RunWith

private const val F = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val I = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
private const val O = "cccccccc-cccc-cccc-cccc-cccccccccccc"
private const val D = "dddddddd-dddd-dddd-dddd-dddddddddddd"

class Local1C : AutoCloseable {
    val server = ServerSocket(0)

    val executor = Executors.newCachedThreadPool()

    val requests = CopyOnWriteArrayList<String>()

    val posts = CopyOnWriteArrayList<Row>()

    val created = CopyOnWriteArrayList<Row>()

    var many = false
    var badAuth = false
    val url = "http://127.0.0.1:${server.localPort}/base/odata/standard.odata/"

    init {
        executor.execute {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    executor.execute { serve(socket) }
                } catch (_: Exception) {}
            }
        }
    }

    fun serve(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream()
                fun line(): String {
                    val bytes = mutableListOf<Byte>()
                    while (true) {
                        val b = input.read()
                        if (b < 0 || b == 10) break
                        if (b != 13) bytes.add(b.toByte())
                    }
                    return bytes.toByteArray().toString(Charsets.UTF_8)
                }

                val first = line()
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val l = line()
                    if (l.isEmpty()) break
                    headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val n = input.read(bytes, offset, length - offset)
                    if (n < 0) break
                    offset += n
                }
                if (
                    headers["authorization"] !=
                        "Basic " + Base64.getEncoder().encodeToString("test:secret".toByteArray())
                )
                    badAuth = true
                val path = URLDecoder.decode(first.split(' ')[1], "UTF-8")
                requests += path
                var status = 200
                val response =
                    if (first.startsWith("POST")) {
                        val r = Json.objectValue(String(bytes, Charsets.UTF_8))
                        posts += r
                        created += r
                        status = 201
                        Json.encode(r)
                    } else if (path.contains("\$metadata")) xml
                    else {
                        val rows: List<Row> =
                            when {
                                many && path.contains("Catalog_Номенклатура") ->
                                    if (path.contains("\$skip=50")) emptyList()
                                    else
                                        List(50) { i ->
                                            mapOf(
                                                "Ref_Key" to
                                                    "%08x-bbbb-bbbb-bbbb-bbbbbbbbbbbb".format(i),
                                                "Description" to "Item $i",
                                                "Code" to "$i",
                                            )
                                        }
                                path.contains("Catalog_Контрагенты") -> created.toList()
                                path.contains("Catalog_Организации") ->
                                    listOf(
                                        mapOf(
                                            "Ref_Key" to O,
                                            "Description" to "Тестовая организация",
                                        )
                                    )

                                path.contains("Document_РеализацияТоваровУслуг") ->
                                    listOf(
                                        mapOf(
                                            "Ref_Key" to D,
                                            "Number" to "R-001",
                                            "Date" to "2025-01-09T12:00:00",
                                            "Организация_Key" to O,
                                            "СуммаДокумента" to 900,
                                            "Товары" to
                                                listOf(
                                                    mapOf(
                                                        "Номенклатура_Key" to I,
                                                        "Количество" to 2,
                                                        "Цена" to 450,
                                                    )
                                                ),
                                        )
                                    )

                                path.contains("Parent_Key eq guid'$F'") ->
                                    listOf(
                                        mapOf(
                                            "Ref_Key" to I,
                                            "Description" to "Чай зелёный",
                                            "Code" to "1610",
                                            "IsFolder" to false,
                                        )
                                    )

                                path.contains("Ref_Key eq guid'$I'") ->
                                    listOf(
                                        mapOf(
                                            "Ref_Key" to I,
                                            "Description" to "Чай зелёный",
                                            "Code" to "1610",
                                        )
                                    )

                                else ->
                                    listOf(
                                        mapOf(
                                            "Ref_Key" to F,
                                            "Description" to "Товары",
                                            "IsFolder" to true,
                                        ),
                                        mapOf(
                                            "Ref_Key" to I,
                                            "Description" to "Чай зелёный",
                                            "IsFolder" to false,
                                            "Code" to "1610",
                                        ),
                                    )
                            }
                        Json.encode(mapOf("value" to rows))
                    }
                val body = response.toByteArray(Charsets.UTF_8)
                it.getOutputStream().apply {
                    write(
                        "HTTP/1.1 $status OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray()
                    )
                    write(body)
                    flush()
                }
            } catch (_: Exception) {}
        }
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }

    val xml =
        """<Edmx><DataServices><Schema xmlns="urn:edm" Namespace="S">
      <EntityType Name="Catalog_Номенклатура"><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="Description" Type="Edm.String"/></EntityType>
      <EntityType Name="Catalog_Контрагенты"><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="Description" Type="Edm.String"/></EntityType>
      <EntityType Name="Catalog_Организации"><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="Description" Type="Edm.String"/></EntityType>
      <EntityType Name="Document_РеализацияТоваровУслуг"><Property Name="Организация_Key" Type="Edm.Guid"/><Property Name="Товары" Type="Collection(S.Document_РеализацияТоваровУслуг_Товары_RowType)"/><NavigationProperty Name="Организация" Type="S.Catalog_Организации"/></EntityType>
      <ComplexType Name="Document_РеализацияТоваровУслуг_Товары_RowType"><Property Name="Номенклатура_Key" Type="Edm.Guid"/></ComplexType>
      <EntityType Name="Document_РеализацияТоваровУслуг_Товары"><Property Name="Номенклатура_Key" Type="Edm.Guid"/><NavigationProperty Name="Номенклатура" Type="S.Catalog_Номенклатура"/></EntityType>
      <EntityContainer Name="Data"><EntitySet Name="Catalog_Номенклатура" EntityType="S.Catalog_Номенклатура"/><EntitySet Name="Catalog_Контрагенты" EntityType="S.Catalog_Контрагенты"/><EntitySet Name="Catalog_Организации" EntityType="S.Catalog_Организации"/><EntitySet Name="Document_РеализацияТоваровУслуг" EntityType="S.Document_РеализацияТоваровУслуг"/></EntityContainer>
      </Schema></DataServices></Edmx>"""
}

@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    @get:Rule
    val cameraPermission: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)
    lateinit var server: Local1C

    lateinit var scenario: ActivityScenario<MainActivity>
    val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun start() {
        instrumentation.targetContext
            .getSharedPreferences("encrypted_1c", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        server = Local1C()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitText("Ввести данные вручную")
    }

    @After
    fun finish() {
        scenario.close()
        server.close()
    }

    private fun waitText(text: String) {
        val until = System.currentTimeMillis() + 15000
        var last: Throwable? = null
        while (System.currentTimeMillis() < until) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                last = e
                Thread.sleep(100)
            }
        }
        throw AssertionError("Missing text: $text", last)
    }

    private fun login() {
        onView(withText("Ввести данные вручную")).perform(click())
        onView(withContentDescription("Путь к OData")).perform(scrollTo(), replaceText(server.url))
        onView(withContentDescription("Логин")).perform(scrollTo(), replaceText("test"))
        onView(withContentDescription("Пароль")).perform(scrollTo(), replaceText("secret"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        onView(withText("Войти")).perform(scrollTo(), click())
        waitText("Быстрый доступ")
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values =
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KotlinQA")
                }
            val uri =
                context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )!!
            context.contentResolver.openOutputStream(uri)!!.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        bitmap.recycle()
    }

    @Test
    fun loginFoldersLocalSearchFavoritesSettingsAndRestart() {
        login()
        shot("kotlin-home")
        val requestsBeforeMenu = server.requests.size
        onView(withContentDescription("Меню")).perform(click())
        onView(withContentDescription("Поиск по меню")).perform(replaceText("Сдельная выработка"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        onView(
                allOf(
                    withText("Сдельная выработка"),
                    not(isAssignableFrom(android.widget.EditText::class.java)),
                )
            )
            .check(matches(isDisplayed()))
        shot("kotlin-drawer")
        onView(withContentDescription("Закрыть меню")).perform(click())
        Assert.assertEquals(requestsBeforeMenu, server.requests.size)
        onView(withText("Номенклатура")).perform(scrollTo(), click())
        waitText("▸ Товары")
        onView(withText("▸ Товары")).perform(click())
        waitText("Чай зелёный")
        val count = server.requests.size
        onView(withContentDescription("Поиск по загруженным данным")).perform(replaceText("1610"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitText("Чай зелёный")
        Assert.assertEquals(count, server.requests.size)
        onView(withText("Чай зелёный")).perform(click())
        waitText("Description")
        onView(withContentDescription("Избранное: Чай зелёный")).perform(click())
        shot("kotlin-card")
        onView(withContentDescription("Копировать Description")).perform(click())
        onView(withContentDescription("Настроить реквизиты")).perform(click())
        waitText("Видимые реквизиты")
        onView(withText("Code")).perform(click())
        onView(withText("Сохранить")).perform(click())
        onView(withText("Code")).check(doesNotExist())
        onView(withContentDescription("На главную")).perform(click())
        waitText("Избранное")
        waitText("Недавно открытые")
        onView(withContentDescription("Настройки")).perform(click())
        onView(withText("Тёмная")).perform(click())
        shot("kotlin-settings-dark")
        scenario.recreate()
        waitText("Настройки")
        onView(withContentDescription("На главную")).perform(click())
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitText("Избранное")
        Assert.assertFalse(server.badAuth)
        val stored =
            instrumentation.targetContext
                .getSharedPreferences("encrypted_1c", Context.MODE_PRIVATE)
                .all
                .values
                .joinToString()
        Assert.assertFalse(stored.contains("secret"))
        Assert.assertFalse(stored.contains(server.url))
    }

    @Test
    fun createCounterpartyAndResolveDocumentTableReferences() {
        login()
        onView(withText("Контрагенты")).perform(scrollTo(), click())
        waitText("Создать: Контрагенты")
        onView(withText("Создать: Контрагенты")).perform(click())
        waitText("Папка: Корень справочника")
        onView(withContentDescription("Наименование"))
            .perform(scrollTo(), replaceText("Test counterparty"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        onView(withText("Создать в 1С")).perform(scrollTo(), click())
        waitText("Description")
        Assert.assertEquals(1, server.posts.size)
        Assert.assertEquals("Test counterparty", server.posts.single()["Description"])
        Assert.assertEquals(ZERO, server.posts.single()["Parent_Key"])
        onView(withContentDescription("На главную")).perform(click())
        onView(withText("Реализация товаров и услуг")).perform(scrollTo(), click())
        waitText("Дата с")
        onView(withText("Дата с")).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        onView(withText("Дата по")).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        onView(withText("Получить документы")).perform(click())
        waitText("№ R-001")
        onView(withText("№ R-001")).perform(click())
        waitText("Тестовая организация")
        shot("kotlin-document")
        onData(anything())
            .inAdapterView(isAssignableFrom(ListView::class.java))
            .atPosition(15)
            .perform(click())
        waitText("[1]")
        onView(withText("[1]")).perform(click())
        waitText("Чай зелёный")
        shot("kotlin-document-row")
        Assert.assertFalse(server.badAuth)
    }

    @Test
    fun listPositionSurvivesNavigationAndReload() {
        server.many = true
        login()
        onView(withContentDescription("Номенклатура")).perform(scrollTo(), click())
        waitText("Item 0")
        onData(anything())
            .inAdapterView(isAssignableFrom(ListView::class.java))
            .atPosition(35)
            .perform(click())
        waitText("Description")
        onView(withContentDescription("На главную")).perform(click())
        onView(withContentDescription("Номенклатура")).perform(scrollTo(), click())
        waitText("Item 35")
        shot("kotlin-restored-list")
    }

    @Test
    fun qrCameraOpensAndCancelReturnsToCollapsedLogin() {
        onView(withContentDescription("Пароль")).check(doesNotExist())
        onView(withText("Сканировать QR-код")).perform(click())
        waitText("Наведите камеру на QR-код подключения к 1С")
        shot("kotlin-qr-camera")
        onView(withText("Отмена")).perform(click())
        waitText("Ввести данные вручную")
        onView(withContentDescription("Пароль")).check(doesNotExist())
    }
}
