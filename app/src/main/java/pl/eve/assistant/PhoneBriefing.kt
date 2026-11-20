package pl.eve.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Przywitanie i krótki przegląd na telefonie (gdy PC jest wyłączony) - tak jak na PC:
 * powitanie od razu, potem pogoda, sprawy na dziś, poczta, propozycja i pytanie na koniec.
 */
class PhoneBriefing(ctx: Context, private val store: LocalStore, private val brain: PhoneBrain, private val tools: PhoneTools) {
    private val sp = ctx.getSharedPreferences("eve_briefing", Context.MODE_PRIVATE)

    private val morning = listOf(  // 04:00 - 17:30
        "Dzień dobry, {u}. Systemy rozgrzane, kawa niestety wciąż po Pana stronie.",
        "Dzień dobry, {u}. Dobrze Pana widzieć. Zanim podbijemy świat - kawa?",
        "Witam, {u}. Dzień zapowiada się obiecująco, przynajmniej według moich obliczeń.",
        "Dzień dobry, {u}. Melduję gotowość. Kawa z mlekiem czy dzisiaj na ostro, czarna?",
        "Dzień dobry, {u}. Tym razem w wersji kieszonkowej, ale w pełni sprawna.",
        "Dzień dobry, {u}. Komputer śpi, ale ja nie. Raport w drodze.")
    private val evening = listOf(  // 17:30 - 24:00
        "Dobry wieczór, {u}. Pora, o której normalni ludzie odpoczywają. My oczywiście dopiero zaczynamy.",
        "Dobry wieczór, {u}. Wieczorna kawa? Nie oceniam, tylko pytam.",
        "Witam wieczorową porą, {u}. Dzień prawie za nami, ale noc jest młoda.",
        "Dobry wieczór, {u}. Kawa po siedemnastej to podobno zły pomysł. Podobno.")
    private val night = listOf(  // 00:00 - 04:00
        "Witam, {u}. Widzę, że jak zwykle nie ma Pan zamiaru położyć się spać. Mogę zaproponować kawę?",
        "{u}, jest środek nocy. Nie pytam, co Pan robi, pytam tylko: espresso czy podwójne espresso?",
        "Witam w nocnej zmianie, {u}. Sowy pozdrawiają, ekspres czeka.",
        "{u}, o tej porze rozsądni ludzie śpią. Na szczęście my jesteśmy ciekawsi. Kawa?")
    private val closing = listOf(
        "Ma Pan dla mnie jakieś zadanie na już? Mogę też puścić jakąś piosenkę albo coś sprawdzić.",
        "To tyle ode mnie. Jakieś zadanie na teraz, muzyka, czy coś do sprawdzenia?",
        "Jestem do dyspozycji. Muzyka, sprawy, pytania - proszę wybierać.",
        "Czym mogę się zająć na start? Może jakaś piosenka do kawy?")

    private fun pick(list: List<String>, key: String): String {
        val last = sp.getString(key, "")
        val g = list.filter { it != last }.ifEmpty { list }.random()
        sp.edit().putString(key, g).apply()
        return g
    }

    fun greeting(): String {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val pool = if (m < 4 * 60) night else if (m < 17 * 60 + 30) morning else evening
        return pick(pool, "last_greeting").replace("{u}", store.sync?.optString("user_name", "Sir") ?: "Sir")
    }

    fun closing() = pick(closing, "last_closing")

    /** Czy pora na przegląd (nie częściej niż co 30 min). */
    fun due(): Boolean = System.currentTimeMillis() - sp.getLong("last", 0) > 30 * 60_000L

    fun build(): String {
        sp.edit().putLong("last", System.currentTimeMillis()).apply()
        val s = store.sync ?: JSONObject()
        val data = JSONObject()
        data.put("pogoda", tools.run("get_weather", JSONObject().put("days", 1)))
        if (s.has("google")) {
            data.put("kalendarz_dzis_jutro", tools.run("calendar_events", JSONObject().put("days", 2)))
            data.put("poczta_ostatnie_5", tools.run("gmail_inbox", JSONObject().put("query", "in:inbox category:primary").put("count", 5)))
        }
        if (s.has("spotify")) data.put("spotify", tools.run("spotify_now", JSONObject()))
        val past = JSONArray(sp.getString("suggestions", "[]"))
        val now = SimpleDateFormat("EEEE, d.MM.yyyy, HH:mm", Locale("pl", "PL")).format(Date())
        val prompt = """Użytkownik właśnie otworzył EVE na telefonie (komputer jest wyłączony). Powitanie już padło - NIE witaj się.
Teraz: $now.
DANE: ${data.toString().take(6000)}
PROPOZYCJE Z POPRZEDNICH RAZY (nie powtarzaj): $past

Powiedz krótko (razem maks. ok. 80 słów, mówione zdania, bez list):
1. Pogoda teraz i na resztę dnia - czy wziąć kurtkę albo parasol.
2. Sprawy na dziś: wydarzenia z kalendarza i rzeczy z pamięci, które dotyczą dziś. Jeśli nic - pomiń.
3. Poczta: NIE czytaj maili, powiedz tylko, czy jest coś ważnego (od kogo i w jednym słowie o co chodzi). Jeśli brak danych - pomiń.
4. Jedna konkretna propozycja na dziś dopasowana do pogody, dnia tygodnia, pory i zainteresowań (użytkownik uwielbia kawę - czasem możesz to wpleść).
Nie pytaj na końcu o zadania. W ostatniej osobnej linii dopisz: PROPOZYCJA: <3-6 słów>"""
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", brain.systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val text = brain.call(msgs, false).optString("content").trim()
        val lines = text.lines().toMutableList()
        val tag = lines.lastOrNull { it.trim().uppercase().startsWith("PROPOZYCJA:") }
        if (tag != null) {
            lines.remove(tag)
            past.put(tag.substringAfter(":").trim())
            val keep = JSONArray(); for (i in maxOf(0, past.length() - 15) until past.length()) keep.put(past.get(i))
            sp.edit().putString("suggestions", keep.toString()).apply()
        }
        return lines.joinToString(" ").trim()
    }
}
