package kg.accounting.mobile.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class Ui(val activity: Activity, val dark: Boolean, val scale: Float) {
    val background = Color.parseColor(if (dark) "#17150E" else "#FFF9F0")
    val foreground = Color.parseColor(if (dark) "#EDE4CD" else "#29251C")
    val muted = Color.parseColor(if (dark) "#C6BFAE" else "#655F50")
    val accent = Color.parseColor(if (dark) "#E7C969" else "#806500")
    val header = Color.parseColor(if (dark) "#3B3200" else "#FFDC45")

    fun dp(n: Int) = (activity.resources.displayMetrics.density * n).toInt()

    fun column(pad: Int = 0) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            setBackgroundColor(this@Ui.background)
        }

    fun row() =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun text(value: String, size: Float = 16f, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size * scale
            setTextColor(this@Ui.foreground)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    fun button(label: String, enabled: Boolean = true, action: () -> Unit) =
        Button(activity).apply {
            text = label
            textSize = 14f * scale
            isAllCaps = false
            isEnabled = enabled
            minHeight = dp(48)
            minimumWidth = dp(44)
            setTextColor(if (dark) Color.BLACK else Color.WHITE)
            background =
                GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(24).toFloat()
                }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { action() }
            contentDescription = label
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        }

    fun action(label: String, description: String = label, block: () -> Unit) =
        TextView(activity).apply {
            text = label
            contentDescription = description
            textSize = 18f * scale
            setTextColor(this@Ui.foreground)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { block() }
        }

    fun input(
        hint: String,
        value: String = "",
        password: Boolean = false,
        multiline: Boolean = false,
        changed: (String) -> Unit,
    ): EditText =
        EditText(activity).apply {
            this.hint = hint
            contentDescription = hint
            tag = hint
            setText(value)
            textSize = 16f * scale
            setTextColor(this@Ui.foreground)
            setHintTextColor(muted)
            inputType =
                if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else InputType.TYPE_CLASS_TEXT
            isSingleLine = !multiline
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(52)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        changed(s.toString())
                    }

                    override fun afterTextChanged(s: Editable?) {}
                }
            )
        }

    fun scroll(content: View) =
        ScrollView(activity).apply {
            isFillViewport = true
            addView(content)
        }

    fun divider() =
        View(activity).apply {
            setBackgroundColor(if (dark) 0xFF484339.toInt() else 0xFFE2D8C7.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }

    fun cell(
        title: String,
        subtitle: String = "",
        tail: View? = null,
        click: (() -> Unit)? = null,
    ): LinearLayout {
        val row =
            row().apply {
                setPadding(dp(16), dp(8), dp(12), dp(8))
                minimumHeight = dp(72)
            }
        val body =
            column().apply {
                addView(text(title, 16f))
                if (subtitle.isNotEmpty())
                    addView(text(subtitle, 13f).apply { setTextColor(muted) })
            }
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        if (tail != null) row.addView(tail)
        if (click != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { click() }
        }
        return row
    }

    fun error(parent: LinearLayout, value: String?) {
        if (!value.isNullOrBlank())
            parent.addView(
                text(value, 14f).apply {
                    setTextColor(if (dark) 0xFFFFB4AB.toInt() else 0xFFAE2424.toInt())
                    setTextIsSelectable(true)
                }
            )
    }

    fun loading(parent: LinearLayout) {
        parent.addView(
            ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }
        )
    }

    fun list(parent: LinearLayout, items: List<Any>, build: (Int) -> View): ListView =
        ListView(activity).apply {
            dividerHeight = dp(1)
            setBackgroundColor(this@Ui.background)
            adapter =
                object : BaseAdapter() {
                    override fun getCount() = items.size

                    override fun getItem(position: Int) = items[position]

                    override fun getItemId(position: Int) = position.toLong()

                    override fun getView(position: Int, convertView: View?, p: ViewGroup?) =
                        build(position)
                }
            parent.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        }
}
