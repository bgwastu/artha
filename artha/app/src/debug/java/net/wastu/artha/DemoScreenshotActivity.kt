package net.wastu.artha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Debug-only captures of the same isolated demo used by the shareable app. */
class DemoScreenshotActivity : DemoActivity() {
    override val screenshotMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("capture", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDemoReady() {
        if (!intent.getBooleanExtra("capture", false)) return
        lifecycleScope.launch {
            delay(1200)
            window.decorView.doOnPreDraw { view ->
                view.post {
                    if (hasWindowFocus()) capture(
                        if (intent.getStringExtra("destination") == "ANALYSIS") "analysis" else "transactions",
                        if (intent.getBooleanExtra("dark", false)) "dark" else "light",
                    )
                }
            }
            window.decorView.invalidate()
        }
    }

    private fun capture(destination: String, theme: String) {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    check(result == PixelCopy.SUCCESS) { "Demo screenshot capture failed" }
                    val directory = File(filesDir, "demo-screenshots").apply { mkdirs() }
                    File(directory, "$destination-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (destination == "analysis") {
                        val timeline = android.graphics.BitmapFactory.decodeFile(File(directory, "transactions-$theme.png").path)
                        check(timeline != null && timeline.height == bitmap.height)
                        val gap = 32
                        val combined = Bitmap.createBitmap(timeline.width + gap + bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        Canvas(combined).apply {
                            drawColor(Color.TRANSPARENT)
                            drawBitmap(timeline, 0f, 0f, null)
                            drawBitmap(bitmap, (timeline.width + gap).toFloat(), 0f, null)
                        }
                        File(directory, "screenshot-$theme.png").outputStream().use { combined.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        timeline.recycle(); combined.recycle()
                    }
                    bitmap.recycle()
                    File(directory, "$destination-$theme.done").writeText("fictional-only")
                }
                finish()
            }
        }, Handler(Looper.getMainLooper()))
    }
}
