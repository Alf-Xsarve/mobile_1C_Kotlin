package kg.accounting.mobile.ui

import kg.accounting.mobile.core.*
import kg.accounting.mobile.data.JournalReader

sealed class Screen

object HomeScreen : Screen()

object SettingsScreen : Screen()

data class ShortcutsScreen(val history: Boolean, var query: String = "") : Screen()

data class RecordsScreen(
    val node: MenuNode,
    val folder: Selection = Selection(),
    val memory: ListMemory = ListMemory(),
    val picker: ((Selection) -> Unit)? = null,
    val foldersOnly: Boolean = false,
) : Screen() {
    val rows = mutableListOf<Row>()

    var loading = false

    var loaded = false

    var more = true

    var error: String? = null

    var generation = 0

    var journal: JournalReader? = null
}

data class CardScreen(
    val title: String,
    val raw: Any?,
    val collection: String?,
    val locator: Locator? = null,
    val path: String = "card:$collection",
    var value: Any? = null,
    var resolved: Boolean = false,
    var loading: Boolean = false,
    var error: String? = null,
    var issues: List<String> = emptyList(),
    var query: String = "",
) : Screen()

data class SourceScreen(val title: String, val raw: Any?, var query: String = "") : Screen()

data class CreateScreen(val draft: CatalogDraft) : Screen() {
    var loading = false

    var error: String? = null

    var uncertain: Locator? = null
}
