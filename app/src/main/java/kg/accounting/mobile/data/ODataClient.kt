package kg.accounting.mobile.data

import java.util.UUID
import kg.accounting.mobile.core.*

class ODataClient(
    val profile: Profile,
    val registry: Registry,
    private val transport: Transport = UrlTransport(profile),
) : AutoCloseable {
    val references = ReferenceResolver(this)

    fun url(collection: String, parameters: Map<String, String>): String {
        require(Regex("^[\\p{L}_][\\p{L}\\p{N}_]*$|^\\\$metadata$").matches(collection))
        val path = collection.split('/').joinToString("/") { encoded(it) }
        return profile.url +
            path +
            if (parameters.isEmpty()) ""
            else parameters.entries.joinToString("&", "?") { "${it.key}=${encoded(it.value)}" }
    }

    fun text(
        collection: String,
        parameters: Map<String, String> = emptyMap(),
        xml: Boolean = false,
    ): String = transport.request(url(collection, parameters), xml = xml).checked()

    fun read(collection: String, parameters: Map<String, String>): List<Row> {
        val value =
            try {
                Json.objectValue(text(collection, parameters))["value"]
            } catch (e: AppError) {
                throw e
            } catch (_: Exception) {
                throw AppError("Сервер вернул некорректный JSON.")
            }
        if (value !is List<*> || value.any { it !is Map<*, *> })
            throw AppError("Ожидался список OData в поле value.")
        return value.map { it.row() }.also { references.seed(collection, it) }
    }

    fun page(
        resource: String,
        skip: Int = 0,
        top: Int = 50,
        period: Period? = null,
        parent: String? = null,
        foldersOnly: Boolean = false,
    ): List<Row> {
        val spec = registry.resources[resource] ?: throw AppError("Объект не настроен.")
        val filters = mutableListOf<String>()
        if (spec.dateField != null)
            filters += (period ?: throw AppError("Выберите период.")).filter(spec.dateField)
        if (spec.hierarchical) {
            val key = parent ?: ZERO
            if (!GUID.matches(key)) throw AppError("Некорректный ключ папки.")
            filters += "Parent_Key eq guid'$key'"
        }
        if (foldersOnly) filters += "IsFolder eq true"
        val p =
            linkedMapOf(
                "\$format" to "json",
                "\$top" to top.coerceIn(1, 50).toString(),
                "\$skip" to skip.coerceAtLeast(0).toString(),
                "\$orderby" to spec.orderBy,
            )
        if (filters.isNotEmpty()) p["\$filter"] = filters.joinToString(" and ") { "($it)" }
        return read(spec.collection, p)
    }

    fun readRecord(locator: Locator): Row? {
        val spec = registry.resources[locator.resource] ?: throw AppError("Неизвестный тип записи.")
        if (spec.collection != locator.collection || spec.keyTypes.keys != locator.keys.keys)
            throw AppError("Сохранённая ссылка не соответствует типу объекта.")
        val filters =
            spec.keyTypes.map { (key, type) ->
                val v = locator.keys[key]
                if (type == "Edm.DateTime") {
                    val d = calendarTime(v) ?: throw AppError("Некорректная дата ключа.")
                    "year($key) eq ${d.year} and month($key) eq ${d.monthValue} and day($key) eq ${d.dayOfMonth}"
                } else "$key eq ${literal(type, v)}"
            }
        val dated = spec.keyTypes.containsValue("Edm.DateTime")
        val top = if (dated) 50 else 2
        for (skip in 0 until (if (dated) 2000 else 2) step 50) {
            val rows =
                read(
                    spec.collection,
                    mapOf(
                        "\$format" to "json",
                        "\$top" to "$top",
                        "\$skip" to "$skip",
                        "\$filter" to filters.joinToString(" and ") { "($it)" },
                        "\$orderby" to spec.orderBy,
                    ),
                )
            val matches =
                rows.filter { row ->
                    spec.keyTypes.all { (k, t) ->
                        when (t) {
                            "Edm.Guid" -> row[k].text().equals(locator.keys[k].text(), true)
                            "Edm.DateTime" -> calendarTime(row[k]) == calendarTime(locator.keys[k])
                            else -> row[k] == locator.keys[k]
                        }
                    }
                }
            if (matches.size > 1) throw AppError("Ключ неоднозначен. Откройте запись из списка.")
            if (matches.size == 1) return matches.single()
            if (rows.size < top) return null
        }
        throw AppError("Слишком много записей. Откройте нужную из списка.")
    }

    private fun literal(type: String, v: Any?): String =
        when {
            type == "Edm.Guid" && GUID.matches(v.text()) -> "guid'${v.text()}'"
            type == "Edm.String" && v is String && v.length <= 2048 -> "'${v.replace("'", "''")}'"
            type == "Edm.Boolean" && v is Boolean -> "$v"
            type in listOf("Edm.Int64", "Edm.Int32", "Edm.Int16", "Edm.Double", "Edm.Decimal") &&
                v is Number &&
                v.toDouble().isFinite() -> v.toString()

            else -> throw AppError("Некорректный ключ записи.")
        }

    fun create(resource: String, fields: Row): Row {
        if (resource !in listOf("Номенклатура", "Контрагенты", "Склады"))
            throw AppError("Создание этого типа не поддерживается.")
        val spec = registry.resources.getValue(resource)
        val payload = fields + mapOf("Ref_Key" to UUID.randomUUID().toString())
        val locator = Locator.fromRow(spec, payload)!!
        val response =
            try {
                transport.request(url(spec.collection, emptyMap()), "POST", Json.encode(payload))
            } catch (_: Exception) {
                throw CreationUncertain(locator)
            }
        if (response.status >= 500 || response.status in listOf(408, 504))
            throw CreationUncertain(locator)
        response.checked()
        references.invalidate()
        return if (response.body.isBlank()) payload
        else runCatching { payload + Json.objectValue(response.body) }.getOrDefault(payload)
    }

    override fun close() {
        transport.close()
        references.invalidate()
    }
}
