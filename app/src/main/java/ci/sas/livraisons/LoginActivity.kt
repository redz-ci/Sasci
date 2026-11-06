package ci.sas.livraisons

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner

/** Premier écran : le chauffeur choisit son nom et saisit son code PIN. */
class LoginActivity : BaseActivity() {

    private lateinit var url: EditText
    private lateinit var cle: EditText
    private lateinit var zoneChauffeurs: LinearLayout
    private var noms: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.chauffeur(this).isNotBlank() && Prefs.cache(this) != null) {
            ouvrirAccueil(); return
        }
        construire()
        if (Prefs.scriptUrl(this).isNotBlank()) chargerChauffeurs()
    }

    private fun construire() {
        val col = ecran("SAS CI – Connexion", retour = false)
        col.texte("Suivi des livraisons et des incidents", 22f, true, 0xFF0D47A1.toInt(), haut = 8)
        col.texte("Version ${BuildConfig.VERSION_NAME}", 13f, couleur = 0xFF757575.toInt())

        // Paramètres serveur (normalement déjà intégrés à l'appli par la logistique)
        val zoneServeur = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoneServeur.titre("Paramètres du serveur")
        zoneServeur.texte("Adresse du script Google (fournie par la logistique)", 14f)
        url = zoneServeur.champ("https://script.google.com/macros/s/…/exec", valeur = Prefs.scriptUrl(this))
        zoneServeur.texte("Clé API", 14f)
        cle = zoneServeur.champ("Clé API", valeur = Prefs.apiKey(this))
        zoneServeur.bouton("Enregistrer et tester", 0xFF455A64.toInt()) {
            Prefs.setServeur(this, url.text.toString(), cle.text.toString())
            chargerChauffeurs()
        }
        zoneServeur.visibility = if (Prefs.scriptUrl(this).isBlank()) View.VISIBLE else View.GONE
        col.addView(zoneServeur, lp(haut = 8))

        zoneChauffeurs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(zoneChauffeurs, lp(haut = 8))

        col.bouton("Recharger la liste des chauffeurs", 0xFF607D8B.toInt(), haut = 24) { chargerChauffeurs() }
        col.bouton("⚙  Paramètres du serveur", 0xFF9E9E9E.toInt()) {
            zoneServeur.visibility = if (zoneServeur.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun chargerChauffeurs() {
        if (Prefs.scriptUrl(this).isBlank()) {
            toast("Saisissez d'abord l'adresse du serveur"); return
        }
        val dlg = attente("Connexion au serveur…")
        bg.execute {
            val res = runCatching { Api.get(this, mapOf("action" to "chauffeurs")) }
            runOnUiThread {
                dlg.dismiss()
                res.onSuccess { json ->
                    if (!json.optBoolean("ok")) {
                        alerte("Erreur", json.optString("error", "Erreur inconnue")); return@onSuccess
                    }
                    val arr = json.optJSONArray("chauffeurs")
                    noms = if (arr == null) emptyList() else
                        (0 until arr.length()).map { arr.getJSONObject(it).optString("nom") }
                    afficherChoix()
                }.onFailure {
                    alerte("Connexion impossible", (it.message ?: "Erreur réseau") +
                        "\n\nVérifiez la connexion internet et les paramètres du serveur.")
                }
            }
        }
    }

    private fun afficherChoix() {
        zoneChauffeurs.removeAllViews()
        if (noms.isEmpty()) {
            zoneChauffeurs.texte("Aucun chauffeur actif dans l'onglet « Chauffeurs » du Google Sheets.", haut = 16)
            return
        }
        zoneChauffeurs.titre("Qui êtes-vous ?")
        val liste: Spinner = zoneChauffeurs.liste(listOf("— Choisir votre nom —") + noms)
        zoneChauffeurs.texte("Code PIN (si attribué par la logistique)", 14f, haut = 12)
        val pin = zoneChauffeurs.champ("Code PIN", motDePasse = true)
        zoneChauffeurs.bouton("Se connecter", 0xFF2E7D32.toInt(), haut = 16) {
            val i = liste.selectedItemPosition
            if (i <= 0) {
                toast("Choisissez votre nom"); return@bouton
            }
            connecter(noms[i - 1], pin.text.toString().trim())
        }
    }

    private fun connecter(nom: String, pin: String) {
        val dlg = attente("Chargement de vos tournées…")
        bg.execute {
            val res = runCatching {
                Api.get(this, mapOf("action" to "bootstrap", "chauffeur" to nom, "pin" to pin))
            }
            runOnUiThread {
                dlg.dismiss()
                res.onSuccess { json ->
                    if (!json.optBoolean("ok")) {
                        alerte("Connexion refusée", json.optString("error", "Erreur")); return@onSuccess
                    }
                    Prefs.login(this, nom, pin)
                    Prefs.saveBootstrap(this, json)
                    ouvrirAccueil()
                }.onFailure { alerte("Connexion impossible", it.message ?: "Erreur réseau") }
            }
        }
    }

    private fun ouvrirAccueil() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
