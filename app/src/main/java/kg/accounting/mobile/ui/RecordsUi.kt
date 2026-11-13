package kg.accounting.mobile.ui

import android.app.DatePickerDialog
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kg.accounting.mobile.core.*

fun AppScreens.records(screen: RecordsScreen): android.view.View {
    val spec = s.registry.resources[screen.node.title]
    val hierarchical = spec?.hierarchical == true
    val actions =
        listOf(
            if (screen.picker == null) star(Shortcut(nodeId = screen.node.id)) else u.text(""),
            u.action("↻", "Обновить") { if (!screen.loading) s.load(screen, true) },
        )
    val (root, body) =
        shell(
            if (hierarchical && screen.folder.key != ZERO) screen.folder.title
            else screen.node.title,
            actions,
        )
    val header = u.column(12)
    body.addView(header)
    if (hierarchical) {
        header.addView(
            u.text(
                if (screen.folder.key == ZERO) "Корень справочника"
                else screen.folder.path.joinToString(" / "),
                13f,
            )
        )
        if (screen.folder.key != ZERO)
            header.addView(
                u.button("В корень справочника", !screen.loading) {
                    val child =
                        RecordsScreen(
                            screen.node,
                            memory = s.preferences?.view(screen.node.title, ZERO) ?: ListMemory(),
                            picker = screen.picker,
                            foldersOnly = screen.foldersOnly,
                        )
                    s.push(child)
                    s.load(child)
                }
            )
    }
    if (screen.picker != null && screen.foldersOnly)
        header.addView(
            u.button(if (screen.folder.key == ZERO) "Выбрать корень" else "Выбрать эту папку") {
                screen.picker.invoke(screen.folder)
            }
        )
    if (screen.node.kind == "journal" || spec?.dateField != null) {
        val dates = u.row()
        fun choose(start: Boolean) {
            val current =
                runCatching {
                        LocalDate.parse(if (start) screen.memory.from else screen.memory.through)
                    }
                    .getOrDefault(LocalDate.now())
            DatePickerDialog(
                    a,
                    { _, year, month, day ->
                        if (start)
                            screen.memory.from = LocalDate.of(year, month + 1, day).toString()
                        else screen.memory.through = LocalDate.of(year, month + 1, day).toString()
                        screen.generation++
                        screen.loading = false
                        screen.loaded = false
                        screen.rows.clear()
                        screen.more = true
                        screen.memory.loaded = 0
                        screen.error = null
                        s.remember()
                        a.render()
                    },
                    current.year.coerceIn(1900, 2100),
                    current.monthValue - 1,
                    current.dayOfMonth,
                )
                .show()
        }

        fun label(value: String, prefix: String) =
            runCatching {
                    prefix +
                        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                }
                .getOrDefault(if (prefix == "С ") "Дата с" else "Дата по")
        dates.addView(
            u.button(label(screen.memory.from, "С "), !screen.loading) { choose(true) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        dates.addView(
            u.button(label(screen.memory.through, "По "), !screen.loading) { choose(false) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(dates)
        header.addView(
            u.button(
                if (spec?.kind == "register") "Получить данные" else "Получить документы",
                !screen.loading,
            ) {
                s.load(screen, true)
            }
        )
        header.addView(u.text("Начальная и конечная даты включаются целиком.", 12f))
    }
    if (
        screen.picker == null &&
            screen.node.title in listOf("Номенклатура", "Контрагенты", "Склады")
    )
        header.addView(
            u.button("Создать: ${screen.node.title}", !screen.loading) {
                s.push(CreateScreen(CatalogDraft(screen.node.title, folder = screen.folder)))
            }
        )
    var populateRecords: (() -> Unit)? = null
    val listArea = u.column()
    val count = u.text("")
    header.addView(
        u.input("Поиск по загруженным данным", screen.memory.query) {
            screen.memory.query = it
            s.remember()
            populateRecords?.invoke()
        }
    )
    header.addView(count)
    if (screen.loading) u.loading(header)
    u.error(header, screen.error)
    body.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))
    var liveList: ListView? = null
    fun fill() {
        val old = liveList
        val first = old?.firstVisiblePosition ?: screen.memory.first
        val offset = old?.getChildAt(0)?.top ?: screen.memory.offset
        listArea.removeAllViews()
        val query = screen.memory.query.trim().lowercase(Locale.ROOT)
        val visible = screen.rows.filter { Json.encode(it).lowercase(Locale.ROOT).contains(query) }
        count.text =
            "Получено: ${screen.rows.size}" +
                if (query.isNotEmpty()) " · Найдено: ${visible.size}" else ""
        liveList =
            u.list(listArea, visible) { index ->
                    val row = visible[index]
                    val folder = row["IsFolder"] == true
                    val title = recordTitle(row)
                    val subtitle =
                        listOf(
                                if (folder) "Папка" else "",
                                row["__resource"].text(),
                                row["Code"].text(),
                                if (row.containsKey("Date")) dateText(row["Date"]) else "",
                                row["СуммаДокумента"]?.let { "Сумма: $it" }.orEmpty(),
                            )
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                    val tail =
                        if (folder && screen.picker == null)
                            u.action("ⓘ", "Данные папки") { s.card(screen.node.title, row) }
                        else null
                    u.cell((if (folder) "▸ " else "") + title, subtitle, tail) {
                        if (folder && hierarchical) s.folder(screen, row)
                        else if (screen.picker != null) {
                            val key = row["Ref_Key"].text()
                            if (GUID.matches(key)) screen.picker.invoke(Selection(key, title))
                            else {
                                screen.error = "У записи нет корректного ключа."
                                a.render()
                            }
                        } else s.card(row["__resource"].text().ifEmpty { screen.node.title }, row)
                    }
                }
                .apply {
                    setSelectionFromTop(
                        first.coerceAtMost((visible.size - 1).coerceAtLeast(0)),
                        offset,
                    )
                    setOnScrollListener(
                        object : AbsListView.OnScrollListener {
                            override fun onScrollStateChanged(view: AbsListView?, state: Int) {}

                            override fun onScroll(
                                view: AbsListView?,
                                first: Int,
                                count: Int,
                                total: Int,
                            ) {
                                if (!screen.loaded || screen.loading || view?.childCount == 0)
                                    return
                                screen.memory.first = first
                                screen.memory.offset = view?.getChildAt(0)?.top ?: 0
                                s.remember()
                            }
                        }
                    )
                }
        if (visible.isEmpty() && !screen.loading)
            listArea.addView(
                u.text(
                    if (screen.loaded) "Совпадений нет. Поиск работает по загруженным данным."
                    else if (spec?.dateField != null || screen.node.kind == "journal")
                        "Выберите период и получите данные."
                    else "Записей нет."
                )
            )
        if (screen.more || screen.error != null)
            listArea.addView(
                u.button(
                    if (screen.error != null) "Повторить"
                    else if (screen.loaded) "Загрузить ещё" else "Получить данные",
                    !screen.loading,
                ) {
                    s.load(screen)
                }
            )
    }
    populateRecords = { fill() }
    fill()
    return root
}
