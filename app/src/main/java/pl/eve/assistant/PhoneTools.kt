package pl.eve.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Funkcje EVE dostępne na telefonie, gdy PC jest wyłączony. */
class PhoneTools(private val ctx: Context, private val store: LocalStore) {

    private val sync get() = store.sync ?: JSONObject()

    // ---------- opisy funkcji dla modelu ----------

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    fun schemas(): JSONArray {
        val a = JSONArray()
        a.put(fn("get_time", "Aktualna data i godzina."))
        a.put(fn("get_weather", "Pogoda teraz i prognoza (do 7 dni).",
            JSONObject().put("city", p("string", "Miejscowość (domyślnie miasto użytkownika)")).put("days", p("integer", "Ile dni (1-7)"))))
        a.put(fn("web_search", "Wyszukuje aktualne informacje w internecie.", JSONObject().put("query", p("string", "Zapytanie")), listOf("query")))
        a.put(fn("read_webpage", "Czyta treść strony internetowej.", JSONObject().put("url", p("string", "Adres")), listOf("url")))
        a.put(fn("remember", "Zapisuje w pamięci długotrwałej informację, którą użytkownik każe zapamiętać.",
            JSONObject().put("text", p("string", "Co zapamiętać")), listOf("text")))
        a.put(fn("open_phone_app", "Otwiera aplikację na telefonie (np. YouTube, Mapy, Messenger, aparat).",
            JSONObject().put("name", p("string", "Nazwa aplikacji")), listOf("name")))
        if (sync.has("spotify")) {
            a.put(fn("spotify_play", "Puszcza na Spotify utwór, playlistę, album, wykonawcę albo polubione (kind=liked).",
                JSONObject().put("query", p("string", "Co puścić")).put("kind", p("string", "track, playlist, album, artist albo liked"))))
            a.put(fn("spotify_control", "Spotify: pause, resume, next, previous, shuffle_on, shuffle_off.",
                JSONObject().put("action", p("string", "Akcja")), listOf("action")))
            a.put(fn("spotify_volume", "Głośność Spotify 0-100 albo zmiana o +/-.",
                JSONObject().put("level", p("integer", "0-100")).put("change", p("integer", "np. 20 albo -20"))))
            a.put(fn("spotify_now", "Co teraz gra na Spotify."))
        }
        if (sync.has("google")) {
            a.put(fn("gmail_inbox", "Sprawdza Gmaila (bez zapytania: nowe nieprzeczytane w głównej skrzynce).",
                JSONObject().put("query", p("string", "Zapytanie Gmail (opcjonalnie)")).put("count", p("integer", "Ile maili"))))
            a.put(fn("calendar_events", "Wydarzenia z Kalendarza Google na najbliższe dni.",
                JSONObject().put("days", p("integer", "Ile dni"))))
            a.put(fn("calendar_add", "Dodaje wydarzenie do kalendarza. start: RRRR-MM-DDTHH:MM.",
                JSONObject().put("title", p("string", "Tytuł")).put("start", p("string", "Początek"))
                    .put("minutes", p("integer", "Czas trwania")), listOf("title", "start")))
        }
        return a
    }

