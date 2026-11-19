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

    fun speakLabel(): String = if (speakOn == "pc") "🖥 Odpowiada: KOMPUTER" else "📱 Odpowiada: TELEFON"

    fun wakeList(): List<String> =
        wakeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .sortedByDescending { it.length } // najpierw dłuższe ("i wi" przed "wi")

    companion object {
        const val DEFAULT_WAKE = "iwi, i wi, iwie, i wie, ewi, ewe, iwy, i wy, eve"
    }
}
