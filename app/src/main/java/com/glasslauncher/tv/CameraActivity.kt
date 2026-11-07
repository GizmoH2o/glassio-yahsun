package com.glasslauncher.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * A camera over whatever is on (Home Assistant's doorbell, through the phone page's /api/ha/camera): a card at the top
 * right with the camera's picture, read again every second, and its title. It goes by itself (20 s unless HA says
 * otherwise) or with any button; the window around it is see-through, so what's playing goes on behind.
 */
class CameraActivity : Activity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_MESSAGE = "message"

        /** A notice over whatever is on (Home Assistant's /api/ha/notify): `title`, `message`, for `seconds`. */
        fun notice(ctx: Context, title: String, message: String, seconds: Int) {
            ctx.startActivity(Intent(ctx, CameraActivity::class.java)
                .putExtra(EXTRA_URL, "").putExtra(EXTRA_TITLE, title).putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_SECONDS, seconds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        /** Shows `url` (a picture, read again every second) with `title`, for `seconds`. */
        fun show(ctx: Context, url: String, title: String, seconds: Int) {
            ctx.startActivity(Intent(ctx, CameraActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SECONDS, seconds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var picture: ImageView
    private lateinit var caption: TextView
    private var url = ""
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        caption = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xE6101014.toInt()) }
            clipToOutline = true
            addView(picture, LinearLayout.LayoutParams((480 * d).toInt(), (270 * d).toInt()))
            addView(caption)
        }
        setContentView(FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = (32 * d).toInt(); rightMargin = (40 * d).toInt()
            })
        })
        take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private val close = Runnable { finish() }

    private fun take(i: Intent) {
        url = i.getStringExtra(EXTRA_URL).orEmpty()
        val message = i.getStringExtra(EXTRA_MESSAGE).orEmpty()
        // No picture: a notice, its title over its message.
        picture.visibility = if (url.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        val title = i.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { if (url.isEmpty()) "" else "Camera" }
        caption.text = if (message.isEmpty()) title else android.text.SpannableStringBuilder().apply {
            if (title.isNotEmpty()) {
                append(title, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n")
            }
            append(message)
        }
        caption.maxWidth = (480 * resources.displayMetrics.density).toInt()
        main.removeCallbacks(close)
        main.postDelayed(close, i.getIntExtra(EXTRA_SECONDS, 20).coerceIn(5, 120) * 1000L)
    }

    override fun onStart() {
        super.onStart()
        running = true
        Thread({
            while (running && url.isNotEmpty()) {
                val bmp = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 5000
                    try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
                }.getOrNull()
                if (bmp != null) main.post { picture.setImageBitmap(bmp) }
                Thread.sleep(1000)
            }
        }, "camera").apply { isDaemon = true; start() }
    }

    override fun onStop() {
        running = false
        super.onStop()
        finish()        // gone from behind whatever comes next
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
