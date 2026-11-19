package pl.eve.assistant

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Pełny panel EVE (ten sam co w przeglądarce) wewnątrz aplikacji. */
class PanelActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
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
