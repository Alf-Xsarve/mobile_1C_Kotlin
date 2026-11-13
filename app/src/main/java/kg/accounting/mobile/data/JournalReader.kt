package kg.accounting.mobile.data

import kg.accounting.mobile.core.*

// Буферы фиксируются только после успешного получения всей страницы журнала.
class JournalReader(private val client: ODataClient, private val sources: List<String>) {
    private data class Cursor(
        var skip: Int = 0,
        var done: Boolean = false,
        val buffer: MutableList<Row> = mutableListOf(),
    ) {
        fun copyState() = Cursor(skip, done, buffer.toMutableList())
    }

    private var current: Period? = null
    private var cursors = sources.associateWith { Cursor() }

    fun page(period: Period, reset: Boolean = false): List<Row> {
        val states =
            if (reset || current != period) sources.associateWith { Cursor() }
            else cursors.mapValues { it.value.copyState() }
        val result = mutableListOf<Row>()
        repeat(50) {
            states.forEach { (name, c) ->
                if (c.buffer.isEmpty() && !c.done) {
                    val rows = client.page(name, c.skip, 50, period)
                    c.skip += rows.size
                    c.done = rows.size < 50
                    c.buffer += rows
                }
            }
            val next =
                states
                    .filterValues { it.buffer.isNotEmpty() }
                    .maxWithOrNull(
                        compareBy<Map.Entry<String, Cursor>> {
                                it.value.buffer.first()["Date"].text()
                            }
                            .thenBy { it.value.buffer.first()["Ref_Key"].text() }
                            .thenBy { it.key }
                    )
            if (next == null) {
                cursors = states
                current = period
                return result
            }
            result += next.value.buffer.removeAt(0) + mapOf("__resource" to next.key)
        }
        cursors = states
        current = period
        return result
    }
}
