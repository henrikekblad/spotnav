package se.sensnology.spotnav.ui.yearreview

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.yearreview.YearReview
import se.sensnology.spotnav.ui.history.CsvExportProvider
import java.io.File

/** The energy palette of the story and the picture: the card's `--spotnav-energy-*` tokens, light and dark. */
internal object YearReviewColours {
    fun of(tint: Tint, dark: Boolean): Int = Color.parseColor(
        when (tint) {
            Tint.SUN -> if (dark) "#f2b705" else "#c18b00"
            Tint.IMPORT -> if (dark) "#5b9bff" else "#3b82f6"
            Tint.EXPORT -> if (dark) "#34c26b" else "#16a34a"
            Tint.BATTERY -> if (dark) "#22c3b0" else "#0f9f8f"
            Tint.CAR -> if (dark) "#ec6fb5" else "#c2408f"
            Tint.HOUSE -> if (dark) "#9a8cf0" else "#6d5bd0"
            Tint.EXPENSIVE -> if (dark) "#f2564f" else "#dc2626"
        }
    )

    /** The story's deep blue behind the cover's gradient and the picture's lower corner. */
    val NIGHT: Int = Color.parseColor("#2a2f7a")

    fun art(id: String): Int = when (id) {
        "cover" -> R.drawable.yr_art_cover
        "saved" -> R.drawable.yr_art_saved
        "car" -> R.drawable.yr_art_car
        "sun" -> R.drawable.yr_art_sun
        "battery" -> R.drawable.yr_art_battery
        "fuse" -> R.drawable.yr_art_fuse
        "expensive" -> R.drawable.yr_art_expensive
        else -> R.drawable.yr_art_thanks
    }
}

/**
 * The year review's picture, drawn natively at 1080 × 1350 as the card draws its own ([YearReviewShareLayout] places
 * everything), and handed to the share sheet or saved in Pictures/SpotNav.
 */
internal object YearReviewPicture {
    fun draw(context: Context, text: YearReviewText, review: YearReview): Bitmap {
        val w = YearReviewShareLayout.WIDTH
        val h = YearReviewShareLayout.HEIGHT
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), YearReviewColours.of(Tint.HOUSE, false), YearReviewColours.NIGHT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        val random = YearReviewShareLayout.seeded(review.year)
        repeat(70) {
            paint.color = Color.argb((random() * 0.5 * 255).toInt(), 255, 255, 255)
            val x = random() * w
            val y = random() * 420
            val r = random() * 3 + 1
            canvas.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), paint)
        }
        context.getDrawable(R.drawable.app_icon)?.let { logo ->
            logo.setBounds(
                YearReviewShareLayout.LOGO_X, YearReviewShareLayout.LOGO_Y,
                YearReviewShareLayout.LOGO_X + YearReviewShareLayout.LOGO_SIZE, YearReviewShareLayout.LOGO_Y + YearReviewShareLayout.LOGO_SIZE
            )
            logo.draw(canvas)
        }
        val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        fun text(line: String, x: Int, y: Int, px: Int, face: Typeface, colour: Int) {
            paint.typeface = face
            paint.textSize = px.toFloat()
            paint.color = colour
            canvas.drawText(line, x.toFloat(), y.toFloat(), paint)
        }
        val white = Color.WHITE
        val soft = Color.argb(230, 255, 255, 255)
        text("SpotNav", 80, 110, 40, medium, white)
        text(text.say("yearReview.share.title", "year" to review.year.toString()), 80, 240, 92, bold, white)
        text(YearReviewShareLayout.subtitle(text, review), 80, 305, 46, regular, soft)
        val tiles = YearReviewShareLayout.tiles(text, review)
        val boxes = YearReviewShareLayout.boxes(tiles.size)
        for ((tile, box) in tiles.zip(boxes)) {
            val rect = RectF(box.x.toFloat(), box.y.toFloat(), (box.x + box.width).toFloat(), (box.y + box.height).toFloat())
            paint.color = YearReviewColours.of(tile.tint, false)
            paint.setShadowLayer(30f, 0f, 10f, Color.argb(77, 0, 0, 0))
            canvas.drawRoundRect(rect, 36f, 36f, paint)
            paint.clearShadowLayer()
            context.getDrawable(YearReviewColours.art(tile.art))?.let { art ->
                art.setBounds(box.artX, box.artY, box.artX + box.artSize, box.artY + box.artSize)
                art.draw(canvas)
            }
            paint.typeface = regular
            paint.textSize = box.labelPx.toFloat()
            val lines = YearReviewShareLayout.wrap({ paint.measureText(it) }, tile.label, box.labelMaxWidth)
            lines.forEachIndexed { index, line -> text(line, box.labelX, box.labelY + index * box.labelLineHeight, box.labelPx, medium, Color.argb(235, 255, 255, 255)) }
            paint.typeface = bold
            val size = YearReviewShareLayout.fit({ value, px -> paint.textSize = px.toFloat(); paint.measureText(value) }, tile.value, box.valueMaxWidth, box.valuePx)
            text(tile.value, box.valueX, box.valueY, size, bold, white)
        }
        text(text.say("yearReview.share.foot"), 80, 1270, 34, medium, Color.argb(235, 255, 255, 255))
        return bitmap
    }

    /** The picture written where the share sheet can read it ([CsvExportProvider]'s folder), and its address. */
    fun forSharing(context: Context, bitmap: Bitmap, name: String): Uri {
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return CsvExportProvider.writeBytes(context, name, bytes)
    }

    fun shareIntent(uri: Uri, title: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        title
    )

    /** Whether this Android saves into Pictures without asking for a storage permission (10 and later). */
    val canSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Save the picture in Pictures/SpotNav; `false` where it could not be. */
    fun save(context: Context, bitmap: Bitmap, name: String): Boolean {
        if (!canSave) return false
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "SpotNav")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (failure: Exception) {
                resolver.delete(uri, null, null)
                throw failure
            }
            true
        }.getOrDefault(false)
    }
}
