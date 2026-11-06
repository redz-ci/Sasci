package ci.sas.livraisons

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * File d'attente des envois. Chaque action du chauffeur est d'abord enregistrée sur le
 * téléphone, puis envoyée dès que le réseau est disponible : rien n'est perdu en zone
 * sans couverture. Chaque événement a un identifiant unique, le serveur ignore les doublons.
 */
object SyncQueue {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var enCours = false
        private set
    @Volatile var derniereErreur: String? = null
        private set
    @Volatile var dernierEnvoi: Long = 0L
        private set

    /** Appelé (sur le thread principal) quand l'état de la file change. */
    var listener: (() -> Unit)? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "queue").apply { mkdirs() }
    private fun failedDir(ctx: Context) = File(ctx.filesDir, "queue_echecs").apply { mkdirs() }

    fun enAttente(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    fun enEchec(ctx: Context): Int =
        failedDir(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    fun ajouter(ctx: Context, action: String, data: JSONObject, photos: List<File>) {
        val id = UUID.randomUUID().toString()
        data.put("eventId", id)
        data.put("action", action)
        data.put("chauffeur", Prefs.chauffeur(ctx))
        data.put("pin", Prefs.pin(ctx))
        if (!data.has("time")) data.put("time", System.currentTimeMillis())
        data.put("photoFiles", JSONArray(photos.map { it.absolutePath }))
        val f = File(dir(ctx), "${System.currentTimeMillis()}_$id.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(data.toString())
        tmp.renameTo(f)
        notifier()
        synchroniser(ctx)
    }

    /** Remet les envois en échec dans la file (bouton "Réessayer"). */
    fun reessayerEchecs(ctx: Context) {
        failedDir(ctx).listFiles { f -> f.name.endsWith(".json") }?.forEach {
            it.renameTo(File(dir(ctx), it.name))
        }
        synchroniser(ctx)
    }

    fun synchroniser(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute { traiter(app) }
    }

    private fun traiter(ctx: Context) {
        val fichiers = dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }
        if (fichiers.isNullOrEmpty()) {
            derniereErreur = null
            notifier()
            return
        }
        enCours = true
        notifier()
        try {
            for (f in fichiers) {
                val ev = try {
                    JSONObject(f.readText())
                } catch (e: Exception) {
                    f.renameTo(File(failedDir(ctx), f.name)); continue
                }
                val chemins = ev.optJSONArray("photoFiles") ?: JSONArray()
                val fichiersPhotos = (0 until chemins.length()).map { File(chemins.getString(it)) }
                val photos = JSONArray()
                fichiersPhotos.filter { it.exists() }.forEach {
                    photos.put(Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
                }
                ev.remove("photoFiles")
                ev.put("photos", photos)
                try {
                    val res = Api.post(ctx, ev)
                    if (res.optBoolean("ok")) {
                        fichiersPhotos.forEach { it.delete() }
                        f.delete()
                        derniereErreur = null
                        dernierEnvoi = System.currentTimeMillis()
                    } else {
                        // Refus du serveur (BL inconnu, etc.) : mis de côté pour ne pas bloquer la file.
                        derniereErreur = res.optString("error", "Refusé par le serveur")
                        f.renameTo(File(failedDir(ctx), f.name))
                    }
                } catch (e: IOException) {
                    derniereErreur = "Pas de connexion : ${e.message ?: "réseau indisponible"}"
                    break
                }
                notifier()
            }
        } finally {
            enCours = false
            notifier()
        }
    }

    private fun notifier() {
        main.post { listener?.invoke() }
    }
}
