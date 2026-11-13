package kg.accounting.mobile.core

import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

const val ZERO = "00000000-0000-0000-0000-000000000000"
val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun encoded(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("'", "%27")

class AppError(message: String, val status: Int? = null, val details: String? = null) :
    Exception(message)

class CreationUncertain(val locator: Locator) :
    Exception(
        "Ответ не получен. Запись могла сохраниться в 1С. Проверьте результат; повторная отправка заблокирована."
    )

// Не data class: стандартный toString не должен раскрывать пароль в журнале.
class Profile private constructor(val url: String, val username: String, val password: String) {
    fun json(): Row =
        mapOf(
            "type" to "accounting_1c_connection",
            "version" to 1,
            "odataUrl" to url,
            "username" to username,
            "password" to password,
        )

    companion object {
        fun create(address: String, user: String, password: String): Profile {
            val url =
                address.trim().let { if (it.endsWith("/odata/standard.odata")) "$it/" else it }
            val uri = runCatching { URI(url) }.getOrNull()
            if (
                uri == null ||
                    uri.scheme !in listOf("http", "https") ||
                    uri.host.isNullOrEmpty() ||
                    uri.userInfo != null ||
                    uri.rawQuery != null ||
                    uri.rawFragment != null ||
                    !uri.path.endsWith("/odata/standard.odata/")
            )
                throw AppError("Укажите адрес, оканчивающийся на /odata/standard.odata/")
            val login = user.trim()
            if (login.isEmpty() || ':' in login || password.isEmpty())
                throw AppError("Введите логин без двоеточия и пароль 1С.")
            if (url.length > 2048 || login.length > 256 || password.length > 4096)
                throw AppError("Данные подключения слишком длинные.")
            return Profile(url, login, password)
        }

        fun fromCode(code: String): Profile {
            if (code.length > 16384) throw AppError("QR-код слишком большой.")
            val row =
                runCatching { Json.objectValue(code.trim().removePrefix("\uFEFF")) }
                    .getOrElse { throw AppError("В QR-коде нет настроек подключения к 1С.") }
            if (
                (row["type"] != null && row["type"] != "accounting_1c_connection") ||
                    (row["version"] != null && row["version"] != 1) ||
                    listOf("odataUrl", "username", "password").any { row[it] !is String }
            )
                throw AppError("Нужны поля odataUrl, username и password (версия 1).")
            return create(
                row["odataUrl"] as String,
                row["username"] as String,
                row["password"] as String,
            )
        }
    }
}

data class Period(val from: LocalDate, val through: LocalDate) {
    init {
        if (from > through || from.year < 1 || through.year >= 9999)
            throw AppError("Дата начала периода не может быть позже окончания.")
    }

    fun filter(field: String): String {
        require(field in listOf("Date", "Period", "RegistrationPeriod"))
        fun after(d: LocalDate) =
            "(year($field) gt ${d.year} or (year($field) eq ${d.year} and (month($field) gt ${d.monthValue} or (month($field) eq ${d.monthValue} and day($field) ge ${d.dayOfMonth}))))"

        fun before(d: LocalDate) =
            "(year($field) lt ${d.year} or (year($field) eq ${d.year} and (month($field) lt ${d.monthValue} or (month($field) eq ${d.monthValue} and day($field) le ${d.dayOfMonth}))))"
        return if (from.year == through.year && from.month == through.month)
            "year($field) eq ${from.year} and month($field) eq ${from.monthValue} and day($field) ge ${from.dayOfMonth} and day($field) le ${through.dayOfMonth}"
        else
            "${
            after(
                from
            )
        } and ${before(through)}"
    }
}

data class Resource(
    val title: String,
    val collection: String,
    val kind: String,
    val dateField: String?,
    val hierarchical: Boolean,
    val orderBy: String,
    val keyTypes: Map<String, String>,
)

data class MenuNode(
    val id: String,
    val title: String,
    val kind: String,
    val children: List<MenuNode> = emptyList(),
    val sources: List<String> = emptyList(),
    val explanation: String = "",
) {
    fun matches(q: String): Boolean =
        title.lowercase(Locale.ROOT).contains(q) || children.any { it.matches(q) }
}

class Registry(text: String) {
    private val raw = Json.objectValue(text)
    val resources =
        raw["resources"]
            .rows()
            .associate { r ->
                r["title"].text() to
                    Resource(
                        r["title"].text(),
                        r["collection"].text(),
                        r["kind"].text(),
                        r["dateField"] as? String,
                        r["hierarchical"] == true,
                        r["orderBy"].text(),
                        r["keyTypes"].row().mapValues { it.value.text() },
                    )
            }
            .toMutableMap()

    private fun node(r: Row): MenuNode =
        MenuNode(
            r["id"].text(),
            r["title"].text(),
            r["kind"].text(),
            r["children"].rows().map(::node),
            r["sources"].strings(),
            r["explanation"].text(),
        )

    val menu = raw["menu"].rows().map(::node)

    fun nodes(): List<MenuNode> {
        fun walk(n: MenuNode): List<MenuNode> = listOf(n) + n.children.flatMap(::walk)
        return menu.flatMap(::walk)
    }

    init {
        resources["Классификатор единиц измерения"] =
            Resource(
                "Классификатор единиц измерения",
                "Catalog_КлассификаторЕдиницИзмерения",
                "catalog",
                null,
                false,
                "Description,Ref_Key",
                mapOf("Ref_Key" to "Edm.Guid"),
            )
        resources["ВЭД"] =
            Resource(
                "ВЭД",
                "Catalog_ВЭД",
                "catalog",
                null,
                false,
                "Description,Ref_Key",
                mapOf("Ref_Key" to "Edm.Guid"),
            )
    }
}

data class Locator(val resource: String, val collection: String, val title: String, val keys: Row) {
    val id: String
        get() = Json.encode(listOf(collection, keys.toSortedMap()))

    fun json(): Row =
        mapOf(
            "resource" to resource,
            "collection" to collection,
            "title" to title.take(200),
            "keys" to keys,
        )

    companion object {
        fun fromRow(spec: Resource, row: Row, title: String = recordTitle(row)): Locator? {
            val keys = spec.keyTypes.mapValues { row[it.key] }
            if (
                keys.isEmpty() ||
                    keys.any { (name, v) ->
                        v == null ||
                            (v !is String && v !is Number && v !is Boolean) ||
                            (spec.keyTypes[name] == "Edm.Guid" && !GUID.matches(v.text()))
                    }
            )
                return null
            return Locator(spec.title, spec.collection, title.take(200), keys)
        }

        fun decode(r: Row, registry: Registry): Locator? {
            val spec = registry.resources[r["resource"]] ?: return null
            if (spec.collection != r["collection"]) return null
            return fromRow(spec, r["keys"].row(), r["title"].text())
        }
    }
}

fun recordTitle(row: Row): String =
    row["Description"].text().ifEmpty {
        row["Number"].text().let {
            if (it.isEmpty()) row["Code"].text().ifEmpty { "Запись" } else "№ $it"
        }
    }

fun calendarTime(value: Any?): LocalDateTime? =
    runCatching { LocalDateTime.parse(value.text().removeSuffix("Z")) }.getOrNull()

fun dateText(value: Any?): String =
    calendarTime(value)?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
        ?: value.text()

fun display(value: Any?): String =
    when (value) {
        null -> "Не передано 1С"
        true -> "Да"
        false -> "Нет"
        ZERO,
        "" -> "Не указано"
        else -> value.toString().trim()
    }
