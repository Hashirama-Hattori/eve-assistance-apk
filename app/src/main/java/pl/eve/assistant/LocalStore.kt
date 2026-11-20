package pl.eve.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dane do pracy bez PC: kopia kluczy, pamięci i połączeń kont pobrana z PC (/api/phone/sync)
 * oraz kolejka rzeczy, które telefon odda PC, gdy znowu będzie dostępny (/api/phone/push).
 * Wszystko w prywatnym schowku aplikacji (inne aplikacje nie mają do niego dostępu).
 */
class LocalStore(context: Context) {
    private val sp = context.getSharedPreferences("eve_offline", Context.MODE_PRIVATE)

    var sync: JSONObject?
        get() = sp.getString("sync", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = sp.edit().putString("sync", v?.toString()).apply()

    val lastSync: Long get() = sp.getLong("last_sync", 0)

    fun hasSync() = sync?.optJSONArray("providers")?.length() ?: 0 > 0

    // ---------- pamięć zapisana na telefonie (bez PC) ----------
    fun localMemories(): List<String> {
        val a = JSONArray(sp.getString("memories", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    @Synchronized
    fun addMemory(text: String) {
        val a = JSONArray(sp.getString("memories", "[]")).put(text)
        val q = JSONArray(sp.getString("q_mem", "[]")).put(text)
        sp.edit().putString("memories", a.toString()).putString("q_mem", q.toString()).apply()
    }

    // ---------- historia rozmowy na telefonie ----------
    fun history(): JSONArray = JSONArray(sp.getString("history", "[]"))

    @Synchronized
    fun addExchange(user: String, reply: String) {
        val h = history()
        h.put(JSONObject().put("role", "user").put("content", user))
        h.put(JSONObject().put("role", "assistant").put("content", reply))
        val trimmed = JSONArray()
        for (i in maxOf(0, h.length() - 16) until h.length()) trimmed.put(h.get(i))
        val q = JSONArray(sp.getString("q_conv", "[]"))
            .put(JSONObject().put("user", user).put("reply", reply).put("brain", "telefon"))
        sp.edit().putString("history", trimmed.toString()).putString("q_conv", q.toString()).apply()
    }

    /** Zaktualizowany token konta (Spotify/Google) - zapisujemy w kopii i oddamy PC. */
    @Synchronized
    fun saveToken(service: String, token: JSONObject) {
        val s = sync ?: return
        s.optJSONObject(service)?.put("token", token)
        sync = s
        sp.edit().putString("q_tok_$service", token.toString()).apply()
    }

    // ---------- wymiana z PC ----------

    /** Pobiera świeże dane z PC. Zwraca null, gdy się udało, inaczej opis problemu. */
    fun pull(prefs: Prefs): String? = try {
        val c = URL(prefs.serverUrl + "/api/phone/sync").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 15000
        c.setRequestProperty("X-Eve-Token", prefs.token)
        if (c.responseCode != 200) "PC odpowiedział ${c.responseCode}" else {
            val fresh = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            // jeśli telefon ma nowszy token konta niż PC - zostawiamy nowszy
            val old = sync
            for (svc in listOf("spotify", "google")) {
                val mine = old?.optJSONObject(svc)?.optJSONObject("token")
                val pcs = fresh.optJSONObject(svc)?.optJSONObject("token")
                if (mine != null && pcs != null && mine.optDouble("expires_at", 0.0) > pcs.optDouble("expires_at", 0.0))
                    fresh.getJSONObject(svc).put("token", mine)
            }
            // historia z PC staje się początkiem rozmowy na telefonie (ciągłość)
            val h = JSONArray()
            val ph = fresh.optJSONArray("history") ?: JSONArray()
            for (i in 0 until ph.length()) {
                val o = ph.getJSONObject(i)
                if (o.optString("user").isNotEmpty()) h.put(JSONObject().put("role", "user").put("content", o.optString("user")))
                if (o.optString("reply").isNotEmpty()) h.put(JSONObject().put("role", "assistant").put("content", o.optString("reply")))
            }
            sync = fresh
            sp.edit().putString("history", h.toString()).putLong("last_sync", System.currentTimeMillis()).apply()
            null
        }
    } catch (e: Exception) {
        e.javaClass.simpleName
    }

    /** Oddaje PC rozmowy, pamięć i tokeny z czasu bez PC. */
    @Synchronized
    fun push(prefs: Prefs): Boolean {
        val conv = JSONArray(sp.getString("q_conv", "[]"))
        val mem = JSONArray(sp.getString("q_mem", "[]"))
        val tokens = JSONObject()
        for (svc in listOf("spotify", "google")) sp.getString("q_tok_$svc", null)?.let { tokens.put(svc, JSONObject(it)) }
        if (conv.length() == 0 && mem.length() == 0 && tokens.length() == 0) return true
        return try {
            val c = URL(prefs.serverUrl + "/api/phone/push").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 4000; c.readTimeout = 15000
            c.setRequestProperty("X-Eve-Token", prefs.token)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("conversations", conv).put("memories", mem).put("tokens", tokens)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 200) {
                sp.edit().putString("q_conv", "[]").putString("q_mem", "[]")
                    .remove("q_tok_spotify").remove("q_tok_google").putString("memories", "[]").apply()
                true
            } else false
        } catch (e: Exception) { false }
    }
}
