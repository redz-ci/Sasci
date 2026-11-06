package ci.sas.livraisons

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Communication avec le Web App Google Apps Script (voir dossier apps-script/). */
object Api {

    fun get(ctx: Context, params: Map<String, String>): JSONObject {
        val base = Prefs.scriptUrl(ctx)
        if (base.isBlank()) throw IOException("Adresse du serveur non configurée")
        val all = params + mapOf("key" to Prefs.apiKey(ctx))
        val query = all.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val sep = if (base.contains("?")) "&" else "?"
        return parse(request("$base$sep$query", null))
    }

    fun post(ctx: Context, body: JSONObject): JSONObject {
        val base = Prefs.scriptUrl(ctx)
        if (base.isBlank()) throw IOException("Adresse du serveur non configurée")
        body.put("key", Prefs.apiKey(ctx))
        return parse(request(base, body.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun parse(text: String): JSONObject {
        try {
            return JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Réponse invalide du serveur (vérifier le déploiement du script)")
        }
    }

    /**
     * Google Apps Script répond aux POST par une redirection 302 vers le résultat :
     * on suit les redirections manuellement en passant en GET.
     */
    private fun request(startUrl: String, postBody: ByteArray?): String {
        var url = URL(startUrl)
        var body = postBody
        for (hop in 0 until 6) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 120_000
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                } else {
                    c.requestMethod = "GET"
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirection sans adresse")
                    url = URL(url, loc)
                    body = null
                    continue
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code >= 400) throw IOException("Erreur serveur HTTP $code")
                return text
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Trop de redirections")
    }
}
