package com.example.tvwebapp

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.min

/**
 * Full-screen website wrapper for Fire TV (Fire OS).
 *
 * Remote controls:
 *   Arrows / OK      move the on-screen pointer and click (pointer mode), or go to the page (pointer off)
 *   Menu (☰)         turn the pointer on/off
 *   Back             leave full-screen video, then go back a page, then exit the app
 *   Play/Pause, ⏪ ⏩  control the video on the page (skip 10 s)
 *
 * The website address lives in res/values/config.xml.
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursor: CursorView

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var pointerMode = false
    private var touchDownTime = 0L

    private val startUrl: String by lazy { getString(R.string.start_url) }
    private val density: Float by lazy { resources.displayMetrics.density }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stop the Fire TV screensaver from kicking in during a video.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val fill = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this)
        cursor = CursorView(this).apply { visibility = View.GONE }
        container.addView(webView, fill)
        container.addView(cursor, FrameLayout.LayoutParams(fill))
        setContentView(container)

        // Debug builds can be inspected from a computer at chrome://inspect over ADB.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
        }
        webView.setBackgroundColor(Color.BLACK)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(startUrl)
        }
        webView.requestFocus()

        container.post {
            cursor.moveTo(container.width / 2f, container.height / 2f)
            setPointerMode(resources.getBoolean(R.bool.start_in_pointer_mode), announce = false)
        }
    }

    // ---------------------------------------------------------------- remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) handleBack()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) setPointerMode(!pointerMode, announce = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) videoJs("if (v.paused) { v.play(); } else { v.pause(); }")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) videoJs("v.play();")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) videoJs("v.pause();")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) videoJs("v.currentTime = Math.min(v.duration || 1e9, v.currentTime + 10);")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) videoJs("v.currentTime = Math.max(0, v.currentTime - 10);")
                return true
            }
        }

        if (pointerMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) moveCursor(event.keyCode, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (first) touch(MotionEvent.ACTION_DOWN)
                    else if (event.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    private fun setPointerMode(on: Boolean, announce: Boolean) {
        pointerMode = on
        cursor.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) webView.requestFocus()
        if (announce) {
            val msg = if (on) "Pointer on (press ☰ to turn off)" else "Pointer off (press ☰ to turn on)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Moves the pointer; holding an arrow speeds it up. Pushing past an edge scrolls the page. */
    private fun moveCursor(keyCode: Int, repeatCount: Int) {
        val step = (12 + min(repeatCount, 12) * 4) * density
        var x = cursor.cx
        var y = cursor.cy
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> y += step
            KeyEvent.KEYCODE_DPAD_LEFT -> x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> x += step
        }
        val maxX = container.width - 1f
        val maxY = container.height - 1f
        when {
            y < 0f -> scrollAtCursor(vertical = 1f, horizontal = 0f)
            y > maxY -> scrollAtCursor(vertical = -1f, horizontal = 0f)
            x < 0f -> scrollAtCursor(vertical = 0f, horizontal = -1f)
            x > maxX -> scrollAtCursor(vertical = 0f, horizontal = 1f)
        }
        cursor.moveTo(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY))
    }

    /** Simulates a finger tap at the pointer. */
    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val ev = MotionEvent.obtain(touchDownTime, now, action, cursor.cx, cursor.cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        container.dispatchTouchEvent(ev)
        ev.recycle()
        cursor.setPressedLook(action == MotionEvent.ACTION_DOWN)
    }

    /** Simulates a mouse wheel at the pointer, so whatever is under it scrolls (lists, rows, the page). */
    private fun scrollAtCursor(vertical: Float, horizontal: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.cx
            y = cursor.cy
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        webView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Runs [action] against the playing <video> on the page (or the first one), available as `v`. */
    private fun videoJs(action: String) {
        val js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));" +
            "var v=vs.filter(function(x){return !x.paused;})[0]||vs[0];" +
            "if(v){$action}})();"
        webView.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- full-screen video

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        container.removeView(view)
        webView.visibility = View.VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webView.requestFocus()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            container.addView(
                view, 0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView.visibility = View.GONE
        }

        override fun onHideCustomView() = exitFullscreen()

        // Lets sites with protected (DRM / Widevine) video play.
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            if (request.resources.contains(drm)) request.grant(arrayOf(drm)) else request.deny()
        }

        // Hides the grey "play" placeholder Android shows before a video loads.
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- page loading

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(request.url)

        @Deprecated("Needed for Fire OS 5 (Android 5.1)")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(Uri.parse(url))

        /** Web links stay in the app; anything else (app links, etc.) is handed to Fire OS. */
        private fun handleUrl(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
                return false
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                // No app can open it; ignore.
            }
            return true
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            if (request.isForMainFrame) showError(error.description.toString())
        }

        @Deprecated("Needed for Fire OS 5 (Android 5.1)")
        override fun onReceivedError(
            view: WebView, errorCode: Int, description: String?, failingUrl: String?
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showError(description ?: "")
        }
    }

    private fun showError(message: String) {
        val url = JSONObject.quote(startUrl)
        val html = """
            <html><head><meta name="viewport" content="width=device-width"></head>
            <body style="margin:0;height:100vh;background:#111;color:#eee;font-family:sans-serif;
                         display:flex;flex-direction:column;align-items:center;justify-content:center">
              <h1 style="font-weight:500">Can't reach the site</h1>
              <p style="color:#aaa">${TextUtils.htmlEncode(message)}</p>
              <button id="r" style="font-size:28px;padding:14px 40px;border-radius:8px;border:0">Retry</button>
              <script>
                var go = function(){ location.href = $url; };
                document.getElementById('r').onclick = go;
                document.getElementById('r').focus();
                document.onkeydown = function(e){ if (e.keyCode === 13 || e.keyCode === 23) go(); };
              </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        container.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}

/** The on-screen pointer: a white dot with a dark ring that turns blue while "clicking". */
class CursorView(context: Context) : View(context) {
    var cx = 0f
        private set
    var cy = 0f
        private set

    private val d = context.resources.displayMetrics.density
    private val radius = 10f * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun moveTo(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    fun setPressedLook(pressed: Boolean) {
        fill.color = if (pressed) Color.rgb(66, 165, 245) else Color.WHITE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, ring)
    }
}
