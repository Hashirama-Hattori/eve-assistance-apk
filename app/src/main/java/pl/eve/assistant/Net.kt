package pl.eve.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Proste zapytania HTTP (bez dodatkowych bibliotek). */
object Net {
    class HttpError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

    private fun conn(url: String, method: String, headers: Map<String, String>, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) EVE")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun finish(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, text)
        return text
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20000): String =
        finish(conn(url, "GET", headers, timeout))

    fun send(url: String, method: String, body: String?, headers: Map<String, String> = emptyMap(),
             contentType: String = "application/json; charset=utf-8", timeout: Int = 60000): String {
        val c = conn(url, method, headers, timeout)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray()) }
        } else if (method == "PUT" || method == "POST") {
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
        }
        return finish(c)
    }

    fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap(), timeout: Int = 60000) =
        JSONObject(send(url, "POST", json.toString(), headers, timeout = timeout).ifBlank { "{}" })

    fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun multipartAudio(url: String, wav: ByteArray, fields: Map<String, String>, headers: Map<String, String>): String {
        val boundary = "----EVE" + System.currentTimeMillis()
        val c = conn(url, "POST", headers, 60000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(c.outputStream).use { out ->
            fields.forEach { (k, v) ->
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n")
                out.write(v.toByteArray()); out.writeBytes("\r\n")
            }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"nagranie.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wav)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return finish(c)
    }

    fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style|nav|footer|header|noscript|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h\\d>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    @Suppress("unused")
    fun bytes(url: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).inputStream.use {
        val b = ByteArrayOutputStream(); it.copyTo(b); b.toByteArray()
    }
}
