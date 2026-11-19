package pl.eve.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rozmowa z rdzeniem EVE (serwer na PC albo w chmurze). */
class EveApi(private val prefs: Prefs) {

    data class Reply(val user: String, val text: String, val audio: ByteArray?, val action: String?)

    /** Wysyła nagranie (PCM 16 kHz mono) - EVE sama zamienia mowę na tekst i odpowiada. */
    fun sendVoice(pcm: ByteArray): Reply {
        val wav = pcmToWav(pcm, 16000)
        val boundary = "----EVE" + System.currentTimeMillis()
        val conn = open("/api/voice")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"nagranie.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wav)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return read(conn)
    }

    /** Wysyła tekst (np. wpisany ręcznie). */
    fun sendText(text: String): Reply {
        val conn = open("/api/text")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        return read(conn)
    }

    /** Sprawdza połączenie i kod dostępu. Zwraca null, gdy wszystko OK, inaczej opis problemu. */
    fun check(): String? = try {
        val conn = URL(prefs.serverUrl + "/api/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("X-Eve-Token", prefs.token)
        when (conn.responseCode) {
            200 -> null
            401 -> "Zły kod dostępu"
            else -> "Serwer odpowiada kodem ${conn.responseCode}"
        }
    } catch (e: Exception) {
        "Brak połączenia z ${prefs.serverUrl} (${e.javaClass.simpleName})"
    }

    private fun open(path: String): HttpURLConnection {
        val conn = URL(prefs.serverUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 120000
        conn.setRequestProperty("X-Eve-Token", prefs.token)
        conn.setRequestProperty("X-Eve-Client", "app")
        return conn
    }

    private fun read(conn: HttpURLConnection): Reply {
        val code = conn.responseCode
        if (code == 401) throw IllegalStateException("Zły kod dostępu - sprawdź ustawienia")
        if (code != 200) throw IllegalStateException("EVE odpowiedziała kodem $code")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val j = JSONObject(body)
        val audioB64 = j.optString("audio", "")
        val audio = if (audioB64.isNotEmpty() && audioB64 != "null")
            android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT) else null
        return Reply(j.optString("user"), j.optString("reply"), audio,
            j.optString("action").takeIf { it.isNotEmpty() && it != "null" })
    }

    companion object {
        fun pcmToWav(pcm: ByteArray, rate: Int): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(pcm.size)
            return ByteArrayOutputStream().apply { write(header.array()); write(pcm) }.toByteArray()
        }
    }
}
