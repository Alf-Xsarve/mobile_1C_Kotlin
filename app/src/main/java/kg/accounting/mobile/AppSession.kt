package kg.accounting.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.Executors
import kg.accounting.mobile.core.*
import kg.accounting.mobile.data.*
import kg.accounting.mobile.ui.*

// Состояние сеанса переживает поворот экрана. UI-подписка принадлежит Activity.
class AppSession(context: Context) {
    val registry =
        Registry(context.assets.open("schema.json").bufferedReader().use { it.readText() })
    private val store = SecureStore(context.applicationContext)
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    var render: (() -> Unit)? = null
    var profile: Profile? = null
        private set

    var client: ODataClient? = null
        private set

    var preferences: UserPreferences? = null
        private set

    var theme = "system"

    var scale = 1f
    var restoring = true

    var restoreFailed = false

    var signingIn = false

    var error: String? = null
    var manual = false

    var address = ""

    var username = ""

    var password = ""
    val stack = mutableListOf<Screen>(HomeScreen)
    @Volatile private var epoch = 0
    @Volatile private var closed = false
    private val save = Runnable { persist() }
    val screen: Screen
        get() = stack.last()

    init {
        background(
            {
                val appearance = store.read("appearance")?.let(Json::objectValue).orEmpty()
                val saved = store.read("connection")?.let(Profile::fromCode)
                val prefs =
                    saved?.let {
                        UserPreferences(store, registry, it).also { p ->
                            runCatching { p.load() }
                                .onFailure { error = "Не удалось прочитать настройки этой базы." }
                        }
                    }
                Triple(appearance, saved, prefs)
            },
            { (appearance, saved, prefs) ->
                theme = appearance["theme"].text().ifEmpty { "system" }
                scale = ((appearance["scale"] as? Number)?.toFloat() ?: 1f).coerceIn(.85f, 1.45f)
                profile = saved
                client = saved?.let { ODataClient(it, registry) }
                preferences = prefs
                restoring = false
            },
            {
                restoring = false
                restoreFailed = true
                error =
                    "Не удалось прочитать защищённое хранилище. Повторите запуск или сбросьте подключение."
            },
        )
    }

    fun notifyUi() {
        if (!closed) render?.invoke()
    }

    fun <T> background(
        task: () -> T,
        done: (T) -> Unit,
        failed: (Exception) -> Unit = { error = message(it) },
    ) {
        val generation = epoch
        io.execute {
            if (closed || generation != epoch) return@execute
            val result = runCatching { task() }
            main.post {
                if (!closed && generation == epoch) {
                    result.fold(done, { failed(it as? Exception ?: Exception(it)) })
                    notifyUi()
                }
            }
        }
    }

    fun message(e: Exception): String =
        when (e) {
            is AppError -> listOfNotNull(e.message, e.details).joinToString("\n\n")
            is CreationUncertain -> e.message.orEmpty()
            is java.net.SocketTimeoutException -> "Сервер не ответил вовремя."
            is java.net.ConnectException,
            is java.net.UnknownHostException -> "Сервер недоступен. Проверьте сеть и адрес OData."
            is javax.net.ssl.SSLException -> "Не удалось проверить HTTPS-соединение."
            else -> "Не удалось выполнить действие. Проверьте подключение и повторите попытку."
        }

    fun connect(p: Profile) {
        if (signingIn) return
        signingIn = true
        error = null
        notifyUi()
        background(
            {
                val c = ODataClient(p, registry)
                try {
                    c.page("Номенклатура", top = 1)
                    store.write("connection", Json.encode(p.json()))
                    val prefs = UserPreferences(store, registry, p)
                    runCatching { prefs.load() }
                    c to prefs
                } catch (e: Exception) {
                    c.close()
                    throw e
                }
            },
            { (c, prefs) ->
                client?.close()
                profile = p
                client = c
                preferences = prefs
                password = ""
                signingIn = false
                stack.clear()
                stack += HomeScreen
            },
            {
                signingIn = false
                error = message(it)
            },
        )
    }

