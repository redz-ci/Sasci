package ci.sas.livraisons

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import org.json.JSONObject

/** Étapes 2 à 4 d'une livraison : arrivée, fin de déchargement, confirmation. */
class BlActivity : BaseActivity() {

    private val numero: String get() = intent.getStringExtra("bl") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Construit une seule fois (et non dans onResume) pour ne pas effacer la saisie
        // au retour de l'appareil photo.
        construire()
    }

    private fun construire() {
        val bl = Store.bl(this, numero)
        val col = ecran("BL $numero")
        if (bl == null) {
            col.texte("BL introuvable. Revenez à l'accueil et touchez « Actualiser ».")
            return
        }
        val st = Store.statut(this, bl)

        col.carte(Statuts.couleur(st)) {
            texte(bl.client, 20f, true)
            if (bl.adresse.isNotBlank()) texte(bl.adresse, 15f, couleur = 0xFF616161.toInt())
            if (bl.marchandise.isNotBlank()) texte("Marchandise : ${bl.marchandise}", 15f, haut = 8)
            if (bl.quantite.isNotBlank()) texte("Quantité prévue : ${bl.quantite}", 15f)
            badge(Statuts.libelle(st), Statuts.couleur(st))
        }
        if (bl.adresse.isNotBlank() || bl.gpsClient.isNotBlank()) {
            col.bouton("🗺  Itinéraire vers le client", 0xFF607D8B.toInt(), haut = 8) { itineraire(bl) }
        }

        val arr = Prefs.time(this, "arr_${bl.numero}")
        val fin = Prefs.time(this, "fin_${bl.numero}")
        if (arr > 0) col.texte("Arrivée : ${Fmt.heure(arr)}", 15f, haut = 12)
        if (fin > 0) {
            col.texte("Fin du déchargement : ${Fmt.heure(fin)}", 15f)
            if (arr > 0) col.texte("Durée de déchargement : ${(fin - arr) / 60000} min", 15f, true)
        }

        when (st) {
            0 -> etapeArrivee(col, bl)
            1 -> etapeFinDechargement(col, bl)
            2 -> etapeConfirmation(col, bl)
            else -> {
                col.titre("Livraison terminée")
                col.texte("✅ ${Statuts.libelle(st)}", 18f, true, 0xFF2E7D32.toInt())
                if (st == 3) col.texte("Le dossier sera contrôlé par le service logistique.", 15f)
            }
        }

        col.bouton("⚠  Signaler un incident sur ce BL", 0xFFC62828.toInt(), haut = 28) {
            startActivity(
                Intent(this, IncidentActivity::class.java)
                    .putExtra("bl", bl.numero).putExtra("tournee", bl.tourneeId)
            )
        }
    }

    private fun etapeArrivee(col: LinearLayout, bl: Bl) {
        col.titre("Étape 2 – Arrivée chez le client")
        col.texte("Une fois sur le site du client, photographiez votre camion puis confirmez l'arrivée.")
        col.zonePhotos("Photo du camion sur le site du client", max = 3)
        col.bouton("📍  Confirmer l'arrivée", 0xFF1565C0.toInt(), haut = 16) {
            if (photos.isEmpty()) {
                alerte("Photo obligatoire", "Prenez une photo du camion sur le site du client."); return@bouton
            }
            enregistrer(bl, "arrivee", 1, JSONObject()) { t -> Prefs.setTime(this, "arr_${bl.numero}", t) }
        }
    }

    private fun etapeFinDechargement(col: LinearLayout, bl: Bl) {
        col.titre("Étape 3 – Fin du déchargement")
        col.texte("Photographiez la marchandise effectivement livrée puis confirmez la fin du déchargement.")
        col.zonePhotos("Photo(s) de la marchandise livrée", max = 5)
        col.bouton("📦  Confirmer la fin du déchargement", 0xFFEF6C00.toInt(), haut = 16) {
            if (photos.isEmpty()) {
                alerte("Photo obligatoire", "Prenez au moins une photo de la marchandise livrée."); return@bouton
            }
            enregistrer(bl, "fin_dechargement", 2, JSONObject()) { t -> Prefs.setTime(this, "fin_${bl.numero}", t) }
        }
    }

    private fun etapeConfirmation(col: LinearLayout, bl: Bl) {
        col.titre("Étape 4 – Confirmation de livraison")
        col.zonePhotos("Photo du BL signé et déchargé par le client", max = 3)

        col.texte("Statut de la livraison", 16f, true, haut = 16)
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val choix: RadioGroup = col.choix(listOf("Livraison complète", "Livraison partielle", "Livraison refusée")) { i ->
            details.visibility = if (i > 0) View.VISIBLE else View.GONE
        }
        col.addView(details, lp())
        details.texte("Quantités concernées", 15f, true, haut = 8)
        val quantite: EditText = details.champ("Ex. : 20 sacs non livrés")
        details.texte("Motif", 15f, true, haut = 8)
        val motif: EditText = details.champ("Motif du refus / de la livraison partielle", multiligne = true)

        col.bouton("✅  Valider la livraison", 0xFF2E7D32.toInt(), haut = 16) {
            val i = choix.indexCoche()
            when {
                photos.isEmpty() -> {
                    alerte("Photo obligatoire", "Photographiez le BL signé par le client."); return@bouton
                }
                i < 0 -> {
                    alerte("Statut manquant", "Choisissez : complète, partielle ou refusée."); return@bouton
                }
                i > 0 && (quantite.text.isBlank() || motif.text.isBlank()) -> {
                    alerte("Informations manquantes", "Indiquez les quantités concernées et le motif."); return@bouton
                }
            }
            val resultat = listOf("Complète", "Partielle", "Refusée")[i]
            val data = JSONObject().put("resultat", resultat)
            if (i > 0) {
                data.put("quantite", quantite.text.toString().trim())
                data.put("motif", motif.text.toString().trim())
            }
            confirmer("Confirmation", "Livraison $resultat pour le BL ${bl.numero} ?") {
                enregistrer(bl, "confirmation", 3, data) {}
            }
        }
    }

    /** Enregistre l'étape : heure, GPS, photos -> file d'envoi. */
    private fun enregistrer(bl: Bl, action: String, nouveauStatut: Int, data: JSONObject, apres: (Long) -> Unit) {
        val heure = System.currentTimeMillis()
        avecPosition { gps ->
            data.put("bl", bl.numero).put("tourneeId", bl.tourneeId).put("time", heure)
            if (gps != null) data.put("gps", gps.toJson())
            SyncQueue.ajouter(this, action, data, photosEnvoyees())
            Prefs.setLocalStatut(this, bl.numero, nouveauStatut)
            apres(heure)
            toast(if (gps == null) "Enregistré (sans GPS). Envoi en cours…" else "Enregistré. Envoi en cours…")
            if (nouveauStatut >= 3) finish() else construire()
        }
    }

    private fun itineraire(bl: Bl) {
        val uri = if (bl.gpsClient.isNotBlank())
            Uri.parse("geo:0,0?q=" + Uri.encode(bl.gpsClient.replace(" ", "") + "(" + bl.client + ")"))
        else Uri.parse("geo:0,0?q=" + Uri.encode(bl.adresse))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast("Aucune application de cartes installée") }
    }
}
