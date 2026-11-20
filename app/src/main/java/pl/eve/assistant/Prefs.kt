package pl.eve.assistant

import android.content.Context

/** Ustawienia aplikacji (adres EVE, kod dostępu, słowa wywołania). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("eve", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "http://192.168.1.107:8000") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim().uppercase()).apply()

    /** Warianty słowa "EVE" tak, jak słyszy je polski model mowy (oddzielone przecinkami). */
    var wakeWords: String
        get() = sp.getString("wake", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString("wake", v).apply()

    /** Dopasowanie "po brzmieniu" (i wi, iwie, ewy, i wy...). */
    var fuzzyWake: Boolean
        get() = sp.getBoolean("fuzzy", true)
        set(v) = sp.edit().putBoolean("fuzzy", v).apply()

    /** Gdzie EVE odpowiada głosem: "phone" (telefon) albo "pc" (głośniki komputera). */
    var speakOn: String
        get() = sp.getString("speak_on", "phone") ?: "phone"
        set(v) = sp.edit().putString("speak_on", v).apply()

    fun toggleSpeakOn(): String {
        speakOn = if (speakOn == "pc") "phone" else "pc"
        return speakOn
    }

    /** Ciągła rozmowa: po "Iwi" słucham dalej bez słowa-klucza, aż do 20 min ciszy. */
    var conversation: Boolean
        get() = sp.getBoolean("conversation", true)
        set(v) = sp.edit().putBoolean("conversation", v).apply()

    var conversationMinutes: Int
        get() = sp.getInt("conversation_minutes", 20)
        set(v) = sp.edit().putInt("conversation_minutes", v).apply()

    fun conversationLabel(): String = if (conversation) "💬 Ciągła rozmowa: WŁ" else "💬 Ciągła rozmowa: WYŁ"

    /** Wzmocnienie mikrofonu (1-4x) - żeby EVE słyszała z drugiego końca pokoju. */
    var micGain: Float
        get() = sp.getFloat("mic_gain", 2.5f)
        set(v) = sp.edit().putFloat("mic_gain", v).apply()

    fun nextMicGain(): Float {
        val steps = floatArrayOf(1f, 1.5f, 2.5f, 3.5f, 5f)
        val i = steps.indexOfFirst { it > micGain + 0.01f }
        micGain = if (i < 0) steps[0] else steps[i]
        return micGain
    }

    fun micLabel(): String = "🎚 Czułość mikrofonu: " + when {
        micGain < 1.2f -> "normalna"; micGain < 2f -> "podwyższona"; micGain < 3f -> "wysoka"
        micGain < 4f -> "bardzo wysoka"; else -> "maksymalna" } + " (${micGain}x)"

    fun speakLabel(): String = if (speakOn == "pc") "🖥 Odpowiada: KOMPUTER" else "📱 Odpowiada: TELEFON"

    fun wakeList(): List<String> =
        wakeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .sortedByDescending { it.length } // najpierw dłuższe ("i wi" przed "wi")

    companion object {
        const val DEFAULT_WAKE = "iwi, i wi, iwie, i wie, ewi, ewe, iwy, i wy, eve"
    }
}
