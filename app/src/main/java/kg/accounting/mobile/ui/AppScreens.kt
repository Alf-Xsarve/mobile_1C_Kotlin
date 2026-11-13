package kg.accounting.mobile.ui

import android.app.AlertDialog
import android.app.Dialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale
import kg.accounting.mobile.MainActivity
import kg.accounting.mobile.core.*

class AppScreens(val a: MainActivity, val u: Ui) {
    val s
        get() = a.session

    fun render(): View {
        if (s.restoring) {
            val body = u.column(24)
            body.addView(u.text("Загрузка…"))
            u.loading(body)
            return body
        }
        if (s.profile == null) return connection()
        return when (val screen = s.screen) {
            HomeScreen -> home()
            SettingsScreen -> settings()
            is RecordsScreen -> records(screen)
            is CardScreen -> card(screen)
            is CreateScreen -> form(screen)
            is SourceScreen -> source(screen)
            is ShortcutsScreen -> shortcuts(screen)
        }
    }

    fun shell(
        title: String,
        actions: List<View> = emptyList(),
        home: Boolean = true,
    ): Pair<LinearLayout, LinearLayout> {
        val root = u.column()
        val bar =
            u.row().apply {
                setBackgroundColor(u.header)
                minimumHeight = u.dp(56)
            }
        if (s.stack.size > 1) bar.addView(u.action("‹", "Назад") { s.back() })
        else if (s.profile != null) bar.addView(u.action("☰", "Меню") { menu() })
        bar.addView(
            u.text(title, 20f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(u.dp(8), 0, u.dp(4), 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        actions.forEach { bar.addView(it) }
        if (home && s.stack.size > 1) bar.addView(u.action("⌂", "На главную") { s.home() })
        root.addView(bar)
        val body = u.column()
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if (s.error != null) {
            val box = u.column(12)
            u.error(box, s.error)
            box.addView(
                u.action("Закрыть сообщение") {
                    s.error = null
                    a.render()
                }
            )
            body.addView(box)
        }
        return root to body
    }

    fun star(shortcut: Shortcut): View {
        val v =
            u.action(
                if (s.preferences?.favorite(shortcut) == true) "★" else "☆",
                "Избранное: ${shortcut.title(s.registry)}",
            ) {}
        v.setOnClickListener {
            s.safePreference { it.toggle(shortcut) }
            v.text = if (s.preferences?.favorite(shortcut) == true) "★" else "☆"
        }
        return v
    }

    fun connection(): View {
        val (root, body) = shell("Подключение к 1С", home = false)
        val content = u.column(20)
        body.addView(u.scroll(content))
        if (s.restoreFailed) {
            u.error(content, s.error)
            content.addView(u.button("Сбросить подключение", !s.signingIn) { s.logout() })
            return root
        }
        content.addView(u.text("Добро пожаловать", 24f, true))
        content.addView(u.text("Отсканируйте QR-код подключения или введите настройки вручную."))
        content.addView(u.button("Сканировать QR-код", !s.signingIn) { a.scan() })
        content.addView(
            u.button(
                if (s.manual) "Свернуть ручной ввод" else "Ввести данные вручную",
                !s.signingIn,
            ) {
                s.manual = !s.manual
                a.render()
            }
        )
        if (s.manual) {
            content.addView(u.input("Путь к OData", s.address) { s.address = it })
            content.addView(u.input("Логин", s.username) { s.username = it })
            content.addView(u.input("Пароль", s.password, password = true) { s.password = it })
            content.addView(
                u.button("Войти", !s.signingIn) {
                    try {
                        s.connect(Profile.create(s.address, s.username, s.password))
                    } catch (e: Exception) {
                        s.error = s.message(e)
                        a.render()
                    }
                }
            )
        }
        if (s.signingIn) {
            u.loading(content)
            content.addView(u.text("Проверка подключения и сохранение…"))
        }
        content.addView(
            u.text(
                "После подключения приложение будет открывать главное меню. Для смены базы используйте «Выйти».",
                14f,
            )
        )
        return root
    }

    fun home(): View {
        val (root, body) =
            shell(
                "Данные 1С",
                listOf(
                    u.action("⚙", "Настройки") { s.push(SettingsScreen) },
                    u.action("Выйти") {
                        AlertDialog.Builder(a)
                            .setTitle("Выйти из базы?")
                            .setMessage("Подключение будет удалено с телефона.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Выйти") { _, _ -> s.logout() }
                            .show()
                    },
                ),
                false,
            )
        val content = u.column(16)
        body.addView(u.scroll(content))
        content.addView(u.text("Пользователь: ${s.profile!!.username}"))
        content.addView(u.text(s.profile!!.url, 12f))
        val shortcuts = u.column()
        var query = ""
        fun populate() {
            shortcuts.removeAllViews()
            val p = s.preferences!!
            fun section(title: String, entries: List<Shortcut>, history: Boolean) {
                val filtered =
                    entries.filter {
                        ("${it.title(s.registry)} ${it.subtitle()}")
                            .lowercase(Locale.ROOT)
                            .contains(query)
                    }
                if (filtered.isEmpty()) return
                shortcuts.addView(
                    u.cell(title, tail = u.action("Все") { s.push(ShortcutsScreen(history)) })
                )
                filtered.take(5).forEach { v ->
                    shortcuts.addView(
                        u.cell(v.title(s.registry), v.subtitle(), star(v)) { s.shortcut(v) }
                    )
                }
            }
            section("Избранное", p.favorites, false)
            section("Недавно открытые", p.recent.map { Shortcut(locator = it) }, true)
            shortcuts.addView(u.text("Быстрый доступ", 18f, true))
            listOf("Номенклатура", "Контрагенты", "Склады", "Реализация товаров и услуг")
                .filter { it.lowercase(Locale.ROOT).contains(query) }
                .forEach { name ->
                    shortcuts.addView(u.button(name) { s.open(MenuNode(name, name, "resource")) })
                }
        }
        content.addView(
            u.input("Поиск быстрого доступа") {
                query = it.trim().lowercase(Locale.ROOT)
                populate()
            }
        )
        content.addView(shortcuts)
        populate()
        content.addView(u.text("Разделы учёта открываются кнопкой «Меню» в верхней панели.", 14f))
        content.addView(u.text("Версия 1.0.0 · Kotlin · Android", 12f))
        return root
    }

    fun shortcuts(screen: ShortcutsScreen): View {
        val (root, body) = shell(if (screen.history) "Недавно открытые" else "Избранное")
        val area = u.column()
        body.addView(
            u.input("Поиск по названию", screen.query) {
                screen.query = it
                populate()
            }
        )
        body.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        fun entries() =
            (if (screen.history) s.preferences!!.recent.map { Shortcut(locator = it) }
                else s.preferences!!.favorites)
                .filter {
                    ("${
                    it.title(s.registry)
                } ${it.subtitle()}")
                        .lowercase(Locale.ROOT)
                        .contains(screen.query.lowercase(Locale.ROOT))
                }

        fun rebuild() {
            area.removeAllViews()
            val values = entries()
            if (values.isEmpty()) area.addView(u.text("Записей нет."))
            else
                u.list(area, values) { i ->
                    val v = values[i]
                    u.cell(v.title(s.registry), v.subtitle(), star(v)) { s.shortcut(v) }
                }
        }
        // Обновление поиска не пересоздаёт поле и не теряет фокус клавиатуры.
        currentPopulate = { rebuild() }
        rebuild()
        return root
    }

    private var currentPopulate: (() -> Unit)? = null

    private fun populate() {
        currentPopulate?.invoke()
    }

    fun menu() {
        val dialog = Dialog(a)
        val content = u.column(16)
        val expanded = mutableSetOf<String>()
        var query = ""
        content.addView(
            u.cell("Разделы учёта", tail = u.action("×", "Закрыть меню") { dialog.dismiss() })
        )
        val tree = u.column()
        fun build() {
            tree.removeAllViews()
            fun walk(node: MenuNode, depth: Int) {
                if (!node.matches(query)) return
                if (node.children.isNotEmpty()) {
                    val open = query.isNotEmpty() || node.id in expanded
                    tree.addView(
                        u.cell(
                                (if (open) "▾ " else "▸ ") + node.title,
                                click = {
                                    if (!expanded.add(node.id)) expanded.remove(node.id)
                                    build()
                                },
                            )
                            .apply { setPadding(u.dp(depth * 10), 0, 0, 0) }
                    )
                    if (open) node.children.forEach { walk(it, depth + 1) }
                } else {
                    val fav =
                        if (node.kind == "unavailable") null else star(Shortcut(nodeId = node.id))
                    tree.addView(
                        u.cell(
                                node.title,
                                if (node.kind == "journal") "Журнал"
                                else if (node.kind == "unavailable") "Требуется серверный интерфейс"
                                else "",
                                fav,
                            ) {
                                dialog.dismiss()
                                s.open(node)
                            }
                            .apply { setPadding(u.dp(depth * 10), 0, 0, 0) }
                    )
                }
            }
            s.registry.menu.forEach { walk(it, 0) }
        }
        content.addView(
            u.input("Поиск по меню") {
                query = it.trim().lowercase(Locale.ROOT)
                build()
            }
        )
        content.addView(u.scroll(tree), LinearLayout.LayoutParams(-1, 0, 1f))
        build()
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.START)
        dialog.window?.setLayout(
            (a.resources.displayMetrics.widthPixels * .9).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
        dialog.window?.setLayout(
            (a.resources.displayMetrics.widthPixels * .9).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}
