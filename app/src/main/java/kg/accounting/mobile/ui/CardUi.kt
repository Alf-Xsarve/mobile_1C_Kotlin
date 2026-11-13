package kg.accounting.mobile.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.widget.*
import java.util.Locale
import kg.accounting.mobile.core.*

private fun serviceField(key: String) =
    key == "Ref_Key" ||
        key == "DataVersion" ||
        key == "__metadata" ||
        key.endsWith("_Type") ||
        key.contains('@') ||
        key.startsWith("odata.") ||
        key.endsWith("____Presentation")

private fun salesFields(raw: Row): Row {
    fun read(name: String) = if (raw.containsKey(name)) raw[name] else raw["${name}_Key"]
    val fields =
        linkedMapOf<String, Any?>(
            "Номер" to raw["Number"],
            "Дата документа" to dateText(raw["Date"]),
            "Организация" to read("Организация"),
            "Контрагент" to read("Контрагент"),
            "Склад" to read("Склад"),
            "Договор контрагента" to read("ДоговорКонтрагента"),
            "Документ расчетов" to raw["Сделка"],
            "Скидка (%)" to raw["СкидкаПроцент"],
            "Сумма документа" to raw["СуммаДокумента"],
            "Сумма включает НДС" to raw["СуммаВключаетНДС"],
            "Счёт на оплату покупателю (основание)" to
                raw["ДокументОснованиеСчетНАОплатуПокупателю"],
            "Использовать скидку в ТМЗ" to raw["ИспользоватьСкидкуНаПозицииТМЗ"],
            "Курс взаиморасчетов" to raw["КурсВзаиморасчетов"],
            "Комментарий" to raw["Комментарий"],
            "Ответственный" to read("Ответственный"),
        )
    raw.filter { !it.key.contains('@') && it.value is List<*> }
        .forEach { fields[it.key] = it.value }
    return fields
}

fun AppScreens.card(screen: CardScreen): android.view.View {
    var value = screen.value ?: screen.raw
    if (screen.collection == "Document_РеализацияТоваровУслуг") value = salesFields(value.row())
    val all: List<Pair<String, Any?>> =
        when (value) {
            is Map<*, *> ->
                value.row().entries.filter { !serviceField(it.key) }.map { it.key to it.value }
            is List<*> -> value.mapIndexed { i, v -> "[${i + 1}]" to v }
            else -> listOf("Значение" to value)
        }

    fun fields() {
        val hidden = s.preferences!!.hidden(screen.path).toMutableSet()
        val names = all.map { it.first }
        val flags = names.map { it !in hidden }.toBooleanArray()
        AlertDialog.Builder(a)
            .setTitle("Видимые реквизиты")
            .setMultiChoiceItems(names.map { it.removeSuffix("_Key") }.toTypedArray(), flags) {
                _,
                i,
                checked ->
                if (checked) hidden.remove(names[i]) else hidden.add(names[i])
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Показать все") { _, _ ->
                s.safePreference { it.hidden(screen.path, emptySet()) }
            }
            .setPositiveButton("Сохранить") { _, _ ->
                s.safePreference { it.hidden(screen.path, hidden) }
            }
            .show()
    }

    val actions = mutableListOf<android.view.View>()
    screen.locator?.let { actions += star(Shortcut(locator = it)) }
    if (value is Map<*, *>)
        actions += u.action("☷", "Настроить реквизиты") { if (!screen.loading) fields() }
    actions += u.action("{ }", "Показать JSON") { s.push(SourceScreen(screen.title, screen.raw)) }
    val (root, body) = shell(screen.title, actions)
    if (screen.loading) u.loading(body)
    u.error(body, screen.error)
    if (screen.issues.isNotEmpty()) {
        body.addView(
            u.button("Часть связанных данных недоступна. Подробности") {
                AlertDialog.Builder(a)
                    .setTitle("Связанные данные")
                    .setMessage(screen.issues.joinToString("\n"))
                    .setPositiveButton("Закрыть", null)
                    .show()
            }
        )
        body.addView(
            u.button("Повторить загрузку ссылок", !screen.loading) {
                s.client!!.references.invalidate()
                s.resolve(screen)
            }
        )
    }
    val area = u.column()
    var list: ListView? = null
    fun copy(label: String, v: Any?) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText(label, display(v))
        )
        Toast.makeText(a, "Скопировано: $label", Toast.LENGTH_SHORT).show()
    }

    fun fill() {
        area.removeAllViews()
        if (screen.loading) {
            area.addView(u.text("Загрузка связанных данных…"))
            return
        }
        val hidden = s.preferences!!.hidden(screen.path)
        val q = screen.query.trim().lowercase(Locale.ROOT)
        val visible =
            all.filter {
                it.first !in hidden && "${it.first} ${it.second}".lowercase(Locale.ROOT).contains(q)
            }
        if (visible.isEmpty()) {
            area.addView(u.text("Нет подходящих полей. Настройки доступны в верхней панели."))
            return
        }
        list =
            u.list(area, visible) { i ->
                val (key, child) = visible[i]
                val label = key.removeSuffix("_Key")
                val nested = child is Map<*, *> || child is List<*>
                val summary =
                    when (child) {
                        is Map<*, *> -> "Объект · полей: ${child.size}"
                        is List<*> -> "Список · элементов: ${child.size}"
                        else -> display(child)
                    }
                val cell =
                    u.cell(
                        label,
                        summary,
                        if (nested) null
                        else u.action("⧉", "Копировать $label") { copy(label, child) },
                        if (nested) {
                            {
                                val raw =
                                    when (val original = screen.raw) {
                                        is Map<*, *> -> original[key]
                                        is List<*> ->
                                            original.getOrNull(
                                                key.removeSurrounding("[", "]").toInt() - 1
                                            )
                                        else -> child
                                    }
                                s.push(
                                    CardScreen(
                                        label,
                                        raw,
                                        null,
                                        path =
                                            screen.path +
                                                if (screen.raw is List<*>) "/row" else "/$key",
                                        value = child,
                                        resolved = true,
                                    )
                                )
                            }
                        } else null,
                    )
                if (!nested)
                    cell.setOnLongClickListener {
                        copy(label, child)
                        true
                    }
                cell
            }
    }
    body.addView(
        u.input("Поиск по полям и значениям", screen.query) {
            screen.query = it
            fill()
        }
    )
    body.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
    fill()
    return root
}

fun AppScreens.source(screen: SourceScreen): android.view.View {
    val (root, body) = shell("JSON · ${screen.title}")
    val raw = Json.encode(screen.raw, true)
    val text =
        u.text(raw, 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8))
        }

    fun highlight(q: String) {
        val result = SpannableString(raw)
        if (q.isNotBlank()) {
            var index = 0
            var count = 0
            while (count++ < 5000) {
                index = raw.indexOf(q, index, ignoreCase = true)
                if (index < 0) break
                result.setSpan(BackgroundColorSpan(0xFFFFDC45.toInt()), index, index + q.length, 0)
                index += q.length
            }
        }
        text.text = result
    }
    body.addView(
        u.input("Поиск в JSON", screen.query) {
            screen.query = it
            highlight(it)
        }
    )
    body.addView(u.scroll(text), LinearLayout.LayoutParams(-1, 0, 1f))
    highlight(screen.query)
    return root
}
