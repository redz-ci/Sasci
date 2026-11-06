package ci.sas.livraisons

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import org.json.JSONObject

/** Déclaration d'un incident ou litige, à tout moment, rattaché ou non à un BL. */
class IncidentActivity : BaseActivity() {

    private val categories = listOf("Qualité de service", "Sécurité opérationnelle")
    private val types = listOf(
        listOf(
            "Réclamation client", "Quantité manquante", "Marchandise endommagée",
            "Erreur de référence", "Refus de livraison", "Déchargement problématique",
            "Attente excessive chez le client", "Autre",
        ),
        listOf(
            "Accident de circulation", "Accrochage", "Panne ou crevaison", "Difficulté d'accès",
            "Incident au péage", "Anomalie mécanique", "Situation dangereuse", "Autre",
        ),
    )
    private val niveaux = listOf("Mineur", "Important", "Grave")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construire()
    }

    private fun construire() {
        val blInitial = intent.getStringExtra("bl")
        val tourneeInitiale = intent.getStringExtra("tournee") ?: ""
        val col = ecran("Déclarer un incident")

        col.titre("Catégorie")
        lateinit var listeTypes: Spinner
        val cat = col.choix(categories, coche = if (blInitial != null) 0 else -1) { i ->
            if (i >= 0) {
                listeTypes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types[i])
            }
        }
        col.titre("Type d'incident")
        listeTypes = col.liste(types[0])

        col.titre("Niveau d'alerte")
        val niveau = col.choix(niveaux)

        col.titre("Livraison concernée")
        val tous = Store.tournees(this).flatMap { it.bls }
        val libelles = listOf("Aucune (hors livraison)") + tous.map { "BL ${it.numero} – ${it.client}" }
        val sel = tous.indexOfFirst { it.numero == blInitial }.let { if (it >= 0) it + 1 else 0 }
        val listeBl = col.liste(libelles, sel)

        col.titre("Véhicule concerné")
        val vehicule = col.champ("Immatriculation / n° camion", valeur = Prefs.camion(this))

        col.titre("Description")
        val description = col.champ("Décrivez ce qui s'est passé", multiligne = true)

        col.zonePhotos("Photos (recommandées)", max = 5)

        col.bouton("📨  Envoyer la déclaration", 0xFFC62828.toInt(), haut = 24) {
            val c = cat.indexCoche()
            val n = niveau.indexCoche()
            when {
                c < 0 -> { alerte("Catégorie manquante", "Choisissez la catégorie de l'incident."); return@bouton }
                n < 0 -> { alerte("Niveau manquant", "Choisissez le niveau : mineur, important ou grave."); return@bouton }
                description.text.isBlank() -> { alerte("Description manquante", "Décrivez l'incident."); return@bouton }
            }
            val b = listeBl.selectedItemPosition.let { if (it > 0) tous[it - 1] else null }
            val data = JSONObject()
                .put("categorie", categories[c])
                .put("type", listeTypes.selectedItem?.toString() ?: "")
                .put("niveau", niveaux[n])
                .put("bl", b?.numero ?: "")
                .put("tourneeId", b?.tourneeId ?: tourneeInitiale)
                .put("vehicule", vehicule.text.toString().trim())
                .put("description", description.text.toString().trim())
                .put("time", System.currentTimeMillis())
            avecPosition { gps ->
                if (gps != null) data.put("gps", gps.toJson())
                SyncQueue.ajouter(this, "incident", data, photosEnvoyees())
                apresEnvoi(niveaux[n])
            }
        }
    }

    private fun apresEnvoi(niveau: String) {
        val tel = Prefs.telLogistique(this)
        if (niveau == "Grave" && tel.isNotBlank()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Incident grave enregistré")
                .setMessage("La logistique est alertée. Voulez-vous l'appeler maintenant ?")
                .setPositiveButton("📞 Appeler") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))) }
                    finish()
                }
                .setNegativeButton("Plus tard") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else {
            toast("Incident enregistré. Envoi en cours…")
            finish()
        }
    }
}
