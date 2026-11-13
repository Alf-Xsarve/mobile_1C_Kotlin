package kg.accounting.mobile.ui

import android.app.AlertDialog
import android.widget.*

fun AppScreens.settings(): android.view.View {
    val (root, body) = shell("Настройки")
    val content = u.column(16)
    body.addView(u.scroll(content))
    content.addView(u.text("Оформление", 22f, true))
    val group = RadioGroup(a)
    listOf("system" to "Как на устройстве", "light" to "Светлая", "dark" to "Тёмная").forEach {
        (key, title) ->
        group.addView(
            RadioButton(a).apply {
                text = title
                textSize = 16f * u.scale
                setTextColor(u.foreground)
                isChecked = s.theme == key
                setOnClickListener { s.appearance(theme = key) }
            }
        )
    }
    content.addView(group)
    val label = u.text("Размер текста: ${(s.scale * 100).toInt()}%")
    content.addView(label)
    content.addView(
        SeekBar(a).apply {
            max = 12
            progress = ((s.scale - .85f) / .05f).toInt()
            contentDescription = "Размер текста"
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {
                        label.text = "Размер текста: ${(85 + progress * 5)}%"
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        s.appearance(scale = .85f + bar.progress * .05f)
                    }
                }
            )
        }
    )
    content.addView(u.text("Пример текста: Номенклатура, количество и сумма."))
    content.addView(u.button("Вернуть размер 100%") { s.appearance(scale = 1f) })
    content.addView(u.divider())
    content.addView(u.text("Для текущей базы", 22f, true))
    val p = s.preferences!!
    fun switch(title: String, checked: Boolean, action: (Boolean) -> Unit) {
        content.addView(
            Switch(a).apply {
                text = title
                textSize = 16f * u.scale
                setTextColor(u.foreground)
                setPadding(0, u.dp(12), 0, u.dp(12))
                isChecked = checked
                setOnCheckedChangeListener { _, value -> action(value) }
            }
        )
    }
    switch("Запоминать списки", p.rememberViews) { value ->
        s.safePreference {
            it.rememberViews = value
            it.save()
        }
    }
    content.addView(u.text("Период, папка, поиск и положение списка", 13f))
    switch("Сохранять недавно открытые", p.saveHistory) { value ->
        s.safePreference {
            it.saveHistory = value
            it.save()
        }
    }
    content.addView(u.text("Ссылки на последние 30 карточек", 13f))
    content.addView(
        u.button("Очистить историю") {
            AlertDialog.Builder(a)
                .setTitle("Очистить историю?")
                .setMessage("Избранное и данные в 1С сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить") { _, _ -> s.safePreference { it.clearHistory() } }
                .show()
        }
    )
    content.addView(
        u.button("Сбросить периоды и позиции списков") { s.safePreference { it.clearViews() } }
    )
    content.addView(
        u.text(
            "Избранное, история и настройки списков хранятся отдельно для адреса базы и пользователя. Тема и размер текста — для этого телефона.",
            14f,
        )
    )
    return root
}
