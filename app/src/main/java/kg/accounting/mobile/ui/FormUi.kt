package kg.accounting.mobile.ui

import android.widget.*
import kg.accounting.mobile.core.*

fun AppScreens.form(screen: CreateScreen): android.view.View {
    val d = screen.draft
    val (root, body) = shell("Создать: ${d.resource}")
    val content = u.column(16)
    body.addView(u.scroll(content))
    fun pick(resource: String, folderOnly: Boolean, changed: (Selection) -> Unit) {
        val records =
            RecordsScreen(
                MenuNode(resource, resource, "resource"),
                picker = { value ->
                    changed(value)
                    while (s.stack.lastOrNull() !== screen && s.stack.size > 1) s.stack.removeAt(
                        s.stack.lastIndex
                    )
                    a.render()
                },
                foldersOnly = folderOnly,
            )
        s.push(records)
        s.load(records)
    }
    content.addView(
        u.button("Папка: ${d.folder.title}", !screen.loading && screen.uncertain == null) {
            pick(d.resource, true) { d.folder = it }
        }
    )
    fun field(label: String, value: String, multiline: Boolean = false, changed: (String) -> Unit) {
        content.addView(
            u.input(label, value, multiline = multiline, changed = changed).apply {
                isEnabled = !screen.loading && screen.uncertain == null
            }
        )
    }
    field("Наименование", d.name) { d.name = it }
    if (d.resource != "Склады") field("Полное наименование", d.fullName) { d.fullName = it }
    when (d.resource) {
        "Контрагенты" -> {
            field("ИНН", d.inn) { d.inn = it }
            val group = RadioGroup(a)
            listOf("ЮрЛицо" to "Юридическое лицо", "ФизЛицо" to "Физическое лицо").forEach {
                (value, title) ->
                group.addView(
                    RadioButton(a).apply {
                        text = title
                        setTextColor(u.foreground)
                        textSize = 16f * u.scale
                        isChecked = d.personType == value
                        isEnabled = !screen.loading && screen.uncertain == null
                        setOnClickListener { d.personType = value }
                    }
                )
            }
            content.addView(group)
        }

        "Номенклатура" -> {
            content.addView(
                Switch(a).apply {
                    text = "Услуга"
                    textSize = 16f * u.scale
                    setTextColor(u.foreground)
                    isChecked = d.service
                    isEnabled = !screen.loading && screen.uncertain == null
                    setOnCheckedChangeListener { _, v -> d.service = v }
                }
            )
            field("Штрихкод", d.barcode) { d.barcode = it }
            content.addView(
                u.button(
                    "Единица измерения: ${d.unit?.title ?: "Выбрать"}",
                    !screen.loading && screen.uncertain == null,
                ) {
                    pick("Классификатор единиц измерения", false) { d.unit = it }
                }
            )
            if (d.unit != null)
                content.addView(
                    u.action("Очистить единицу измерения") {
                        d.unit = null
                        a.render()
                    }
                )
            content.addView(
                u.button(
                    "ВЭД: ${d.ved?.title ?: "Выбрать"}",
                    !screen.loading && screen.uncertain == null,
                ) {
                    pick("ВЭД", false) { d.ved = it }
                }
            )
            if (d.ved != null)
                content.addView(
                    u.action("Очистить ВЭД") {
                        d.ved = null
                        a.render()
                    }
                )
        }

        "Склады" -> {
            field("Адрес", d.address, true) { d.address = it }
            field("Префикс", d.prefix) { d.prefix = it }
        }
    }
    field("Комментарий", d.comment, true) { d.comment = it }
    u.error(content, screen.error)
    if (screen.loading) u.loading(content)
    content.addView(
        u.button("Создать в 1С", !screen.loading && screen.uncertain == null) { s.create(screen) }
    )
    if (screen.uncertain != null)
        content.addView(u.button("Проверить запись в 1С", !screen.loading) { s.verify(screen) })
    content.addView(
        u.text(
            "Запись выполняется в текущей базе. Обязательные реквизиты и права окончательно проверяет 1С.",
            13f,
        )
    )
    return root
}
