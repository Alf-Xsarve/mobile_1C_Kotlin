package kg.accounting.mobile

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import kg.accounting.mobile.core.*
import kg.accounting.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

const val A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
const val B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
const val C = "cccccccc-cccc-cccc-cccc-cccccccccccc"

class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String) = values[key]

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class FakeTransport(var handler: (String, String, String?) -> HttpResult) : Transport {
    val calls = mutableListOf<Triple<String, String, String?>>()

    override fun request(url: String, method: String, body: String?, xml: Boolean): HttpResult {
        calls += Triple(url, method, body)
        return handler(url, method, body)
    }
}

fun registry() = Registry(File("src/main/assets/schema.json").readText())

fun profile(host: String = "example.test", user: String = "test") =
    Profile.create("https://$host/base/odata/standard.odata/", user, "secret")

fun success(rows: List<Row>) = HttpResult(200, Json.encode(mapOf("value" to rows)))

fun query(url: String) =
    URI(url)
        .rawQuery
        .orEmpty()
        .split('&')
        .filter { it.contains('=') }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

class CoreTest {
    @Test
    fun qrCompatibleWithFlutter() {
        val p =
            Profile.fromCode(
                Json.encode(
                    mapOf(
                        "type" to "accounting_1c_connection",
                        "version" to 1,
                        "odataUrl" to "http://10.0.2.2/base/odata/standard.odata",
                        "username" to "test",
                        "password" to "secret",
                    )
                )
            )
        assertTrue(p.url.endsWith('/'))
        assertEquals("test", p.username)
        assertFalse(p.toString().contains("secret"))
    }

    @Test
    fun invalidQrRejected() {
        listOf("https://evil.test/", "{}", "{\"type\":\"other\"}").forEach {
            assertThrows(AppError::class.java) { Profile.fromCode(it) }
        }
    }

    @Test
    fun invalidAddressesRejected() {
        listOf(
                "file:///x",
                "https://u:p@example.test/base/odata/standard.odata/",
                "https://example.test/base/odata/standard.odata/?x=1",
                "https://example.test/other/",
            )
            .forEach { assertThrows(AppError::class.java) { Profile.create(it, "test", "secret") } }
    }

    @Test
    fun credentialsValidated() {
        assertThrows(AppError::class.java) { Profile.create(profile().url, "a:b", "x") }
        assertThrows(AppError::class.java) { Profile.create(profile().url, "user", "") }
    }

    @Test
    fun sameMonthPeriodInclusive() {
        assertEquals(
            "year(Date) eq 2025 and month(Date) eq 1 and day(Date) ge 9 and day(Date) le 19",
            Period(LocalDate.of(2025, 1, 9), LocalDate.of(2025, 1, 19)).filter("Date"),
        )
    }

