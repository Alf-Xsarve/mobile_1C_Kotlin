package kg.accounting.mobile.core

import org.json.JSONArray
import org.json.JSONObject

typealias Row = Map<String, Any?>

object Json {
    fun parse(text: String): Any? =
        unwrap(org.json.JSONTokener(text.removePrefix("\uFEFF")).nextValue())

    fun objectValue(text: String): Row {
        val value = parse(text)
        require(value is Map<*, *>) { "Ожидался JSON-объект" }
        return value.row()
    }

    fun encode(value: Any?, pretty: Boolean = false): String =
        when (value) {
            is Map<*, *> -> JSONObject(value).let { if (pretty) it.toString(2) else it.toString() }
            is List<*> -> JSONArray(value).let { if (pretty) it.toString(2) else it.toString() }
            else -> JSONArray(listOf(value)).toString().removeSurrounding("[", "]")
        }

    private fun unwrap(v: Any?): Any? =
        when (v) {
            JSONObject.NULL,
            null -> null
            is JSONObject -> v.keys().asSequence().associateWith { unwrap(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            else -> v
        }
}

fun Any?.text(): String = this?.toString()?.trim().orEmpty()

fun Any?.row(): Row =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

fun Any?.rows(): List<Row> = (this as? List<*>)?.map { it.row() }.orEmpty()

fun Any?.strings(): List<String> = (this as? List<*>)?.filterIsInstance<String>().orEmpty()
