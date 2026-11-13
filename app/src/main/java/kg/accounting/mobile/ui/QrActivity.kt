package kg.accounting.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*

class QrActivity : Activity() {
    private var camera: DecoratedBarcodeView? = null
    private var captured = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Контроллер доступен только после создания DecorView.
            window.decorView.post {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
    }

    private fun startCamera() {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 32, 16, 24)
                setBackgroundColor(0xFF111111.toInt())
            }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val pad = (16 * resources.displayMetrics.density).toInt()
                v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            }
            insets
        }
        root.addView(
            TextView(this).apply {
                text = "Наведите камеру на QR-код подключения к 1С"
                setTextColor(-1)
                textSize = 18f
            }
        )
        camera =
            DecoratedBarcodeView(this).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                setStatusText("")
                decodeSingle(
                    object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult?) {
                            if (captured || result == null) return
                            captured = true
                            pause()
                            setResult(RESULT_OK, Intent().putExtra("code", result.text.take(16385)))
                            finish()
                        }
                    }
                )
            }
        root.addView(camera, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            Button(this).apply {
                text = "Отмена"
                setOnClickListener { finish() }
            }
        )
        setContentView(root)
        camera?.resume()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 701) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else {
                val ui = Ui(this, false, 1f)
                val body = ui.column(24)
                body.addView(
                    ui.text(
                        "Для сканирования нужен доступ к камере. Можно вернуться и ввести настройки вручную."
                    )
                )
                body.addView(ui.button("Назад") { finish() })
                setContentView(body)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        camera?.resume()
    }

    override fun onPause() {
        camera?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        camera?.pause()
        camera = null
        super.onDestroy()
    }
}
