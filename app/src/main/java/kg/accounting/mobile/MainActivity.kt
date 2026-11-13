package kg.accounting.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import kg.accounting.mobile.ui.*

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {
    lateinit var session: AppSession
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? AppSession ?: AppSession(applicationContext)
        session.render = { render() }
        render()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!session.back()) finish()
            }
    }

    fun render() {
        if (isFinishing || isDestroyed) return
        val focused = currentFocus as? EditText
        val tag = focused?.tag
        val cursor = focused?.selectionStart ?: 0
        val dark =
            when (session.theme) {
                "dark" -> true
                "light" -> false
                else -> resources.configuration.uiMode and 48 == 32
            }
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        ui = Ui(this, dark, session.scale)
        val root = AppScreens(this, ui).render()
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            insets
        }
        window.statusBarColor = ui.header
        window.navigationBarColor = ui.background
        window.decorView.systemUiVisibility =
            if (dark) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        root.requestApplyInsets()
        if (tag != null)
            root.findViewWithTag<EditText>(tag)?.let {
                it.requestFocus()
                it.setSelection(cursor.coerceIn(0, it.text.length))
            }
    }

    fun scan() {
        startActivityForResult(Intent(this, QrActivity::class.java), 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 700 && resultCode == RESULT_OK) {
            try {
                session.connect(
                    kg.accounting.mobile.core.Profile.fromCode(
                        data?.getStringExtra("code").orEmpty()
                    )
                )
            } catch (e: Exception) {
                session.error = session.message(e)
                render()
            }
        }
    }

    // Только Android 8–12; на API 33+ выше зарегистрирован OnBackInvokedCallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (!session.back()) super.onBackPressed()
    }

    override fun onStop() {
        session.remember()
        session.persist()
        super.onStop()
    }

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onDestroy() {
        session.render = null
        if (!isChangingConfigurations) session.close()
        super.onDestroy()
    }
}
