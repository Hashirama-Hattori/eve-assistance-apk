package pl.eve.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var log: TextView
    private lateinit var live: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val ui = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val server = findViewById<EditText>(R.id.server)
        val token = findViewById<EditText>(R.id.token)
        val wake = findViewById<EditText>(R.id.wake)
        log = findViewById(R.id.log)
        live = findViewById(R.id.live)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        server.setText(prefs.serverUrl)
        token.setText(prefs.token)
        wake.setText(prefs.wakeWords)
        findViewById<TextView>(R.id.version).text = "v" + packageManager.getPackageInfo(packageName, 0).versionName

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.serverUrl = server.text.toString()
            prefs.token = token.text.toString()
            prefs.wakeWords = wake.text.toString().ifBlank { Prefs.DEFAULT_WAKE }
            wake.setText(prefs.wakeWords)
            addLog("Zapisano. Sprawdzam połączenie…")
            ui.launch {
                val err = withContext(Dispatchers.IO) { EveApi(prefs).check() }
                addLog(err?.let { "⚠ $it" } ?: "✔ Połączono z EVE")
            }
        }
        findViewById<Button>(R.id.resetWake).setOnClickListener {
            wake.setText(Prefs.DEFAULT_WAKE)
        }
        toggle.setOnClickListener {
            if (EveService.isRunning) {
                startService(Intent(this, EveService::class.java).setAction(EveService.ACTION_STOP))
            } else if (askPermissions()) {
                ContextCompat.startForegroundService(this, Intent(this, EveService::class.java))
            }
            toggle.postDelayed({ refreshToggle() }, 400)
        }
        findViewById<Button>(R.id.talk).setOnClickListener {
            if (!EveService.isRunning) { Toast.makeText(this, "Najpierw włącz nasłuch", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startService(Intent(this, EveService::class.java).setAction(EveService.ACTION_LISTEN_NOW))
        }
        val speakOn = findViewById<Button>(R.id.speakOn)
        speakOn.text = prefs.speakLabel()
        speakOn.setOnClickListener {
            prefs.toggleSpeakOn()
            speakOn.text = prefs.speakLabel()
            addLog(if (prefs.speakOn == "pc") "Odpowiedzi głosem na komputerze" else "Odpowiedzi głosem na telefonie")
            if (EveService.isRunning) startService(Intent(this, EveService::class.java).setAction(EveService.ACTION_REFRESH))
        }
        val conv = findViewById<Button>(R.id.conversation)
        conv.text = prefs.conversationLabel()
        conv.setOnClickListener {
            prefs.conversation = !prefs.conversation
            conv.text = prefs.conversationLabel()
            addLog(if (prefs.conversation) "Ciągła rozmowa włączona: po „Iwi” mów bez słowa-klucza przez 20 min"
                   else "Ciągła rozmowa wyłączona: każde polecenie zaczynaj od „Iwi”")
        }
        val mic = findViewById<Button>(R.id.micGain)
        mic.text = prefs.micLabel()
        mic.setOnClickListener {
            prefs.nextMicGain()
            mic.text = prefs.micLabel()
            addLog("Czułość mikrofonu: ${prefs.micGain}x (działa od razu)")
        }
        findViewById<Button>(R.id.panel).setOnClickListener {
            startActivity(Intent(this, PanelActivity::class.java))
        }
        val input = findViewById<EditText>(R.id.input)
        findViewById<Button>(R.id.send).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            addLog("Ty: $text")
            ui.launch {
                try {
                    val r = withContext(Dispatchers.IO) { EveApi(prefs).sendText(text) }
                    addLog("EVE: ${r.text}")
                } catch (e: Exception) { addLog("⚠ ${e.message}") }
            }
        }
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        synchronized(EveBus.history) { log.text = EveBus.history.joinToString("\n") }
        scrollDown()
        status.text = EveBus.lastStatus
        findViewById<Button>(R.id.speakOn).text = prefs.speakLabel()  // mogło się zmienić z powiadomienia
        refreshToggle()
        EveBus.listener = { kind, text ->
            when (kind) {
                "log" -> addLog(text, fromBus = true)
                "partial" -> live.text = "słyszę: $text"
                "heard" -> live.text = "(nie do EVE) $text"
                "status" -> { status.text = text; refreshToggle() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        EveBus.listener = null
    }

    private fun refreshToggle() {
        toggle.text = if (EveService.isRunning) "Wyłącz nasłuch" else "Włącz nasłuch „Iwi”"
    }

    private fun addLog(s: String, fromBus: Boolean = false) {
        if (!fromBus) EveBus.log(s) else {
            log.append((if (log.text.isEmpty()) "" else "\n") + s)
            scrollDown()
        }
    }

    private fun scrollDown() = (log.parent as ScrollView).post { (log.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }

    private fun askPermissions(): Boolean {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        return false
    }
}
