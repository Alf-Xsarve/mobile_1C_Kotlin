package kg.accounting.mobile.core

import java.security.MessageDigest
import kg.accounting.mobile.data.KeyValueStore

data class ListMemory(
    var query: String = "",
    var first: Int = 0,
    var offset: Int = 0,
    var loaded: Int = 0,
    var from: String = "",
    var through: String = "",
) {
    fun json(): Row =
        mapOf(
            "query" to query.take(256),
            "first" to first,
            "offset" to offset,
            "loaded" to loaded,
            "from" to from,
            "through" to through,
        )

    companion object {
        fun decode(r: Row) =
            ListMemory(
                r["query"].text().take(256),
                (r["first"] as? Number)?.toInt()?.coerceIn(0, 10000) ?: 0,
                (r["offset"] as? Number)?.toInt()?.coerceIn(-100000, 0) ?: 0,
                (r["loaded"] as? Number)?.toInt()?.coerceIn(0, 10000) ?: 0,
                r["from"].text(),
                r["through"].text(),
            )
    }
}

data class Shortcut(val nodeId: String? = null, val locator: Locator? = null) {
    val id: String
        get() = locator?.let { "record:${it.id}" } ?: "menu:$nodeId"

    fun json(): Row =
        if (locator != null) mapOf("record" to locator.json()) else mapOf("menu" to nodeId)

    fun title(registry: Registry) =
        locator?.title ?: registry.nodes().find { it.id == nodeId }?.title.orEmpty()

    fun subtitle() = locator?.resource ?: "Раздел"
}

class UserPreferences(
    private val store: KeyValueStore,
    private val registry: Registry,
    profile: Profile,
) {
    val key =
        "workspace_" +
            MessageDigest.getInstance("SHA-256")
                .digest(Json.encode(listOf(profile.url, profile.username)).toByteArray())
                .joinToString("") { "%02x".format(it) }
    val favorites = mutableListOf<Shortcut>()
    val recent = mutableListOf<Locator>()
    private val views = linkedMapOf<String, ListMemory>()
    private val folders = mutableMapOf<String, Selection>()
    private val hidden = mutableMapOf<String, Set<String>>()
    var rememberViews = true
    var saveHistory = true

    fun load() {
        val text = store.read(key) ?: return
        if (text.length > 512 * 1024) throw AppError("Настройки слишком большие.")
        val r = Json.objectValue(text)
        require(r["version"] == 1)
        val known =
            registry
                .nodes()
                .filter { it.kind in listOf("resource", "journal") }
                .map { it.id }
                .toSet()
        favorites +=
            r["favorites"]
                .rows()
                .take(40)
                .mapNotNull { v ->
                    Locator.decode(v["record"].row(), registry)?.let { Shortcut(locator = it) }
                        ?: v["menu"].text().takeIf { it in known }?.let { Shortcut(nodeId = it) }
                }
                .distinctBy { it.id }
        recent +=
            r["recent"]
                .rows()
                .take(30)
                .mapNotNull { Locator.decode(it, registry) }
                .distinctBy { it.id }
        r["views"].row().entries.take(100).forEach { (k, v) ->
            views[k] = ListMemory.decode(v.row())
        }
        r["folders"].row().entries.take(100).forEach { (k, v) ->
            val f = v.row()
            val id = f["key"].text()
            if (GUID.matches(id))
                folders[k] =
                    Selection(id, f["title"].text().take(200), f["path"].strings().take(30))
        }
        r["hidden"].row().entries.take(150).forEach { (k, v) ->
            hidden[k] = v.strings().take(200).toSet()
        }
        rememberViews = r["rememberViews"] != false
        saveHistory = r["saveHistory"] != false
    }

    fun save() {
        store.write(
            key,
            Json.encode(
                mapOf(
                    "version" to 1,
                    "favorites" to favorites.map { it.json() },
                    "recent" to recent.map { it.json() },
                    "views" to views.mapValues { it.value.json() },
                    "folders" to
                        folders.mapValues {
                            mapOf(
                                "key" to it.value.key,
                                "title" to it.value.title,
                                "path" to it.value.path,
                            )
                        },
                    "hidden" to hidden.mapValues { it.value.toList() },
                    "rememberViews" to rememberViews,
                    "saveHistory" to saveHistory,
                )
            ),
        )
    }

    fun favorite(shortcut: Shortcut) = favorites.any { it.id == shortcut.id }

    fun toggle(shortcut: Shortcut) {
        if (!favorites.removeAll { it.id == shortcut.id }) {
            if (favorites.size >= 40) throw AppError("В избранном уже 40 пунктов.")
            favorites += shortcut
        }
        save()
    }

    fun remember(locator: Locator) {
        if (!saveHistory) return
        recent.removeAll { it.id == locator.id }
        recent.add(0, locator)
        while (recent.size > 30) recent.removeAt(recent.lastIndex)
        save()
    }

    fun view(resource: String, parent: String): ListMemory =
        if (rememberViews) views["$resource|$parent"]?.copy() ?: ListMemory() else ListMemory()

    fun remember(resource: String, parent: String, value: ListMemory) {
        if (!rememberViews) return
        views.remove("$resource|$parent")
        views["$resource|$parent"] = value.copy()
        while (views.size > 100) views.remove(views.keys.first())
    }

    fun folder(resource: String) =
        if (rememberViews) folders[resource] ?: Selection() else Selection()

    fun folder(resource: String, value: Selection) {
        if (rememberViews) folders[resource] = value
    }

    fun hidden(path: String) = hidden[path].orEmpty()

    fun hidden(path: String, keys: Set<String>) {
        hidden[path] = keys
        if (hidden.size > 150) hidden.remove(hidden.keys.first())
        save()
    }

    fun clearViews() {
        views.clear()
        folders.clear()
        save()
    }

    fun clearHistory() {
        recent.clear()
        save()
    }
}
