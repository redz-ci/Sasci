package ci.sas.livraisons

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.concurrent.Executors

/**
 * Base commune des écrans : construction simple de l'interface (gros boutons lisibles),
 * prise de photos, demande de position GPS.
 */
abstract class BaseActivity : AppCompatActivity() {

    protected val bg = Executors.newSingleThreadExecutor()

    // ---------- Photos de l'écran courant (conservées si Android ferme l'appli pendant la photo) ----------
    protected val photos = ArrayList<File>()
    private var captureEnCours: File? = null
    private var rangeePhotos: LinearLayout? = null
    private var maxPhotos = 5

    private val prendrePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = captureEnCours
        captureEnCours = null
        if (!ok || f == null) return@registerForActivityResult
        val dlg = attente("Traitement de la photo…")
        bg.execute {
            val res = runCatching { Photos.compresser(this, f) }.getOrNull()
            runOnUiThread {
                dlg.dismiss()
                if (res != null) {
                    photos.add(res); rafraichirPhotos()
                } else {
                    toast("Photo non enregistrée, réessayez")
                }
            }
        }
    }

    private var apresPermission: (() -> Unit)? = null
    private val demanderPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val suite = apresPermission
            apresPermission = null
            suite?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { b ->
            b.getStringArrayList("photos")?.map { File(it) }?.filter { it.exists() }?.let { photos.addAll(it) }
            b.getString("capture")?.let { captureEnCours = File(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("photos", ArrayList(photos.map { it.absolutePath }))
        captureEnCours?.let { outState.putString("capture", it.absolutePath) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- Construction de l'interface ----------

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Remplace le contenu de l'écran par une colonne défilante et renvoie cette colonne. */
    protected fun ecran(titre: String, retour: Boolean = true): LinearLayout {
        title = titre
        supportActionBar?.setDisplayHomeAsUpEnabled(retour)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        return col
    }

    protected fun LinearLayout.texte(
        t: String, taille: Float = 16f, gras: Boolean = false, couleur: Int = 0xFF212121.toInt(),
        haut: Int = 4,
    ): TextView {
        val tv = TextView(this@BaseActivity).apply {
            text = t
            textSize = taille
            setTextColor(couleur)
            if (gras) setTypeface(typeface, Typeface.BOLD)
        }
        addView(tv, lp(haut = haut))
        return tv
    }

    protected fun LinearLayout.titre(t: String): TextView =
        texte(t, 20f, true, 0xFF0D47A1.toInt(), haut = 16)

    protected fun LinearLayout.bouton(
        t: String, couleur: Int = 0xFF0D47A1.toInt(), haut: Int = 12, action: () -> Unit,
    ): MaterialButton {
        val b = MaterialButton(this@BaseActivity).apply {
            text = t
            textSize = 17f
            isAllCaps = false
            minHeight = dp(60)
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(couleur)
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }
        addView(b, lp(haut = haut))
        return b
    }

    protected fun LinearLayout.carte(
        couleurBord: Int = 0xFF0D47A1.toInt(), clic: (() -> Unit)? = null,
        contenu: LinearLayout.() -> Unit,
    ): MaterialCardView {
        val inner = LinearLayout(this@BaseActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        inner.contenu()
        val card = MaterialCardView(this@BaseActivity).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            strokeColor = couleurBord
            strokeWidth = dp(2)
            addView(inner)
            if (clic != null) {
                isClickable = true
                setOnClickListener { clic() }
            }
        }
        addView(card, lp(haut = 10))
        return card
    }

    protected fun LinearLayout.badge(t: String, couleur: Int): TextView {
        val tv = TextView(this@BaseActivity).apply {
            text = t
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(couleur); cornerRadius = dp(12).toFloat()
            }
        }
        addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        return tv
    }

    protected fun LinearLayout.champ(
        indice: String, multiligne: Boolean = false, nombre: Boolean = false, valeur: String = "",
        motDePasse: Boolean = false,
    ): EditText {
        val e = EditText(this@BaseActivity).apply {
            hint = indice
            setText(valeur)
            textSize = 17f
            inputType = when {
                motDePasse -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                nombre -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                multiligne -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (multiligne) {
                minLines = 3; gravity = Gravity.TOP or Gravity.START
            }
        }
        addView(e, lp(haut = 4))
        return e
    }

    protected fun LinearLayout.choix(options: List<String>, coche: Int = -1, onChange: ((Int) -> Unit)? = null): RadioGroup {
        val g = RadioGroup(this@BaseActivity).apply { orientation = RadioGroup.VERTICAL }
        options.forEachIndexed { i, o ->
            val rb = RadioButton(this@BaseActivity).apply {
                text = o
                textSize = 17f
                id = View.generateViewId()
                minHeight = dp(44)
            }
            g.addView(rb)
            if (i == coche) rb.isChecked = true
        }
        g.setOnCheckedChangeListener { grp, id ->
            val idx = (0 until grp.childCount).indexOfFirst { grp.getChildAt(it).id == id }
            onChange?.invoke(idx)
        }
        addView(g, lp(haut = 4))
        return g
    }

    protected fun RadioGroup.indexCoche(): Int =
        (0 until childCount).indexOfFirst { (getChildAt(it) as RadioButton).isChecked }

    protected fun LinearLayout.liste(options: List<String>, selection: Int = 0): Spinner {
        val s = Spinner(this@BaseActivity)
        s.adapter = ArrayAdapter(this@BaseActivity, android.R.layout.simple_spinner_dropdown_item, options)
        if (selection in options.indices) s.setSelection(selection)
        s.minimumHeight = dp(48)
        addView(s, lp(haut = 4))
        return s
    }

    protected fun lp(haut: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(haut)
        }

    /** Zone photos : miniatures + bouton appareil photo. */
    protected fun LinearLayout.zonePhotos(libelle: String, max: Int = 5) {
        maxPhotos = max
        texte(libelle, 16f, true, haut = 12)
        val rangee = LinearLayout(this@BaseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(this@BaseActivity).apply { addView(rangee) }
        addView(hs, lp(haut = 6))
        rangeePhotos = rangee
        bouton("📷  Prendre une photo", 0xFF455A64.toInt(), haut = 6) {
            if (photos.size >= maxPhotos) toast("Maximum $maxPhotos photos") else lancerAppareilPhoto()
        }
        rafraichirPhotos()
    }

    private fun rafraichirPhotos() {
        val rangee = rangeePhotos ?: return
        rangee.removeAllViews()
        if (photos.isEmpty()) {
            rangee.addView(TextView(this).apply {
                text = "Aucune photo"
                setTextColor(0xFF9E9E9E.toInt())
            })
            return
        }
        for (f in photos.toList()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(Photos.miniature(f, dp(96)))
                setOnClickListener {
                    AlertDialog.Builder(this@BaseActivity)
                        .setMessage("Supprimer cette photo ?")
                        .setPositiveButton("Supprimer") { _, _ ->
                            photos.remove(f); f.delete(); rafraichirPhotos()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            rangee.addView(iv, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) })
        }
    }

    private fun lancerAppareilPhoto() {
        try {
            val f = Photos.fichierCapture(this)
            captureEnCours = f
            prendrePhoto.launch(Photos.uri(this, f))
        } catch (e: Exception) {
            captureEnCours = null
            toast("Impossible d'ouvrir l'appareil photo")
        }
    }

    /** Vide la liste après un envoi (les fichiers appartiennent désormais à la file d'envoi). */
    protected fun photosEnvoyees(): List<File> {
        val l = photos.toList()
        photos.clear()
        return l
    }

    // ---------- GPS ----------

    protected fun demanderPermissionGps(suite: (() -> Unit)? = null) {
        if (Geo.permission(this)) {
            suite?.invoke(); return
        }
        apresPermission = suite
        demanderPermissions.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** Obtient la position (avec message d'attente). cb(null) si le GPS est indisponible. */
    protected fun avecPosition(cb: (GeoFix?) -> Unit) {
        demanderPermissionGps {
            if (!Geo.permission(this)) {
                AlertDialog.Builder(this)
                    .setTitle("GPS non autorisé")
                    .setMessage("L'application a besoin de la localisation pour prouver la présence du camion. Autorisez-la dans les paramètres.")
                    .setPositiveButton("Continuer sans GPS") { _, _ -> cb(null) }
                    .setNegativeButton("Annuler", null)
                    .show()
                return@demanderPermissionGps
            }
            if (!Geo.localisationActive(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Localisation désactivée")
                    .setMessage("Activez la localisation (GPS) du téléphone puis réessayez.")
                    .setPositiveButton("Ouvrir les réglages") { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }
                    .setNeutralButton("Continuer sans GPS") { _, _ -> cb(null) }
                    .setNegativeButton("Annuler", null)
                    .show()
                return@demanderPermissionGps
            }
            val dlg = attente("Localisation GPS en cours…")
            Geo.localiser(this) { fix ->
                if (!isFinishing) runCatching { dlg.dismiss() }
                cb(fix)
            }
        }
    }

    // ---------- Divers ----------

    protected fun attente(message: String): AlertDialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(this@BaseActivity))
            addView(TextView(this@BaseActivity).apply {
                text = message; textSize = 16f; setPadding(dp(16), 0, 0, 0)
            })
        }
        return AlertDialog.Builder(this).setView(row).setCancelable(false).show()
    }

    protected fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    protected fun alerte(titre: String, message: String, ok: (() -> Unit)? = null) {
        AlertDialog.Builder(this).setTitle(titre).setMessage(message)
            .setPositiveButton("OK") { _, _ -> ok?.invoke() }
            .show()
    }

    protected fun confirmer(titre: String, message: String, oui: String = "Confirmer", action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(titre).setMessage(message)
            .setPositiveButton(oui) { _, _ -> action() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }
}
