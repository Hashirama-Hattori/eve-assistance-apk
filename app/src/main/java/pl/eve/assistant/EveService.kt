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
    @Volatile private var convUntil = 0L         // ciągła rozmowa trwa do tej chwili (potem spoczynek)
    @Volatile private var resetRecognizer = false
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // praca bez PC
    private lateinit var store: LocalStore
    private lateinit var phoneBrain: PhoneBrain
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var pcOnline = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = EveApi(prefs)
        store = LocalStore(this)
        phoneBrain = PhoneBrain(store, PhoneTools(this, store))
        tts = android.speech.tts.TextToSpeech(this) { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) tts?.language = java.util.Locale("pl", "PL")
        }
        instance = this
    }

    /** Co 20 minut (i po każdym powrocie PC): świeże dane z PC + oddanie tego, co działo się bez niego. */
    private fun syncLoop() = scope.launch {
        while (running) {
            val err = store.pull(prefs)
            if (err == null) {
                store.push(prefs)
                if (!pcOnline) EveBus.log("🖥 PC znowu dostępny – wracam do pełnej EVE")
                pcOnline = true
            }
            kotlinx.coroutines.delay(20 * 60_000L)
        }
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
                syncLoop()
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
        // wbudowane wzmocnienie telefonu (jeśli ma) + nasze programowe - mowa z odległości jest cicha
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable())
                android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
        } catch (_: Exception) {}
        val recognizer = Recognizer(m, rate.toFloat())
        val buf = ByteArray(4096)
        val utterance = ByteArrayOutputStream()
        var lastPartial = ""
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                amplify(buf, n, prefs.micGain)
                if (convUntil > 0 && System.currentTimeMillis() > convUntil) endConversation("20 minut ciszy")
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

    /** Programowe wzmocnienie z miękkim ogranicznikiem (głośne dźwięki nie przesterowują). */
    private fun amplify(b: ByteArray, n: Int, gain: Float) {
        if (gain <= 1.01f) return
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort() / 32768f
            var v = s * gain
            v = if (v > 0.6f) 0.6f + (1 - 0.6f) * kotlin.math.tanh((v - 0.6f) / 0.4f)
                else if (v < -0.6f) -0.6f - (1 - 0.6f) * kotlin.math.tanh((-v - 0.6f) / 0.4f) else v
            val o = (v * 32767).toInt().coerceIn(-32768, 32767)
            b[i] = (o and 0xFF).toByte(); b[i + 1] = ((o shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** Wyrównanie głośności całego zdania przed wysłaniem (do ok. 90% skali, maks. 4x). */
    private fun normalize(pcm: ByteArray): ByteArray {
        var peak = 1
        var i = 0
        while (i + 1 < pcm.size) {
            val s = kotlin.math.abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt())
            if (s > peak) peak = s
            i += 2
        }
        val g = minOf(4f, 0.9f * 32767 / peak)
        if (g in 0.95f..1.05f) return pcm
        val out = pcm.copyOf()
        i = 0
        while (i + 1 < out.size) {
            val s = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)).toShort()
            val o = (s * g).toInt().coerceIn(-32768, 32767)
            out[i] = (o and 0xFF).toByte(); out[i + 1] = ((o shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /** Całe zdanie usłyszane offline. Decydujemy, czy to było do EVE. */
    private fun onUtterance(text: String, audio: ByteArray) {
        val now = System.currentTimeMillis()
        val wake = findWake(text)
        when {
            wake != null && wake.second.length >= 3 -> {
                EveBus.log("🎙 $text")
                startConversation()
                send(audio)
            }
            wake != null -> {               // samo "Iwi" - czekamy na polecenie
                EveBus.log("🎙 (Iwi) słucham…")
                beep()
                startConversation()
                awaitUntil = now + 8000
                resetRecognizer = true
            }
            now < awaitUntil -> {
                awaitUntil = 0
                EveBus.log("🎙 $text")
                send(audio)
            }
            inConversation() -> send(audio, conversation = true, heard = text)  // serwer oceni, czy to do EVE
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

    // ---------- ciągła rozmowa ----------

    private fun inConversation() = convUntil > System.currentTimeMillis()

    private fun startConversation() {
        if (!prefs.conversation) return
        val first = !inConversation()
        convUntil = System.currentTimeMillis() + prefs.conversationMinutes * 60_000L
        if (first) EveBus.log("💬 Rozmowa – mów śmiało, bez „Iwi” (spoczynek po ${prefs.conversationMinutes} min ciszy)")
    }

    private fun endConversation(why: String) {
        if (convUntil == 0L) return
        convUntil = 0
        EveBus.log("💤 Spoczynek ($why). Powiedz „Iwi”, żeby zacząć znowu.")
        notify(idleText())
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 50).startTone(ToneGenerator.TONE_PROP_NACK, 160) } catch (_: Exception) {}
    }

    private fun idleText() = if (inConversation()) "Rozmowa trwa – mów śmiało" else "Słucham – powiedz „Iwi”"

    private fun send(audio: ByteArray, conversation: Boolean = false, heard: String = "") {
        busy = true
        if (!conversation) notify("Myślę…")
        scope.launch {
            try {
                val r = api.sendVoice(normalize(audio), conversation, heard)
                if (r.ignored) {            // to nie było do EVE - słuchamy dalej po cichu
                    EveBus.heard("$heard  (nie do mnie)")
                    busy = false
                    return@launch
                }
                startConversation()         // każda rozmowa z EVE przedłuża czas do spoczynku
                if (!pcOnline) {            // PC wrócił - oddajemy mu rozmowy z czasu bez niego
                    pcOnline = true
                    EveBus.log("🖥 PC znowu dostępny – wracam do pełnej EVE")
                    scope.launch { if (store.pull(prefs) == null) store.push(prefs) }
                }
                if (r.action == "end_conversation") endConversation("na Twoje polecenie")
                if (r.action == "stop") stopSpeaking()
                if (r.user.isNotEmpty()) EveBus.log("Ty: ${r.user}")
                if (r.text.isNotEmpty()) EveBus.log("EVE: ${r.text}" + if (r.spokenOn == "pc") "  (🖥 mówi na PC)" else "")
                notify(r.text.ifEmpty { idleText() })
                when {
                    r.audio != null -> play(r.audio)
                    // EVE mówi z głośników PC - nie słuchamy, dopóki mówi (inaczej telefon usłyszy jej głos)
                    r.spokenOn == "pc" -> main.postDelayed({ done() }, (r.speakSeconds * 1000).toLong())
                    else -> done()
                }
            } catch (e: java.io.IOException) {
                // PC wyłączony / poza zasięgiem - EVE działa dalej w telefonie
                if (pcOnline) EveBus.log("📱 PC niedostępny – działam w telefonie")
                pcOnline = false
                offline(audio, conversation, heard)
            } catch (e: Exception) {
                EveBus.log("⚠ ${e.message}")
                notify("Błąd EVE")
                done()
            }
        }
    }

    // ---------- praca bez PC ----------

    private fun transcribe(audio: ByteArray): String? {
        val stt = store.sync?.optJSONObject("stt") ?: return null
        val key = stt.optString("api_key")
        if (key.isBlank()) return null
        return try {
            val r = Net.multipartAudio("https://api.openai.com/v1/audio/transcriptions", EveApi.pcmToWav(audio, 16000),
                mapOf("model" to stt.optString("model", "gpt-4o-mini-transcribe"), "language" to "pl",
                    "prompt" to "Rozmowa z asystentką EVE (Iwi, Edit, Jarvis). Spotify, playlista, polubione, pogoda, Gmail, kalendarz."),
                mapOf("Authorization" to "Bearer $key"))
            org.json.JSONObject(r).optString("text").trim().ifBlank { null }
        } catch (e: Exception) { null }
    }

    private suspend fun offline(audio: ByteArray, conversation: Boolean, heard: String) {
        if (!store.hasSync()) {
            EveBus.log("⚠ PC niedostępny, a telefon nie ma jeszcze danych do pracy samodzielnej. Połącz się raz z PC (ta sama sieć Wi-Fi).")
            notify("Brak połączenia z PC")
            done(); return
        }
        val text = transcribe(audio) ?: heard.ifBlank { null }
        if (text == null) { done(); return }
        if (conversation && !phoneBrain.isForEve(text)) {
            EveBus.heard("$text  (nie do mnie)")
            busy = false
            return
        }
        val low = text.lowercase()
        if (Regex("^(stop|cisza|przestań|wystarczy|dość)[.!]?$").matches(low)) { stopSpeaking(); done(); return }
        startConversation()
        EveBus.log("Ty: $text")
        if (Regex("(to wszystko|koniec rozmowy|kończymy|idź spać|dobranoc)").containsMatchIn(low)) {
            endConversation("na Twoje polecenie"); speakLocal("Dobrze, Sir. Będę w pogotowiu."); return
        }
        notify("Myślę (w telefonie)…")
        val reply = try { phoneBrain.ask(text) } catch (e: Exception) { "Nie mogę teraz odpowiedzieć: ${e.message}" }
        EveBus.log("EVE 📱: $reply")
        notify(reply)
        speakLocal(reply)
    }

    /** Głos w telefonie (bez PC): systemowy syntezator mowy, polski; dla Jarvisa niżej. */
    private fun speakLocal(text: String) {
        val t = tts
        if (t == null || text.isBlank()) { done(); return }
        val male = store.sync?.optBoolean("male") == true
        t.setPitch(if (male) 0.8f else 1.05f)
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done() }
            @Deprecated("old API") override fun onError(id: String?) { done() }
        })
        t.speak(text.replace(Regex("[*#_`]"), ""), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "eve")
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
            notify(idleText())
        }, 300)
    }

    private fun stopSpeaking() = main.post {
        try { player?.stop() } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun listenNow() {
        if (busy) { stopSpeaking(); done() }
        beep()
        startConversation()
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