    fun logout() {
        if (signingIn) return
        signingIn = true
        notifyUi()
        background(
            { store.write("connection", null) },
            {
                client?.close()
                client = null
                profile = null
                preferences = null
                password = ""
                manual = false
                error = null
                restoreFailed = false
                stack.clear()
                stack += HomeScreen
                signingIn = false
                epoch++
            },
            {
                signingIn = false
                error = "Не удалось удалить подключение с телефона."
            },
        )
    }

    fun open(node: MenuNode) {
        if (node.kind == "unavailable") {
            error = node.explanation
            notifyUi()
            return
        }
        error = null
        val folder =
            if (registry.resources[node.title]?.hierarchical == true)
                preferences?.folder(node.title) ?: Selection()
            else Selection()
        val memory = preferences?.view(node.title, folder.key) ?: ListMemory()
        val s = RecordsScreen(node, folder, memory)
        if (node.kind == "journal") s.journal = JournalReader(client!!, node.sources)
        push(s)
        load(s)
    }

    fun push(screen: Screen) {
        stack += screen
        notifyUi()
    }

    fun back(): Boolean {
        if (stack.size == 1) return false
        if ((screen as? CreateScreen)?.loading == true) return true
        remember()
        stack.removeAt(stack.lastIndex)
        (screen as? RecordsScreen)?.let { rememberFolder(it) }
        notifyUi()
        (screen as? RecordsScreen)?.let {
            if (!it.loaded && !it.loading && it.error == null) load(it)
        }
        return true
    }

    fun home() {
        if ((screen as? CreateScreen)?.loading == true) return
        remember()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        notifyUi()
    }

    private fun rememberFolder(s: RecordsScreen) {
        if (s.picker == null && registry.resources[s.node.title]?.hierarchical == true)
            preferences?.folder(s.node.title, s.folder)
        scheduleSave()
    }

    fun remember() {
        (screen as? RecordsScreen)?.let {
            if (it.picker == null) preferences?.remember(it.node.title, it.folder.key, it.memory)
        }
        scheduleSave()
    }

    fun scheduleSave() {
        main.removeCallbacks(save)
        main.postDelayed(save, 400)
    }

    fun persist() {
        main.removeCallbacks(save)
        runCatching { preferences?.save() }
            .onFailure {
                error =
                    "Не удалось сохранить настройки на телефоне. Изменения пока доступны только в этом сеансе."
            }
    }

    fun load(s: RecordsScreen, reset: Boolean = false) {
        val active = client ?: return
        if (s.loading && !reset) return
        val spec = registry.resources[s.node.title]
        val usesPeriod = s.node.kind == "journal" || spec?.dateField != null
        val period =
            if (usesPeriod)
                runCatching {
                        Period(LocalDate.parse(s.memory.from), LocalDate.parse(s.memory.through))
                    }
                    .getOrElse {
                        s.error = "Укажите корректные даты периода."
                        notifyUi()
                        return
                    }
            else null
        if (reset) {
            s.generation++
            s.rows.clear()
            s.more = true
            s.loaded = false
            s.memory.first = 0
            s.memory.offset = 0
            client?.references?.invalidate()
        }
        s.loading = true
        s.error = null
        val generation = s.generation
        val restoreCount = if (!s.loaded) s.memory.loaded else 0
        rememberFolder(s)
        notifyUi()
        background(
            {
                val result = mutableListOf<Row>()
                var count = s.rows.size
                var more = true
                do {
                    val rows =
                        s.journal?.page(period!!, reset && count == 0)
                            ?: active.page(
                                s.node.title,
                                count,
                                50,
                                period,
                                s.folder.key,
                                s.foldersOnly,
                            )
                    result += rows
                    count += rows.size
                    more = rows.size == 50
                } while (more && count < restoreCount && count < 10000)
                result to more
            },
            { (rows, more) ->
                if (generation == s.generation) {
                    s.rows += rows
                    s.more = more
                    s.loaded = true
                    s.loading = false
                    s.memory.loaded = s.rows.size
                    if (screen === s) {
                        rememberFolder(s)
                        remember()
                    }
                }
            },
            {
                if (generation == s.generation) {
                    s.loading = false
                    s.error = message(it)
                }
            },
        )
    }

