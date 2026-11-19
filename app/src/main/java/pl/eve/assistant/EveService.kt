package pl.eve.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Usługa w tle: słucha bez przerwy (także przy wygaszonym ekranie), wykrywa "Iwi"
 * offline (Vosk, polski model), a całe zdanie wysyła do EVE, która odpowiada głosem.
 */
class EveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var api: EveApi
    private var model: Model? = null
    @Volatile private var running = false
    @Volatile private var busy = false            // EVE myśli albo mówi - nie słuchamy siebie
    @Volatile private var awaitUntil = 0L        // po samym "Iwi" czekamy na polecenie
    @Volatile private var resetRecognizer = false
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = EveApi(prefs)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LISTEN_NOW) {
            listenNow()
            return START_STICKY
        }
        if (intent?.action == ACTION_SPEAK_TOGGLE || intent?.action == ACTION_REFRESH) {
            if (intent.action == ACTION_SPEAK_TOGGLE) {
                prefs.toggleSpeakOn()
                EveBus.log(if (prefs.speakOn == "pc") "Odpowiedzi głosem na komputerze" else "Odpowiedzi głosem na telefonie")
            }
            if (running) notify(lastNotif)
            return START_STICKY
        }
        startForegroundCompat("Uruchamiam…")
        if (!running) start()
        return START_STICKY
    }

    private fun start() {
        running = true
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EVE:listen").apply { acquire() }
        EveBus.log("Ładuję model mowy (pierwszy raz chwilę to potrwa)…")
        StorageService.unpack(this, "model-pl", "model",
            { m ->
                model = m
                EveBus.log("Słucham. Powiedz: „Iwi, …”")
                notify("Słucham – powiedz „Iwi”")
                Thread({ loop(m) }, "eve-mic").start()
            },
            { e ->
                EveBus.log("Błąd modelu mowy: ${e.message}")
                notify("Błąd modelu mowy")
            })
    }

    @SuppressLint("MissingPermission")
    private fun loop(m: Model) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            EveBus.log("Brak zgody na mikrofon")
            return
        }
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        val recognizer = Recognizer(m, rate.toFloat())
        val buf = ByteArray(4096)
        val utterance = ByteArrayOutputStream()
        var lastPartial = ""
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (busy) { utterance.reset(); continue }
                if (resetRecognizer) { recognizer.reset(); utterance.reset(); resetRecognizer = false }
                utterance.write(buf, 0, n)
                if (utterance.size() > rate * 2 * 20) utterance.reset() // maks. 20 s na zdanie
                if (recognizer.acceptWaveForm(buf, n)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    val audio = utterance.toByteArray()
                    utterance.reset()
                    lastPartial = ""
                    if (text.isNotEmpty()) onUtterance(text, audio)
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial")
                    if (p.isNotEmpty() && p != lastPartial) {
                        lastPartial = p
                        EveBus.partial(p)
                    }
                }
            }
        } finally {
            rec.stop()
            rec.release()
            recognizer.close()
        }
    }

    /** Całe zdanie usłyszane offline. Decydujemy, czy to było do EVE. */
    private fun onUtterance(text: String, audio: ByteArray) {
        val now = System.currentTimeMillis()
        val wake = findWake(text)
        when {
            wake != null && wake.second.length >= 3 -> {
                EveBus.log("🎙 $text")
                send(audio)
            }
            wake != null -> {               // samo "Iwi" - czekamy na polecenie
                EveBus.log("🎙 (Iwi) słucham…")
                beep()
                awaitUntil = now + 8000
                resetRecognizer = true
            }
            now < awaitUntil -> {
                awaitUntil = 0
                EveBus.log("🎙 $text")
                send(audio)
            }
            else -> EveBus.heard(text)      // rozmowa nie do EVE - tylko podgląd
        }
    }

    /**
     * Szuka wywołania. Najpierw dokładne słowa z ustawień, potem dopasowanie "po brzmieniu":
     * polski model nie zna słowa "Iwi" i zapisuje je różnie ("i wi", "iwie", "ewy", "i wy", "hiwi"...).
     * Sprawdzamy 1-2 pierwsze słowa zdania sklejone razem. Zwraca (co usłyszał, reszta zdania).
     */
    private fun findWake(text: String): Pair<String, String>? {
        val t = " " + text.lowercase() + " "
        for (w in prefs.wakeList()) {
            val i = t.indexOf(" $w ")
            if (i >= 0) return w to t.substring(i + w.length + 2).trim()
        }
        if (!prefs.fuzzyWake) return null
        val words = text.lowercase().split(" ").filter { it.isNotBlank() }
        // pomijamy grzecznościowe "hej"/"ok" na początku
        val start = if (words.firstOrNull() in setOf("hej", "ej", "ok", "okej", "halo")) 1 else 0
        for (take in 2 downTo 1) {
            if (words.size < start + take) continue
            val joined = words.subList(start, start + take).joinToString("")
            if (soundsLikeIwi(joined)) {
                return joined to words.drop(start + take).joinToString(" ")
            }
        }
        return null
    }

    private val iwiSound = Regex("^h?[iyje]{1,2}w[iyje]{1,3}[ji]?$")

    private fun soundsLikeIwi(w: String): Boolean {
        if (w.length < 3 || w.length > 6) return false
        // "ewa", "iwo" itp. są zbyt częste w zwykłej mowie - nie traktujemy ich jako wywołania
        if (w in setOf("ewa", "ewo", "ewę", "wie", "wiej", "iwo")) return false
        return iwiSound.matches(w)
    }

    private fun send(audio: ByteArray) {
        busy = true
        notify("Myślę…")
        scope.launch {
            try {
                val r = api.sendVoice(audio)
                if (r.action == "stop") stopSpeaking()
                if (r.user.isNotEmpty()) EveBus.log("Ty: ${r.user}")
                if (r.text.isNotEmpty()) EveBus.log("EVE: ${r.text}" + if (r.spokenOn == "pc") "  (🖥 mówi na PC)" else "")
                notify(r.text.ifEmpty { "Słucham – powiedz „Iwi”" })
                if (r.audio != null) play(r.audio) else done()
            } catch (e: Exception) {
                EveBus.log("⚠ ${e.message}")
                notify("Brak połączenia z EVE")
                done()
            }
        }
    }

    private fun play(audio: ByteArray) {
        val f = File(cacheDir, "eve_odpowiedz.mp3")
        f.writeBytes(audio)
        main.post {
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { done() }
                    setOnErrorListener { _, _, _ -> done(); true }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                EveBus.log("⚠ Odtwarzanie: ${e.message}")
                done()
            }
        }
    }

    private fun done() {
        main.postDelayed({
            resetRecognizer = true
            busy = false
            notify("Słucham – powiedz „Iwi”")
        }, 300)
    }

    private fun stopSpeaking() = main.post { try { player?.stop() } catch (_: Exception) {} }

    fun listenNow() {
        if (busy) { stopSpeaking(); done() }
        beep()
        awaitUntil = System.currentTimeMillis() + 8000
        resetRecognizer = true
        EveBus.log("🎙 słucham…")
    }

    private fun beep() {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP2, 180) } catch (_: Exception) {}
    }

    // ---------- powiadomienie (wymagane dla usługi w tle) ----------

    private var lastNotif = "Słucham – powiedz „Iwi”"

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "EVE nasłuch", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 2, Intent(this, EveService::class.java).setAction(ACTION_LISTEN_NOW),
            PendingIntent.FLAG_IMMUTABLE)
        val where = PendingIntent.getService(this, 3, Intent(this, EveService::class.java).setAction(ACTION_SPEAK_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE)
        lastNotif = text
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eve)
            .setContentTitle("EVE")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Mów", talk)
            .addAction(0, if (prefs.speakOn == "pc") "Głos: PC" else "Głos: telefon", where)
            .addAction(0, "Wyłącz", stop)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        EveBus.status(text)
    }

    override fun onDestroy() {
        running = false
        instance = null
        scope.cancel()
        player?.release()
        try { wakeLock?.release() } catch (_: Exception) {}
        EveBus.log("Nasłuch wyłączony")
        EveBus.status("Wyłączona")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "eve"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "pl.eve.STOP"
        const val ACTION_LISTEN_NOW = "pl.eve.LISTEN"
        const val ACTION_SPEAK_TOGGLE = "pl.eve.SPEAK_TOGGLE"
        const val ACTION_REFRESH = "pl.eve.REFRESH"
        @Volatile var instance: EveService? = null
        val isRunning get() = instance != null
    }
}

/** Prosty kanał komunikatów usługa -> ekran aplikacji. */
object EveBus {
    var listener: ((kind: String, text: String) -> Unit)? = null
    val history = ArrayDeque<String>()
    var lastStatus = "Wyłączona"
    private val main = Handler(Looper.getMainLooper())

    fun log(s: String) {
        synchronized(history) {
            history.addLast(s)
            while (history.size > 60) history.removeFirst()
        }
        main.post { listener?.invoke("log", s) }
    }
    fun partial(s: String) = main.post { listener?.invoke("partial", s) }
    fun heard(s: String) = main.post { listener?.invoke("heard", s) }
    fun status(s: String) { lastStatus = s; main.post { listener?.invoke("status", s) } }
}
