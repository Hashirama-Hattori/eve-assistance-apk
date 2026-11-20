package pl.eve.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mózg EVE działający w samym telefonie (gdy PC jest wyłączony): te same modele i klucze co na PC,
 * rozmowa z funkcjami (pogoda, internet, Spotify, Gmail, kalendarz, pamięć, aplikacje).
 */
class PhoneBrain(private val store: LocalStore, private val tools: PhoneTools) {

    private val cooldown = HashMap<String, Long>()
    var lastBrain = ""
        private set

    private fun systemPrompt(): String {
        val s = store.sync ?: JSONObject()
        val mem = store.localMemories()
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, 'godzina' HH:mm", Locale("pl", "PL")).format(Date())
        return s.optString("persona") +
            "\n\nTERAZ DZIAŁASZ W TELEFONIE użytkownika, bo komputer jest wyłączony albo poza zasięgiem. " +
            "Nie masz teraz sterowania komputerem ani jego plików - jeśli ktoś o to prosi, powiedz krótko, że zrobisz to, gdy PC będzie włączony. " +
            "Masz za to: pogodę, internet, pamięć" + (if (s.has("spotify")) ", Spotify" else "") +
            (if (s.has("google")) ", Gmail i kalendarz" else "") + " oraz otwieranie aplikacji na telefonie." +
            "\nAktualny czas: $now. Miasto użytkownika: ${s.optString("home_city")}." +
            s.optString("memory") +
            (if (mem.isNotEmpty()) "\nZapisane na telefonie:\n" + mem.joinToString("\n") { "- $it" } else "")
    }

    private fun call(messages: JSONArray, withTools: Boolean, maxTokens: Int? = null): JSONObject {
        val providers = store.sync?.optJSONArray("providers") ?: throw IllegalStateException("Brak danych z PC")
        var last: Exception? = null
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val name = p.getString("name")
            if (System.currentTimeMillis() < (cooldown[name] ?: 0)) continue
            try {
                val body = JSONObject().put("model", p.getString("model")).put("messages", messages)
                if (withTools) body.put("tools", tools.schemas()).put("tool_choice", "auto")
                if (!p.isNull("temperature")) body.put("temperature", p.getDouble("temperature"))
                if (!p.isNull("max_tokens")) body.put("max_tokens", p.getInt("max_tokens"))
                if (maxTokens != null && !p.getString("model").contains("gpt-oss")) body.put("max_tokens", maxTokens)
                p.optJSONObject("params")?.let { pr -> pr.keys().forEach { k -> body.put(k, pr.get(k)) } }
                val url = p.getString("base_url").trimEnd('/') + "/chat/completions"
                val r = Net.postJson(url, body, mapOf("Authorization" to "Bearer ${p.getString("api_key")}"), 60000)
                lastBrain = name
                return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: Exception) {
                last = e
                cooldown[name] = System.currentTimeMillis() + 60_000
                EveBus.log("⚠ $name: ${e.message?.take(80)}")
            }
        }
        throw IllegalStateException("Żaden mózg nie odpowiedział (${last?.message?.take(80)})")
    }

    /** Odpowiedź na polecenie (z funkcjami). */
    fun ask(text: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val h = store.history()
        for (i in 0 until h.length()) msgs.put(h.get(i))
        msgs.put(JSONObject().put("role", "user").put("content", text))
        repeat(5) {
            val m = call(msgs, withTools = true)
            val calls = m.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val reply = m.optString("content").trim()
                store.addExchange(text, reply)
                return reply
            }
            msgs.put(m)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val f = c.getJSONObject("function")
                val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                EveBus.log("🔧 ${f.getString("name")}")
                val res = tools.run(f.getString("name"), args)
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", res.take(6000)))
            }
        }
        return "Nie dałam rady tego skończyć w kilku krokach."
    }

    /** Tryb ciągłej rozmowy: czy usłyszane zdanie jest do EVE? */
    fun isForEve(heard: String): Boolean {
        val t = heard.trim()
        if (t.length < 2) return false
        if (Regex("(?i)\\b(iwi|ewi|eve|edit|edith|edyta?|jarvis\\w*|jarwis\\w*)\\b").containsMatchIn(t)) return true
        val h = store.history()
        val lastReply = if (h.length() > 0) h.getJSONObject(h.length() - 1).optString("content") else ""
        val lastUser = if (h.length() > 1) h.getJSONObject(h.length() - 2).optString("content") else ""
        if (lastReply.isNotEmpty() && lastReply.lowercase().contains(t.lowercase())) return false  // echo
        if (lastReply.trim().endsWith("?") && t.split(" ").size <= 4) return true
        val prompt = "Mikrofon telefonu słyszy wszystko w pokoju. Ostatnie pytanie użytkownika do asystentki: \"${lastUser.take(200)}\". " +
            "Jej odpowiedź: \"${lastReply.take(300)}\". Nowa wypowiedź: \"${t.take(300)}\". " +
            "Czy jest skierowana DO asystentki (polecenie, pytanie, dalszy ciąg rozmowy z nią)? Odpowiedz jednym słowem: TAK albo NIE."
        return try {
            call(JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), false, 3)
                .optString("content").trim().uppercase().startsWith("TAK")
        } catch (e: Exception) { true }
    }
}
