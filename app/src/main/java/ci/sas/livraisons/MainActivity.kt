package ci.sas.livraisons

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Accueil du chauffeur : ses tournées, l'état des envois et le bouton incident. */
class MainActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var etat: TextView? = null
    private var boutonEchecs: MaterialButton? = null
    private var actif = false

    private val boucle = object : Runnable {
        override fun run() {
            SyncQueue.synchroniser(this@MainActivity)
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.chauffeur(this).isBlank()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        demanderPermissionGps()
    }

    override fun onResume() {
        super.onResume()
        actif = true
        construire()
        SyncQueue.listener = { afficherEtat() }
        handler.post(boucle)
        actualiser(silencieux = true)
    }

    override fun onPause() {
        super.onPause()
        actif = false
        SyncQueue.listener = null
        handler.removeCallbacks(boucle)
    }

    private fun construire() {
        val col = ecran("Mes tournées", retour = false)
        col.texte("Bonjour ${Prefs.chauffeur(this)}", 20f, true)
        val camion = Prefs.camion(this)
        if (camion.isNotBlank()) col.texte("Camion : $camion", 16f, couleur = 0xFF616161.toInt())

        col.carte(0xFF90A4AE.toInt()) {
            etat = texte("", 16f, true)
            boutonEchecs = bouton("Réessayer les envois refusés", 0xFFC62828.toInt(), haut = 8) {
                SyncQueue.reessayerEchecs(this@MainActivity)
            }
        }
        afficherEtat()

        col.bouton("⚠  Déclarer un incident", 0xFFC62828.toInt(), haut = 16) {
            startActivity(Intent(this, IncidentActivity::class.java))
        }

        col.titre("Tournées")
        val tournees = Store.tournees(this)
        if (tournees.isEmpty()) {
            col.texte("Aucune tournée ne vous est affectée pour le moment.\nTouchez « Actualiser » après la préparation des tournées par la logistique.", haut = 8)
        }
        for (t in tournees) {
            val livres = t.bls.count { Store.statut(this, it) >= 3 }
            val statut = when {
                t.statut == "Clôturée" -> "Clôturée"
                Store.estRentree(this, t) -> "Terminée"
                Store.estPartie(this, t) -> "En cours"
                else -> "À démarrer"
            }
            val couleur = when (statut) {
                "En cours" -> 0xFF1565C0.toInt()
                "À démarrer" -> 0xFFEF6C00.toInt()
                else -> 0xFF2E7D32.toInt()
            }
            col.carte(couleur, clic = {
                startActivity(Intent(this, TourneeActivity::class.java).putExtra("tournee", t.id))
            }) {
                texte("Tournée ${t.id}", 18f, true)
                texte("${Fmt.date(t.date)}  •  Camion ${t.camion}", 15f, couleur = 0xFF616161.toInt())
                texte("$livres / ${t.bls.size} BL livrés", 15f)
                badge(statut, couleur)
            }
        }

        val maj = Prefs.cacheTime(this)
        if (maj > 0) col.texte("Liste mise à jour à ${Fmt.heure(maj)}", 13f, couleur = 0xFF9E9E9E.toInt(), haut = 12)
        col.bouton("🔄  Actualiser", 0xFF0D47A1.toInt(), haut = 16) { actualiser(silencieux = false) }
        col.bouton("Se déconnecter", 0xFF9E9E9E.toInt()) { deconnecter() }
    }

    private fun afficherEtat() {
        val tv = etat ?: return
        val attente = SyncQueue.enAttente(this)
        val echecs = SyncQueue.enEchec(this)
        val txt = StringBuilder()
        when {
            SyncQueue.enCours && attente > 0 -> txt.append("⏳ Envoi en cours… ($attente en attente)")
            attente > 0 -> txt.append("⏳ $attente envoi(s) en attente de réseau")
            else -> txt.append("✅ Toutes les données sont envoyées")
        }
        SyncQueue.derniereErreur?.let { if (attente > 0 || echecs > 0) txt.append("\n").append(it) }
        if (echecs > 0) txt.append("\n❌ $echecs envoi(s) refusé(s) par le serveur")
        tv.text = txt.toString()
        tv.setTextColor(
            when {
                echecs > 0 -> 0xFFC62828.toInt()
                attente > 0 -> 0xFFEF6C00.toInt()
                else -> 0xFF2E7D32.toInt()
            }
        )
        boutonEchecs?.visibility = if (echecs > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun actualiser(silencieux: Boolean) {
        val dlg = if (silencieux) null else attente("Mise à jour…")
        bg.execute {
            val res = runCatching {
                Api.get(this, mapOf("action" to "bootstrap", "chauffeur" to Prefs.chauffeur(this), "pin" to Prefs.pin(this)))
            }
            runOnUiThread {
                dlg?.dismiss()
                res.onSuccess { json ->
                    if (json.optBoolean("ok")) {
                        Prefs.saveBootstrap(this, json)
                        if (actif) construire()
                    } else if (!silencieux) {
                        alerte("Erreur", json.optString("error"))
                    }
                }.onFailure {
                    if (!silencieux) alerte("Pas de connexion", "Impossible de joindre le serveur. Les données enregistrées sur le téléphone restent utilisables.")
                }
            }
        }
    }

    private fun deconnecter() {
        val attente = SyncQueue.enAttente(this)
        val msg = if (attente > 0)
            "Attention : $attente envoi(s) sont encore en attente. Ils seront envoyés dès que le réseau revient, même après la déconnexion.\n\nSe déconnecter ?"
        else "Se déconnecter de l'application ?"
        confirmer("Déconnexion", msg, "Se déconnecter") {
            Prefs.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
