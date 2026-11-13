package kg.accounting.mobile.data

import kg.accounting.mobile.core.*

data class Resolved(val value: Any?, val issues: List<String>)

class ReferenceResolver(private val client: ODataClient) {
    private var metadata: MetadataModel? = null
    private val cache =
        object : LinkedHashMap<String, Pair<Long, String>>(64, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?
            ) = size > 2000
        }

    @Synchronized
    fun invalidate() {
        metadata = null
        cache.clear()
    }

    @Synchronized
    fun seed(collection: String, rows: List<Row>) {
        rows.forEach { r ->
            val key = r["Ref_Key"].text()
            if (GUID.matches(key))
                label(r)?.let {
                    cache["$collection:${key.lowercase()}"] = System.currentTimeMillis() to it
                }
        }
    }

    private fun label(row: Row): String? {
        listOf("Description", "Наименование", "НаименованиеПолное", "Presentation").forEach {
            if (row[it].text().isNotEmpty()) return row[it].text()
        }
        if (row["Number"].text().isNotEmpty())
            return "№ ${row["Number"].text()} · ${dateText(row["Date"])}"
        return row["Code"].text().takeIf { it.isNotEmpty() }?.let { "Код: $it" }
    }

    fun resolve(value: Any?, collection: String): Resolved {
        val issues = linkedSetOf<String>()
        val model =
            runCatching {
                    metadata
                        ?: MetadataModel(client.text("\$metadata", xml = true)).also {
                            metadata = it
                        }
                }
                .getOrElse {
                    issues += "Не удалось загрузить описание OData. Проверьте подключение и права."
                    null
                }
        val local = mutableMapOf<String, String>()
        fun reference(type: String, key: String, raw: Any?, row: Row): Any? {
            if (raw == null || raw == "" || raw == ZERO) return "Не указано"
            if (raw !is String || !GUID.matches(raw)) return raw
            val name = key.removeSuffix("_Key")
            val explicit = row["${name}_Type"].text()
            val target =
                if (explicit.isNotEmpty()) model?.canonical(explicit)
                else model?.reference(type, name)
            val set = target?.let { model?.setFor(it) }
            if (set == null) {
                val error =
                    if (target != null) "Тип ссылки не опубликован в OData"
                    else "Не удалось определить тип ссылки"
                issues += "$name: $error"
                return error
            }
            row["${name}____Presentation"]
                .text()
                .takeIf { it.isNotEmpty() }
                ?.let {
                    return it
                }
            val cacheKey = "$set:${raw.lowercase()}"
            return local.getOrPut(cacheKey) {
                val cached = synchronized(this) { cache[cacheKey] }
                if (cached != null && System.currentTimeMillis() - cached.first < 300000)
                    cached.second
                else
                    try {
                        val props = model!!.properties[model.sets[set]].orEmpty()
                        val select =
                            listOf(
                                    "Ref_Key",
                                    "Description",
                                    "Наименование",
                                    "НаименованиеПолное",
                                    "Number",
                                    "Date",
                                    "Code",
                                )
                                .filter { props.containsKey(it) }
                        val params =
                            mutableMapOf(
                                "\$format" to "json",
                                "\$top" to "1",
                                "\$filter" to "Ref_Key eq guid'$raw'",
                            )
                        if (select.isNotEmpty()) params["\$select"] = select.joinToString(",")
                        val row =
                            client.read(set, params).firstOrNull()
                                ?: throw AppError("Объект не найден или недоступен")
                        val text = label(row) ?: throw AppError("У объекта нет представления")
                        synchronized(this) { cache[cacheKey] = System.currentTimeMillis() to text }
                        text
                    } catch (e: Exception) {
                        val message =
                            (e as? AppError)?.message ?: "Не удалось загрузить связанные данные"
                        issues += "$name: $message"
                        message
                    }
            }
        }

        fun walk(node: Any?, type: String, depth: Int): Any? {
            if (Thread.currentThread().isInterrupted) throw AppError("Загрузка отменена.")
            if (depth > 40) return node
            if (node is List<*>) return node.map { walk(it, type, depth + 1) }
            if (node !is Map<*, *>) return node
            val row = node.row()
            return row.mapValues { (key, child) ->
                if (key != "Ref_Key" && (key.endsWith("_Key") || row.containsKey("${key}_Type")))
                    reference(type, key, child, row)
                else if (child is Map<*, *> || child is List<*>)
                    walk(
                        child,
                        model?.property(type, key) ?: model?.target(type, key).orEmpty(),
                        depth + 1,
                    )
                else child
            }
        }
        return Resolved(walk(value, model?.sets?.get(collection).orEmpty(), 0), issues.toList())
    }
}
