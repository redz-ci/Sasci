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
        val base = verifierUrl(Prefs.scriptUrl(ctx))
        val all = params + mapOf("key" to Prefs.apiKey(ctx))
        val query = all.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val sep = if (base.contains("?")) "&" else "?"
        return parse(request("$base$sep$query", null))
    }

    fun post(ctx: Context, body: JSONObject): JSONObject {
        val base = verifierUrl(Prefs.scriptUrl(ctx))
        body.put("key", Prefs.apiKey(ctx))
        return parse(request(base, body.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Détecte les erreurs de saisie d'adresse les plus courantes avant d'appeler le serveur. */
    fun verifierUrl(brute: String): String {
        val url = brute.trim()
        if (url.isBlank()) throw IOException("Adresse du serveur non configurée.")
        if (!url.startsWith("https://script.google.com/")) {
            throw IOException("L'adresse doit commencer par https://script.google.com/ (adresse saisie : $url)")
        }
        val chemin = url.substringBefore("?").trimEnd('/')
        if (chemin.endsWith("/dev")) {
            throw IOException(
                "L'adresse se termine par /dev : c'est l'adresse de test, qui ne fonctionne pas sur les téléphones. " +
                    "Dans Apps Script, ouvrez Déployer > Gérer les déploiements et copiez l'URL de l'application Web qui se termine par /exec."
            )
        }
        if (!chemin.endsWith("/exec")) {
            throw IOException("L'adresse doit se terminer par /exec (Déployer > Gérer les déploiements > URL de l'application Web).")
        }
        return chemin
    }

    private fun parse(text: String): JSONObject {
        try {
            return JSONObject(text)
        } catch (e: Exception) {
            throw IOException(diagnostic(text))
        }
    }

    /** Traduit une page HTML renvoyée par Google en explication compréhensible. */
    private fun diagnostic(text: String): String {
        val t = text.lowercase()
        return when {
            "doget" in t || "dopost" in t || "fonction de script introuvable" in t || "script function not found" in t ->
                "Le déploiement ne contient pas le code SAS CI (fonction doGet introuvable).\n" +
                    "Vérifiez que Code.gs est bien collé et enregistré, puis Déployer > Gérer les déploiements > Modifier > Version : Nouvelle version > Déployer."
            "unable to open the file" in t || "impossible d'ouvrir le fichier" in t || "not found" in t || "introuvable" in t ->
                "Adresse du script introuvable : vérifiez l'URL /exec (copiez-la depuis Déployer > Gérer les déploiements)."
            "accounts.google.com" in t || "servicelogin" in t ->
                "Google demande une connexion : le script n'est pas accessible publiquement.\n" +
                    "Dans Apps Script : Déployer > Gérer les déploiements > Modifier (crayon) > " +
                    "« Exécuter en tant que : Moi » et « Qui a accès : Tout le monde », puis Déployer."
            else -> "Réponse inattendue du serveur : " + text.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(200)
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
                    if (url.host.contains("accounts.google")) {
                        throw IOException(
                            "Google demande une connexion : le script n'est pas accessible publiquement.\n" +
                                "Dans Apps Script : Déployer > Gérer les déploiements > Modifier (crayon) > " +
                                "« Exécuter en tant que : Moi » et « Qui a accès : Tout le monde », puis Déployer."
                        )
                    }
                    body = null
                    continue
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code >= 400) {
                    val t = text.trim()
                    if (t.startsWith("{")) return t
                    throw IOException(
                        when (code) {
                            401, 403 -> diagnostic(t).let {
                                if (it.startsWith("Réponse inattendue")) "Accès refusé par Google (HTTP $code) : le déploiement doit être « Qui a accès : Tout le monde »." else it
                            }
                            404 -> "Adresse du script introuvable (HTTP 404) : vérifiez l'URL /exec."
                            else -> "Erreur du serveur Google (HTTP $code). " + diagnostic(t)
                        }
                    )
                }
                return text
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Trop de redirections")
    }
}