    fun folder(s: RecordsScreen, row: Row) {
        val key = row["Ref_Key"].text()
        if (!GUID.matches(key) || key == s.folder.key) {
            error = "Некорректный ключ папки."
            notifyUi()
            return
        }
        remember()
        val folder = Selection(key, recordTitle(row), s.folder.path + recordTitle(row))
        val child =
            RecordsScreen(
                s.node,
                folder,
                preferences?.view(s.node.title, key) ?: ListMemory(),
                s.picker,
                s.foldersOnly,
            )
        push(child)
        load(child)
    }

    fun card(resource: String, row: Row) {
        val clean = row.filterKeys { !it.startsWith("__") }
        val spec = registry.resources.getValue(resource)
        val locator = Locator.fromRow(spec, clean)
        val s = CardScreen(recordTitle(clean), clean, spec.collection, locator)
        push(s)
        resolve(s)
        locator?.let { safePreference { it.remember(locator) } }
    }

    fun resolve(s: CardScreen) {
        val active = client ?: return
        if (s.collection == null) return
        s.loading = true
        notifyUi()
        background(
            { active.references.resolve(s.raw, s.collection) },
            {
                s.value = it.value
                s.issues = it.issues
                s.resolved = true
                s.loading = false
            },
            {
                s.error = message(it)
                s.loading = false
            },
        )
    }

    fun shortcut(value: Shortcut) {
        if (value.nodeId != null) {
            registry.nodes().find { it.id == value.nodeId }?.let(::open)
            return
        }
        val locator = value.locator ?: return
        val active = client ?: return
        error = null
        restoring = true
        notifyUi()
        background(
            { active.readRecord(locator) },
            { row ->
                restoring = false
                if (row == null) {
                    error = "Запись удалена или недоступна текущему пользователю."
                } else card(locator.resource, row)
            },
            {
                restoring = false
                error = message(it)
            },
        )
    }

    fun safePreference(action: (UserPreferences) -> Unit) {
        try {
            preferences?.let(action)
        } catch (e: Exception) {
            error = message(e)
        }
        notifyUi()
    }

    fun appearance(theme: String = this.theme, scale: Float = this.scale) {
        this.theme = theme
        this.scale = scale.coerceIn(.85f, 1.45f)
        runCatching {
                store.write(
                    "appearance",
                    Json.encode(mapOf("theme" to theme, "scale" to this.scale)),
                )
            }
            .onFailure { error = "Не удалось сохранить оформление." }
        notifyUi()
    }

    fun create(s: CreateScreen) {
        val active = client ?: return
        if (s.loading || s.uncertain != null) return
        val payload =
            try {
                s.draft.payload()
            } catch (e: Exception) {
                s.error = message(e)
                notifyUi()
                return
            }
        s.loading = true
        s.error = null
        notifyUi()
        background(
            { active.create(s.draft.resource, payload) },
            { row ->
                s.loading = false
                stack.remove(s)
                stack.filterIsInstance<RecordsScreen>().lastOrNull()?.let {
                    it.loaded = false
                    it.rows.clear()
                    it.more = true
                }
                card(s.draft.resource, row)
            },
            {
                s.loading = false
                if (it is CreationUncertain) s.uncertain = it.locator
                s.error = message(it)
            },
        )
    }

    fun verify(s: CreateScreen) {
        val active = client ?: return
        val locator = s.uncertain ?: return
        if (s.loading) return
        s.loading = true
        notifyUi()
        background(
            { active.readRecord(locator) },
            { row ->
                s.loading = false
                if (row != null) {
                    stack.remove(s)
                    card(s.draft.resource, row)
                } else
                    s.error =
                        "Запись пока не найдена. Проверьте результат в 1С. Повторная отправка заблокирована."
            },
            {
                s.loading = false
                s.error = message(it)
            },
        )
    }

    fun close() {
        closed = true
        epoch++
        render = null
        persist()
        client?.close()
        io.shutdownNow()
    }
}
