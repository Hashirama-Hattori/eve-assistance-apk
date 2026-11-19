package pl.eve.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** Pełny panel EVE (ten sam co w przeglądarce) wewnątrz aplikacji. */
class PanelActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF04070C.toInt())
        val web = WebView(this)
        root.addView(web)
        setContentView(root)
        // nie wchodź pod pasek stanu i przyciski systemowe
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.userAgentString = web.settings.userAgentString + " EVEApp"
        // most: dotknięcie rdzenia w panelu = nasłuch aplikacji (mikrofon strony http jest zablokowany)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun listen() {
                runOnUiThread {
                    if (EveService.isRunning) {
                        startService(Intent(this@PanelActivity, EveService::class.java).setAction(EveService.ACTION_LISTEN_NOW))
                    } else {
                        ContextCompat.startForegroundService(this@PanelActivity, Intent(this@PanelActivity, EveService::class.java))
                        Toast.makeText(this@PanelActivity, "Włączam nasłuch – za chwilę powiedz „Iwi, …”", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "EVEApp")
        var injected = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (!injected) {   // kod dostępu z ustawień aplikacji - bez wpisywania
                    injected = true
                    val t = JSONObject.quote(prefs.token)
                    view.evaluateJavascript("localStorage.setItem('eve_token', $t); location.reload();", null)
                }
            }
        }
        web.loadUrl(prefs.serverUrl)
    }
}