    fun run(name: String, args: JSONObject): String = try {
        when (name) {
            "get_time" -> SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("pl", "PL")).format(Date())
            "get_weather" -> weather(args.optString("city").ifBlank { sync.optString("home_city", "Warszawa").ifBlank { "Warszawa" } },
                args.optInt("days", 1))
            "web_search" -> search(args.getString("query"))
            "read_webpage" -> Net.stripHtml(Net.get(args.getString("url").let { if (it.startsWith("http")) it else "https://$it" })).take(7000)
            "remember" -> { store.addMemory(args.getString("text")); "Zapisano w pamięci." }
            "open_phone_app" -> openApp(args.getString("name"))
            "spotify_play" -> spotifyPlay(args.optString("query"), args.optString("kind", "track"))
            "spotify_control" -> spotifyControl(args.getString("action"))
            "spotify_volume" -> spotifyVolume(if (args.has("level")) args.getInt("level") else null,
                if (args.has("change")) args.getInt("change") else null)
            "spotify_now" -> spotifyNow()
            "gmail_inbox" -> gmail(args.optString("query"), args.optInt("count", 8))
            "calendar_events" -> events(args.optInt("days", 7))
            "calendar_add" -> addEvent(args.getString("title"), args.getString("start"), args.optInt("minutes", 60))
            else -> "Nieznana funkcja $name"
        }
    } catch (e: Net.HttpError) {
        "Błąd ${e.code}: ${e.body.take(200)}"
    } catch (e: Exception) {
        "Błąd: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
    }

    // ---------- pogoda (Open-Meteo, bez klucza) ----------

    private val wmo = mapOf(0 to "bezchmurnie", 1 to "przeważnie słonecznie", 2 to "częściowe zachmurzenie", 3 to "pochmurno",
        45 to "mgła", 48 to "mgła", 51 to "lekka mżawka", 53 to "mżawka", 55 to "gęsta mżawka", 61 to "słaby deszcz",
        63 to "deszcz", 65 to "ulewa", 71 to "słaby śnieg", 73 to "śnieg", 75 to "intensywny śnieg", 80 to "przelotny deszcz",
        81 to "przelotne opady", 82 to "gwałtowne ulewy", 85 to "przelotny śnieg", 95 to "burza", 96 to "burza z gradem")

    private fun weather(city: String, days: Int): String {
        val geo = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=pl&format=json&name=" + Net.enc(city)))
            .optJSONArray("results")?.optJSONObject(0) ?: return "Nie znalazłam miejscowości $city."
        val d = days.coerceIn(1, 7)
        val w = JSONObject(Net.get("https://api.open-meteo.com/v1/forecast?latitude=${geo.getDouble("latitude")}&longitude=${geo.getDouble("longitude")}" +
            "&timezone=auto&forecast_days=$d&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code"))
        val c = w.getJSONObject("current"); val dl = w.getJSONObject("daily")
        val sb = StringBuilder("${geo.getString("name")}: teraz ${c.getDouble("temperature_2m").toInt()}°C " +
            "(odczuwalna ${c.getDouble("apparent_temperature").toInt()}), ${wmo[c.getInt("weather_code")] ?: "?"}, " +
            "wiatr ${c.getDouble("wind_speed_10m").toInt()} km/h.")
        val t = dl.getJSONArray("time")
        for (i in 0 until t.length()) sb.append("\n${t.getString(i)}: ${dl.getJSONArray("temperature_2m_min").getDouble(i).toInt()}" +
            "..${dl.getJSONArray("temperature_2m_max").getDouble(i).toInt()}°C, ${wmo[dl.getJSONArray("weather_code").getInt(i)] ?: "?"}, " +
            "opady ${dl.getJSONArray("precipitation_probability_max").optInt(i)}%")
        return sb.toString()
    }

    // ---------- internet ----------

    private fun search(q: String): String {
        val key = sync.optString("tavily_key")
        if (key.isNotBlank()) {
            try {
                val r = Net.postJson("https://api.tavily.com/search", JSONObject().put("query", q).put("max_results", 5),
                    mapOf("Authorization" to "Bearer $key"), 25000).optJSONArray("results") ?: JSONArray()
                return (0 until r.length()).joinToString("\n\n") { val o = r.getJSONObject(it)
                    "[${it + 1}] ${o.optString("title")}\n${o.optString("url")}\n${o.optString("content").take(500)}" }
            } catch (_: Exception) { }
        }
        // DuckDuckGo (wersja HTML, bez klucza)
        val html = Net.send("https://html.duckduckgo.com/html/", "POST", "q=" + Net.enc(q) + "&kl=pl-pl", emptyMap(),
            "application/x-www-form-urlencoded")
        val links = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        if (links.isEmpty()) return "Brak wyników."
        return links.take(5).mapIndexed { i, m ->
            var url = m.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            "[${i + 1}] ${Net.stripHtml(m.groupValues[2])}\n$url\n${Net.stripHtml(snips.getOrNull(i)?.groupValues?.get(1) ?: "").take(400)}"
        }.joinToString("\n\n")
    }

    // ---------- aplikacje na telefonie ----------

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase().replace("ł", "l"), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), "").trim()

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = norm(name)
        val apps = pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val best = apps.maxByOrNull { (label, _) ->
            val l = norm(label)
            when { l == q -> 3.0; l.startsWith(q) || q.startsWith(l) -> 2.0; l.contains(q) || q.contains(l) -> 1.5
                else -> similarity(q, l) }
        } ?: return "Nie znalazłam aplikacji $name."
        val score = norm(best.first).let { l -> if (l.contains(q) || q.contains(l)) 1.0 else similarity(q, l) }
        if (score < 0.6) return "Nie znalazłam aplikacji „$name”."
        val intent = pm.getLaunchIntentForPackage(best.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "Nie mogę uruchomić ${best.first}."
        ctx.startActivity(intent)
        return "Otwieram ${best.first}."
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    // ---------- konta: tokeny ----------

    private fun token(svc: String): String {
        val s = sync.optJSONObject(svc) ?: throw IllegalStateException("$svc nie jest połączone na PC")
        val tok = s.getJSONObject("token")
        if (System.currentTimeMillis() / 1000.0 < tok.optDouble("expires_at", 0.0) - 60) return tok.getString("access_token")
        val form = if (svc == "spotify") mapOf("grant_type" to "refresh_token", "refresh_token" to tok.getString("refresh_token"),
            "client_id" to s.getString("client_id"))
        else mapOf("grant_type" to "refresh_token", "refresh_token" to tok.getString("refresh_token"),
            "client_id" to s.getString("client_id"), "client_secret" to s.getString("client_secret"))
        val url = if (svc == "spotify") "https://accounts.spotify.com/api/token" else "https://oauth2.googleapis.com/token"
        val fresh = JSONObject(Net.send(url, "POST", Net.form(form), emptyMap(), "application/x-www-form-urlencoded"))
        val keys = fresh.keys()
        while (keys.hasNext()) { val k = keys.next(); tok.put(k, fresh.get(k)) }
        tok.put("expires_at", System.currentTimeMillis() / 1000.0 + fresh.optInt("expires_in", 3600))
        store.saveToken(svc, tok)
        return tok.getString("access_token")
    }

    private fun sp(method: String, path: String, body: JSONObject? = null): JSONObject {
        val r = Net.send("https://api.spotify.com/v1$path", method, body?.toString(), mapOf("Authorization" to "Bearer ${token("spotify")}"))
        return runCatching { JSONObject(r) }.getOrDefault(JSONObject())
    }

    private fun spDevice(): String {
        fun list() = sp("GET", "/me/player/devices").optJSONArray("devices") ?: JSONArray()
        var devs = list()
        for (i in 0 until devs.length()) if (devs.getJSONObject(i).optBoolean("is_active")) return devs.getJSONObject(i).getString("id")
        if (devs.length() == 0) {  // Spotify nie działa - uruchamiamy je na telefonie
            ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            for (t in 0 until 15) { Thread.sleep(1000); devs = list(); if (devs.length() > 0) break }
        }
        for (i in 0 until devs.length()) if (devs.getJSONObject(i).optString("type") == "Smartphone") return devs.getJSONObject(i).getString("id")
        if (devs.length() > 0) return devs.getJSONObject(0).getString("id")
        throw IllegalStateException("Nie widzę Spotify na żadnym urządzeniu - otwórz aplikację Spotify.")
    }

    private fun spotifyPlay(query: String, kind: String): String {
        val dev = spDevice()
        val q = query.trim()
        val body: JSONObject; val what: String
        if (kind == "liked" || Regex("(?i)^(moje |twoje )?(polubione|ulubione)( utwory| piosenki)?$").matches(q)) {
            val items = sp("GET", "/me/tracks?limit=50").optJSONArray("items") ?: JSONArray()
            val uris = (0 until items.length()).mapNotNull { items.getJSONObject(it).optJSONObject("track")?.optString("uri") }.shuffled()
            if (uris.isEmpty()) return "Nie masz polubionych utworów."
            body = JSONObject().put("uris", JSONArray(uris)); what = "Twoje polubione utwory"
        } else if (q.isEmpty()) {
            sp("PUT", "/me/player/play?device_id=$dev"); return "Wznowiono."
        } else {
            val type = if (kind in listOf("playlist", "album", "artist")) kind else "track"
            val res = sp("GET", "/search?type=$type&limit=5&q=" + Net.enc(q)).optJSONObject("${type}s")?.optJSONArray("items") ?: JSONArray()
            val first = (0 until res.length()).map { res.optJSONObject(it) }.firstOrNull { it != null } ?: return "Nie znalazłam: $q."
            if (type == "track") {
                body = JSONObject().put("uris", JSONArray().put(first.getString("uri")))
                what = first.getString("name") + " – " + first.getJSONArray("artists").getJSONObject(0).getString("name")
            } else { body = JSONObject().put("context_uri", first.getString("uri")); what = first.getString("name") }
        }
        sp("PUT", "/me/player/play?device_id=$dev", body)
        return "Gram: $what."
    }

    private fun spotifyControl(action: String): String {
        when (action) {
            "pause" -> sp("PUT", "/me/player/pause")
            "resume" -> sp("PUT", "/me/player/play")
            "next" -> sp("POST", "/me/player/next")
            "previous" -> sp("POST", "/me/player/previous")
            "shuffle_on" -> sp("PUT", "/me/player/shuffle?state=true")
            "shuffle_off" -> sp("PUT", "/me/player/shuffle?state=false")
            else -> return "Nieznana akcja."
        }
        return "Gotowe."
    }

    private fun spotifyVolume(level: Int?, change: Int?): String {
        val cur = sp("GET", "/me/player").optJSONObject("device")?.optInt("volume_percent", 50) ?: 50
        val v = (level ?: (cur + (change ?: 0))).coerceIn(0, 100)
        sp("PUT", "/me/player/volume?volume_percent=$v")
        return "Głośność Spotify: $v%."
    }

    private fun spotifyNow(): String {
        val st = sp("GET", "/me/player")
        val it = st.optJSONObject("item") ?: return "Teraz nic nie gra na Spotify."
        return "${if (st.optBoolean("is_playing")) "Gra" else "Wstrzymane"}: ${it.optString("name")} – " +
            it.optJSONArray("artists")?.optJSONObject(0)?.optString("name") + " (urządzenie: ${st.optJSONObject("device")?.optString("name")})"
    }

    // ---------- Google ----------

    private fun g(url: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        JSONObject(Net.send(url, method, body?.toString(), mapOf("Authorization" to "Bearer ${token("google")}")).ifBlank { "{}" })

    private fun gmail(query: String, count: Int): String {
        val q = query.ifBlank { "in:inbox is:unread category:primary" }
        val base = "https://gmail.googleapis.com/gmail/v1/users/me/messages"
        val ids = g("$base?maxResults=${count.coerceIn(1, 15)}&q=" + Net.enc(q)).optJSONArray("messages") ?: return "Brak wiadomości."
        return (0 until ids.length()).joinToString("\n") { i ->
            val m = g("$base/${ids.getJSONObject(i).getString("id")}?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
            val h = m.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            fun hdr(n: String) = (0 until h.length()).map { h.getJSONObject(it) }.firstOrNull { it.optString("name") == n }?.optString("value") ?: ""
            "- Od: ${hdr("From").substringBefore("<").trim('"', ' ')} | ${hdr("Subject")}\n  ${m.optString("snippet").take(150)}"
        }
    }

    private fun iso(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)

    private fun events(days: Int): String {
        val now = Date(); val end = Date(now.time + days * 86400000L)
        val r = g("https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=25" +
            "&timeMin=" + Net.enc(iso(now)) + "&timeMax=" + Net.enc(iso(end)))
        val items = r.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return "Brak wydarzeń przez $days dni."
        return (0 until items.length()).joinToString("\n") { i ->
            val e = items.getJSONObject(i); val st = e.getJSONObject("start")
            "- ${st.optString("dateTime", st.optString("date")).take(16).replace("T", " ")}: ${e.optString("summary")}"
        }
    }

    private fun addEvent(title: String, start: String, minutes: Int): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Warsaw") }
        val st = f.parse(start.take(16)) ?: return "Zły format daty."
        val cal = Calendar.getInstance().apply { time = st; add(Calendar.MINUTE, minutes) }
        val tz = "Europe/Warsaw"
        g("https://www.googleapis.com/calendar/v3/calendars/primary/events", "POST", JSONObject().put("summary", title)
            .put("start", JSONObject().put("dateTime", f.format(st) + ":00").put("timeZone", tz))
            .put("end", JSONObject().put("dateTime", f.format(cal.time) + ":00").put("timeZone", tz)))
        return "Dodano: $title, ${start.replace("T", " ")}."
    }
}