    @Test
    fun crossYearPeriodAndInvalidOrder() {
        val f = Period(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 2)).filter("Period")
        assertTrue(f.contains("year(Period) gt 2024"))
        assertTrue(f.contains("day(Period) le 2"))
        assertFalse(f.contains("datetime"))
        assertThrows(AppError::class.java) {
            Period(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1))
        }
    }

    @Test
    fun menuMatchesAllPreviousSections() {
        val r = registry()
        assertEquals(7, r.menu.size)
        assertEquals(102, r.resources.size)
        assertEquals(3, r.menu.find { it.id == "production" }!!.children.size)
        r.nodes()
            .filter { it.kind == "resource" }
            .forEach { assertTrue(it.title, r.resources.containsKey(it.title)) }
        r.nodes()
            .filter { it.kind == "journal" }
            .forEach { n -> n.sources.forEach { assertEquals("document", r.resources[it]!!.kind) } }
    }

    @Test
    fun rootQueryIncludesBothFoldersAndItemsAndEncodedGuidQuotes() {
        val t = FakeTransport { _, _, _ -> success(emptyList()) }
        ODataClient(profile(), registry(), t).page("Номенклатура")
        val url = t.calls.single().first
        assertTrue(url.contains("%27"))
        assertEquals("(Parent_Key eq guid'$ZERO')", query(url)["\$filter"])
        assertFalse(query(url)["\$filter"]!!.contains("IsFolder"))
    }

    @Test
    fun childFolderUsesParentAndLocalSearchIsAbsentFromServer() {
        val t = FakeTransport { _, _, _ -> success(emptyList()) }
        ODataClient(profile(), registry(), t).page("Номенклатура", parent = A)
        assertTrue(query(t.calls.single().first)["\$filter"]!!.contains(A))
        assertFalse(t.calls.single().first.contains("substringof"))
        assertFalse(t.calls.single().first.contains("tolower"))
    }

    @Test
    fun periodsUseResourceDateField() {
        val t = FakeTransport { _, _, _ -> success(emptyList()) }
        val c = ODataClient(profile(), registry(), t)
        c.page(
            "Сведения об инвалидности физ. лиц",
            period = Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2)),
        )
        assertTrue(query(t.calls.single().first)["\$filter"]!!.contains("year(Period)"))
    }

    @Test
    fun jsonShapeAndHttpErrorsAreNotAcceptedAsLogin() {
        val t = FakeTransport { _, _, _ -> HttpResult(200, "<html>login</html>") }
        val c = ODataClient(profile(), registry(), t)
        assertThrows(AppError::class.java) { c.page("Номенклатура") }
        t.handler = { _, _, _ ->
            HttpResult(401, "{\"odata.error\":{\"message\":{\"value\":\"Ошибка входа\"}}}")
        }
        val e = assertThrows(AppError::class.java) { c.page("Номенклатура") }
        assertEquals(401, e.status)
        assertEquals("Ошибка входа", e.details)
    }

    @Test
    fun typedLocatorReadsFreshRecord() {
        val r = registry()
        val l =
            Locator.fromRow(r.resources.getValue("Номенклатура"), mapOf("Ref_Key" to A), "old")!!
        val t = FakeTransport { _, _, _ ->
            success(listOf(mapOf("Ref_Key" to A, "Description" to "fresh")))
        }
        assertEquals("fresh", ODataClient(profile(), r, t).readRecord(l)!!["Description"])
        assertTrue(query(t.calls.single().first)["\$filter"]!!.contains("guid'$A'"))
    }

    @Test
    fun foreignLocatorRejectedBeforeRequest() {
        val t = FakeTransport { _, _, _ -> success(emptyList()) }
        assertThrows(AppError::class.java) {
            ODataClient(profile(), registry(), t)
                .readRecord(Locator("Номенклатура", "Catalog_Other", "x", mapOf("Ref_Key" to A)))
        }
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun dateKeyMatchesFullTimeAndPagesCandidates() {
        val r = registry()
        val spec = r.resources.getValue("Сведения об инвалидности физ. лиц")
        val wanted = mapOf("Period" to "2025-01-09T15:30:00", "Физлицо_Key" to A)
        val t = FakeTransport { url, _, _ ->
            success(
                if (query(url)["\$skip"] == "0")
                    List(50) { mapOf("Period" to "2025-01-09T10:00:00", "Физлицо_Key" to A) }
                else listOf(wanted)
            )
        }
        assertEquals(
            wanted,
            ODataClient(profile(), r, t).readRecord(Locator.fromRow(spec, wanted)!!),
        )
        assertEquals(2, t.calls.size)
        assertFalse(query(t.calls[0].first)["\$filter"]!!.contains("datetime"))
    }

    @Test
    fun ambiguousKeyDoesNotOpenRandomRecord() {
        val r = registry()
        val spec = r.resources.getValue("Взаиморасчёты по ЗП")
        val row = mapOf("LineNumber" to 1)
        val t = FakeTransport { _, _, _ -> success(listOf(row, row)) }
        assertThrows(AppError::class.java) {
            ODataClient(profile(), r, t).readRecord(Locator.fromRow(spec, row)!!)
        }
    }

    @Test
    fun inventoryPayloadUsesClassifierAndVedKeys() {
        val d =
            CatalogDraft(
                "Номенклатура",
                name = " Чай ",
                folder = Selection(A, "Товары"),
                unit = Selection(B, "шт"),
                ved = Selection(C, "Чай"),
            )
        val p = d.payload()
        assertEquals("Чай", p["Description"])
        assertEquals(A, p["Parent_Key"])
        assertEquals(B, p["БазоваяЕдиницаИзмерения_Key"])
        assertEquals(C, p["ВЭД_Key"])
        assertEquals(false, p["IsFolder"])
    }

    @Test
    fun requiredCatalogFieldsAndTypesValidated() {
        assertThrows(AppError::class.java) { CatalogDraft("Контрагенты").payload() }
        assertThrows(AppError::class.java) {
            CatalogDraft("Контрагенты", name = "x", personType = "bad").payload()
        }
        assertThrows(AppError::class.java) {
            CatalogDraft("Склады", name = "x", folder = Selection("bad")).payload()
        }
    }

    @Test
    fun createSendsOnlyOnePostWithStableKey() {
        val t = FakeTransport { _, _, body -> HttpResult(201, body!!) }
        val row =
            ODataClient(profile(), registry(), t)
                .create("Склады", CatalogDraft("Склады", name = "Новый").payload())
        assertTrue(GUID.matches(row["Ref_Key"].text()))
        assertEquals(1, t.calls.size)
        assertEquals("POST", t.calls.single().second)
    }

    @Test
    fun uncertainCreateIsNeverRetried() {
        val t = FakeTransport { _, _, _ -> throw java.net.SocketTimeoutException() }
        val e =
            assertThrows(CreationUncertain::class.java) {
                ODataClient(profile(), registry(), t)
                    .create("Контрагенты", CatalogDraft("Контрагенты", name = "x").payload())
            }
        assertTrue(GUID.matches(e.locator.keys["Ref_Key"].text()))
        assertEquals(1, t.calls.size)
    }

    @Test
    fun serverFailureAfterPostIsUncertain() {
        val t = FakeTransport { _, _, _ -> HttpResult(500, "") }
        assertThrows(CreationUncertain::class.java) {
            ODataClient(profile(), registry(), t)
                .create("Склады", CatalogDraft("Склады", name = "x").payload())
        }
        assertEquals(1, t.calls.size)
    }

    @Test
    fun preferencesSurviveRestartAndAreNamespaced() {
        val r = registry()
        val store = MemoryStore()
        val p = UserPreferences(store, r, profile())
        val locator =
            Locator.fromRow(r.resources.getValue("Номенклатура"), mapOf("Ref_Key" to A), "Чай")!!
        p.toggle(Shortcut(locator = locator))
        p.remember(locator)
        p.remember("Номенклатура", A, ListMemory("чай", 4, -12, 100, "2025-01-01", "2025-01-19"))
        p.folder("Номенклатура", Selection(A, "Товары", listOf("Товары")))
        p.hidden("card:x", setOf("Комментарий"))
        p.save()
        val restored = UserPreferences(store, r, profile()).apply { load() }
        assertEquals(1, restored.favorites.size)
        assertEquals(1, restored.recent.size)
        assertEquals(4, restored.view("Номенклатура", A).first)
        assertEquals(A, restored.folder("Номенклатура").key)
        assertEquals(setOf("Комментарий"), restored.hidden("card:x"))
        assertTrue(
            UserPreferences(store, r, profile("other.test")).apply { load() }.recent.isEmpty()
        )
        assertNotEquals(p.key, UserPreferences(store, r, profile(user = "other")).key)
        assertFalse(store.values.values.single().contains("secret"))
    }

    @Test
    fun historyBoundedAndClearPreservesFavorites() {
        val r = registry()
        val p = UserPreferences(MemoryStore(), r, profile())
        for (i in 0..34) p.remember(
            Locator.fromRow(
                r.resources.getValue("Номенклатура"),
                mapOf("Ref_Key" to "%08x-aaaa-aaaa-aaaa-aaaaaaaaaaaa".format(i)),
                "Item $i",
            )!!
        )
        assertEquals(30, p.recent.size)
        p.toggle(Shortcut(locator = p.recent.first()))
        p.saveHistory = false
        p.remember(p.recent.last())
        assertEquals("Item 34", p.recent.first().title)
        p.clearHistory()
        assertTrue(p.recent.isEmpty())
        assertEquals(1, p.favorites.size)
    }

    @Test
    fun metadataResolvesInlineTablePeerAndAliases() {
        val m = MetadataModel(metadata)
        assertEquals(
            "S.Catalog_Номенклатура",
            m.reference("S.Document_Test_Товары_RowType", "Номенклатура"),
        )
        assertEquals("Catalog_Номенклатура", m.setFor("A.Catalog_Номенклатура"))
        assertEquals("S.Document_Test_Товары_RowType", m.property("S.Document_Test", "Товары"))
    }

    @Test
    fun metadataRejectsExternalEntities() {
        assertThrows(AppError::class.java) {
            MetadataModel("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///x'>]><x/>")
        }
    }

    @Test
    fun referencesResolveRowsAndCacheWithoutChangingRawJson() {
        val t = FakeTransport { url, _, _ ->
            if (url.contains("metadata")) HttpResult(200, metadata)
            else success(listOf(mapOf("Ref_Key" to A, "Description" to "Чай")))
        }
        val c = ODataClient(profile(), registry(), t)
        val raw = mapOf("Товары" to listOf(mapOf("Номенклатура_Key" to A, "Количество" to 2)))
        val out = c.references.resolve(raw, "Document_Test")
        assertEquals("Чай", out.value.row()["Товары"].rows().single()["Номенклатура_Key"])
        assertEquals(A, raw["Товары"]!!.single()["Номенклатура_Key"])
        assertTrue(out.issues.isEmpty())
        c.references.resolve(raw, "Document_Test")
        assertEquals(2, t.calls.size)
    }

    @Test
    fun journalSortsMixedTypesAndRetainsStateOnFailure() {
        val r = registry()
        var fail = true
        val t = FakeTransport { url, _, _ ->
            when {
                url.contains(encoded("Document_РеализацияТоваровУслуг")) ->
                    success(
                        listOf(
                            mapOf("Ref_Key" to A, "Date" to "2025-01-02T00:00:00", "Number" to "2")
                        )
                    )
                fail -> {
                    fail = false
                    throw AppError("offline")
                }
                else ->
                    success(
                        listOf(
                            mapOf("Ref_Key" to B, "Date" to "2025-01-01T00:00:00", "Number" to "1")
                        )
                    )
            }
        }
        val j =
            JournalReader(
                ODataClient(profile(), r, t),
                listOf("Реализация товаров и услуг", "Приходный кассовый ордер"),
            )
        val p = Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
        assertThrows(AppError::class.java) { j.page(p) }
        assertEquals(listOf("2", "1"), j.page(p).map { it["Number"] })
        assertTrue(j.page(p).isEmpty())
    }
}

val metadata =
    """<edmx:Edmx xmlns:edmx="urn:edmx"><edmx:DataServices><Schema xmlns="urn:edm" Namespace="S" Alias="A">
<EntityType Name="Catalog_Номенклатура"><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="Description" Type="Edm.String"/></EntityType>
<EntityType Name="Document_Test"><Property Name="Товары" Type="Collection(A.Document_Test_Товары_RowType)"/></EntityType>
<ComplexType Name="Document_Test_Товары_RowType"><Property Name="Номенклатура_Key" Type="Edm.Guid"/></ComplexType>
<EntityType Name="Document_Test_Товары"><Property Name="Номенклатура_Key" Type="Edm.Guid"/><NavigationProperty Name="Номенклатура" Relationship="A.Link" ToRole="End"/></EntityType>
<Association Name="Link"><End Role="Begin" Type="A.Document_Test_Товары"/><End Role="End" Type="A.Catalog_Номенклатура"/></Association>
<EntityContainer Name="Data"><EntitySet Name="Catalog_Номенклатура" EntityType="A.Catalog_Номенклатура"/><EntitySet Name="Document_Test" EntityType="A.Document_Test"/></EntityContainer>
</Schema></edmx:DataServices></edmx:Edmx>"""
