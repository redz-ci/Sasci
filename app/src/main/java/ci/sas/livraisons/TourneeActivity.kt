package ci.sas.livraisons

import android.content.Intent
import org.json.JSONObject

/** Étape 1 (départ du parc) + liste des BL de la tournée dans l'ordre de livraison. */
class TourneeActivity : BaseActivity() {

    private val tourneeId: String get() = intent.getStringExtra("tournee") ?: ""

    override fun onResume() {
        super.onResume()
        construire()
    }

    private fun construire() {
        val t = Store.tournee(this, tourneeId)
        val col = ecran("Tournée $tourneeId")
        if (t == null) {
            col.texte("Tournée introuvable. Revenez à l'accueil et touchez « Actualiser ».")
            return
        }
        col.texte("${Fmt.date(t.date)}  •  Camion ${t.camion}", 16f, couleur = 0xFF616161.toInt())
        col.texte("${t.bls.size} BL à livrer", 16f)

        val partie = Store.estPartie(this, t)
        col.titre("Étape 1 – Départ du parc")
        if (!partie) {
            col.texte("Confirmez votre départ au moment de quitter le parc. L'heure et la position GPS seront enregistrées.")
            col.bouton("🚚  Confirmer le départ du parc", 0xFF2E7D32.toInt()) { confirmerDepart(t) }
        } else {
            val h = Prefs.time(this, "dep_${t.id}")
            col.texte("✅ Départ confirmé" + (if (h > 0) " à ${Fmt.heure(h)}" else ""), 16f, true, 0xFF2E7D32.toInt())
        }

        col.titre("Livraisons (ordre de chargement)")
        if (!partie) col.texte("Les livraisons seront accessibles après la confirmation du départ.", 14f, couleur = 0xFFEF6C00.toInt())
        for (bl in t.bls) {
            val st = Store.statut(this, bl)
            col.carte(Statuts.couleur(st), clic = {
                if (!Store.estPartie(this, t)) {
                    alerte("Départ non confirmé", "Confirmez d'abord le départ du parc (étape 1).")
                } else {
                    startActivity(Intent(this, BlActivity::class.java).putExtra("bl", bl.numero))
                }
            }) {
                texte("${bl.ordre}.  BL ${bl.numero}", 18f, true)
                texte(bl.client, 16f)
                if (bl.adresse.isNotBlank()) texte(bl.adresse, 14f, couleur = 0xFF616161.toInt())
                badge(Statuts.libelle(st), Statuts.couleur(st))
            }
        }

        if (partie) {
            col.titre("Fin de tournée")
            if (Store.estRentree(this, t)) {
                col.texte("✅ Retour au parc enregistré", 16f, true, 0xFF2E7D32.toInt())
            } else {
                col.bouton("🏁  Retour au parc (fin de tournée)", 0xFF455A64.toInt()) { retourParc(t) }
            }
        }
        col.bouton("⚠  Déclarer un incident", 0xFFC62828.toInt(), haut = 24) {
            startActivity(Intent(this, IncidentActivity::class.java).putExtra("tournee", t.id))
        }
    }

    private fun confirmerDepart(t: Tournee) {
        confirmer("Départ du parc", "Confirmer le départ de la tournée ${t.id} maintenant ?") {
            val heure = System.currentTimeMillis()
            avecPosition { gps ->
                val data = JSONObject().put("tourneeId", t.id).put("time", heure)
                if (gps != null) data.put("gps", gps.toJson())
                SyncQueue.ajouter(this, "depart", data, emptyList())
                Prefs.setFlag(this, "dep_${t.id}")
                Prefs.setTime(this, "dep_${t.id}", heure)
                toast("Départ enregistré. Bonne route !")
                construire()
            }
        }
    }

    private fun retourParc(t: Tournee) {
        val restants = t.bls.count { Store.statut(this, it) < 3 }
        val msg = if (restants > 0) "$restants BL ne sont pas encore livrés. Confirmer quand même le retour au parc ?"
        else "Toutes les livraisons sont faites. Confirmer le retour au parc ?"
        confirmer("Fin de tournée", msg) {
            val heure = System.currentTimeMillis()
            avecPosition { gps ->
                val data = JSONObject().put("tourneeId", t.id).put("time", heure)
                if (gps != null) data.put("gps", gps.toJson())
                SyncQueue.ajouter(this, "fin_tournee", data, emptyList())
                Prefs.setFlag(this, "fin_${t.id}")
                toast("Fin de tournée enregistrée")
                construire()
            }
        }
    }
}
