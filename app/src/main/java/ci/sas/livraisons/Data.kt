package ci.sas.livraisons

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Statuts d'un BL, dans l'ordre du processus (identiques à ceux du Google Sheets). */
object Statuts {
    const val A_LIVRER = "À livrer"
    const val ARRIVE = "Arrivé"
    const val DECHARGE = "Déchargé"
    const val LIVRE = "Livré – à valider"
    const val VALIDE = "Validé"
    const val CLOTURE = "Clôturé"
    val ORDRE = listOf(A_LIVRER, ARRIVE, DECHARGE, LIVRE, VALIDE, CLOTURE)

    fun index(s: String?): Int {
        val i = ORDRE.indexOf(s?.trim() ?: "")
        return if (i < 0) 0 else i
    }

    fun libelle(i: Int): String = ORDRE[i.coerceIn(0, ORDRE.size - 1)]

    fun couleur(i: Int): Int = when (i) {
        0 -> 0xFF757575.toInt()
        1 -> 0xFF1565C0.toInt()
        2 -> 0xFFEF6C00.toInt()
        3 -> 0xFF2E7D32.toInt()
        else -> 0xFF1B5E20.toInt()
    }
}

data class Bl(
    val numero: String,
    val tourneeId: String,
    val ordre: Int,
    val client: String,
    val adresse: String,
    val gpsClient: String,
    val marchandise: String,
    val quantite: String,
    val statutServeur: String,
)

data class Tournee(
    val id: String,
    val date: String,
    val camion: String,
    val statut: String,
    val depart: String,
    val retour: String,
    val bls: List<Bl>,
)

/** Préférences locales : identité du chauffeur, configuration serveur, cache et état local. */
object Prefs {
    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("sasci", Context.MODE_PRIVATE)

    fun scriptUrl(ctx: Context): String =
        p(ctx).getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SCRIPT_URL

    fun apiKey(ctx: Context): String =
        p(ctx).getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_KEY

    fun setServeur(ctx: Context, url: String, key: String) {
        p(ctx).edit().putString("url", url.trim()).putString("key", key.trim()).apply()
    }

    fun chauffeur(ctx: Context): String = p(ctx).getString("chauffeur", "") ?: ""
    fun pin(ctx: Context): String = p(ctx).getString("pin", "") ?: ""
    fun camion(ctx: Context): String = p(ctx).getString("camion", "") ?: ""
    fun telLogistique(ctx: Context): String = p(ctx).getString("tel", "") ?: ""

    fun login(ctx: Context, chauffeur: String, pin: String) {
        p(ctx).edit().putString("chauffeur", chauffeur).putString("pin", pin).apply()
    }

    fun logout(ctx: Context) {
        val url = p(ctx).getString("url", "")
        val key = p(ctx).getString("key", "")
        p(ctx).edit().clear().putString("url", url).putString("key", key).apply()
    }

    fun saveBootstrap(ctx: Context, json: JSONObject) {
        val ch = json.optJSONObject("chauffeur")
        val params = json.optJSONObject("params")
        p(ctx).edit()
            .putString("cache", json.toString())
            .putString("camion", ch?.optString("camion") ?: camion(ctx))
            .putString("tel", params?.optString("telLogistique") ?: "")
            .putLong("cacheTime", System.currentTimeMillis())
            .apply()
    }

    fun cache(ctx: Context): JSONObject? =
        p(ctx).getString("cache", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun cacheTime(ctx: Context): Long = p(ctx).getLong("cacheTime", 0L)

    fun localStatut(ctx: Context, bl: String): Int = p(ctx).getInt("st_$bl", 0)
    fun setLocalStatut(ctx: Context, bl: String, i: Int) {
        if (i > localStatut(ctx, bl)) p(ctx).edit().putInt("st_$bl", i).apply()
    }

    fun flag(ctx: Context, name: String): Boolean = p(ctx).getBoolean("f_$name", false)
    fun setFlag(ctx: Context, name: String) = p(ctx).edit().putBoolean("f_$name", true).apply()

    fun time(ctx: Context, name: String): Long = p(ctx).getLong("t_$name", 0L)
    fun setTime(ctx: Context, name: String, t: Long) = p(ctx).edit().putLong("t_$name", t).apply()
}

/** Accès aux tournées / BL en cache, fusionnés avec les actions faites hors ligne. */
object Store {
    fun tournees(ctx: Context): List<Tournee> {
        val arr = Prefs.cache(ctx)?.optJSONArray("tournees") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseTournee(it) }
    }

    fun tournee(ctx: Context, id: String): Tournee? = tournees(ctx).firstOrNull { it.id == id }

    fun bl(ctx: Context, numero: String): Bl? =
        tournees(ctx).flatMap { it.bls }.firstOrNull { it.numero == numero }

    fun statut(ctx: Context, bl: Bl): Int =
        maxOf(Statuts.index(bl.statutServeur), Prefs.localStatut(ctx, bl.numero))

    fun estPartie(ctx: Context, t: Tournee): Boolean =
        t.depart.isNotBlank() || t.statut in listOf("En cours", "Terminée", "Clôturée") ||
            Prefs.flag(ctx, "dep_${t.id}")

    fun estRentree(ctx: Context, t: Tournee): Boolean =
        t.retour.isNotBlank() || t.statut == "Clôturée" || Prefs.flag(ctx, "fin_${t.id}")

    private fun parseTournee(o: JSONObject): Tournee {
        val blsJson = o.optJSONArray("bls") ?: JSONArray()
        val id = o.optString("id")
        val bls = (0 until blsJson.length()).mapNotNull { blsJson.optJSONObject(it) }.map {
            Bl(
                numero = it.optString("bl"),
                tourneeId = id,
                ordre = it.optInt("ordre", 0),
                client = it.optString("client"),
                adresse = it.optString("adresse"),
                gpsClient = it.optString("gpsClient"),
                marchandise = it.optString("marchandise"),
                quantite = it.optString("quantite"),
                statutServeur = it.optString("statut"),
            )
        }.sortedBy { it.ordre }
        return Tournee(
            id = id,
            date = o.optString("date"),
            camion = o.optString("camion"),
            statut = o.optString("statut"),
            depart = o.optString("depart"),
            retour = o.optString("retour"),
            bls = bls,
        )
    }
}

object Fmt {
    private val heure = SimpleDateFormat("HH:mm", Locale.FRANCE)

    fun heure(t: Long): String = if (t <= 0) "" else heure.format(Date(t))

    /** "2026-11-06" -> "06/11/2026" */
    fun date(iso: String): String {
        val parts = iso.split("-")
        return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
    }
}
