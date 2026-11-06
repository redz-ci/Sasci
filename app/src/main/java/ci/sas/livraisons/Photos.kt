package ci.sas.livraisons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Prise de photo et compression (≈ 200-400 Ko par photo pour économiser les données mobiles). */
object Photos {
    private const val TAILLE_MAX = 1600
    private const val QUALITE = 70

    fun fichierCapture(ctx: Context): File {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        return File(dir, "cap_${System.currentTimeMillis()}.jpg")
    }

    fun uri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    /** Redimensionne, corrige l'orientation et enregistre la photo dans le stockage de l'appli. */
    fun compresser(ctx: Context, src: File): File? {
        if (!src.exists() || src.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TAILLE_MAX) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val echelle = minOf(1f, TAILLE_MAX.toFloat() / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply {
            postScale(echelle, echelle)
            postRotate(rotation)
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.jpg")
        dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITE, it) }
        if (out !== bmp) out.recycle()
        bmp.recycle()
        src.delete()
        return dest
    }

    fun miniature(f: File, taille: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= taille) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
