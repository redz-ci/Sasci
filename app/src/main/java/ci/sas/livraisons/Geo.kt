package ci.sas.livraisons

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject

class GeoFix(val lat: Double, val lng: Double, val precision: Float) {
    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lng", lng).put("acc", precision.toDouble())
}

/** Obtient une position GPS (sans dépendre des services Google Play). */
object Geo {
    fun permission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun localisationActive(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    fun localiser(ctx: Context, delaiMs: Long = 25_000L, cb: (GeoFix?) -> Unit) {
        if (!permission(ctx)) {
            cb(null); return
        }
        Localisateur(ctx, delaiMs, cb).demarrer()
    }

    private class Localisateur(
        ctx: Context,
        private val delaiMs: Long,
        private val cb: (GeoFix?) -> Unit,
    ) : LocationListener {
        private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private val handler = Handler(Looper.getMainLooper())
        private var meilleure: Location? = null
        private var termine = false

        @SuppressLint("MissingPermission")
        fun demarrer() {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            // Dernière position connue récente (moins de 2 minutes) comme point de départ.
            for (p in providers) {
                val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (System.currentTimeMillis() - l.time < 120_000) proposer(l)
            }
            if (providers.isEmpty()) {
                terminer(); return
            }
            for (p in providers) {
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper()) }
            }
            handler.postDelayed({ terminer() }, delaiMs)
        }

        private fun proposer(l: Location) {
            val m = meilleure
            if (m == null || l.accuracy < m.accuracy) meilleure = l
            if (l.provider == LocationManager.GPS_PROVIDER && l.accuracy <= 25f) terminer()
        }

        private fun terminer() {
            if (termine) return
            termine = true
            handler.removeCallbacksAndMessages(null)
            runCatching { lm.removeUpdates(this) }
            val l = meilleure
            cb(l?.let { GeoFix(it.latitude, it.longitude, it.accuracy) })
        }

        override fun onLocationChanged(location: Location) {
            if (!termine) proposer(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
}
