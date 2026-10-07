package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.ha.client.CameraCommands
import se.sensnology.spotnav.ha.dashboard.PictureKind
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import se.sensnology.spotnav.vehicles.CameraFrames
import se.sensnology.spotnav.vehicles.CameraSetup

/**
 * The camera's two editors on the Settings page, as the Home Assistant card has them: the frame editor
 * (the camera's picture now, fetched from Home Assistant, with a frame to drag and resize, a preview of
 * what is inside it, the whole picture, another try, and Save), and a car's reference pictures (a day and a
 * night slot, each taken, retaken or deleted in place).
 * The app processes no picture: Home Assistant crops and scales, the editors only show.
 */

/** The words for a camera refusal. */
internal fun ViewScope.cameraFailureText(code: String?): String = t(
    when (code) {
        CameraCommands.NO_CAMERA -> R.string.camera_error_no_camera
        CameraCommands.NO_PICTURE -> R.string.camera_error_no_picture
        else -> R.string.paired_error_generic
    }
)

private fun ViewScope.mutedText(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
    this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
}

private fun ViewScope.plainButton(label: String, onClick: () -> Unit) = Button(context).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
}

/** A dialog's text button, as AlertDialog draws Cancel and Save: no box, the accent colour. */
private fun ViewScope.dialogButton(label: String, onClick: () -> Unit) =
    Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
        text = label
        setTextColor(accent)
        setOnClickListener { onClick() }
    }

/** The status and navigation bars' room, on every Android version this app runs on. */
@Suppress("DEPRECATION")
private fun systemBars(insets: WindowInsets): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { Rect(it.left, it.top, it.right, it.bottom) }
    } else {
        Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

/**
 * The frame editor, full screen: [load] fetches the camera's picture now (decoded), [save] writes the
 * frame (`null`: the whole picture) and answers `null` when it took or the words to show.
 */
internal fun ViewScope.openFrameEditor(
    current: CameraFrame?,
    load: ((CameraCommands.Outcome, Bitmap?) -> Unit) -> Unit,
    save: (CameraFrame?, (String?) -> Unit) -> Unit
) {
    val darkTheme = AppThemeSettings.isDark(context)
    val dialog = Dialog(context, if (darkTheme) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val screenHeight = resources.displayMetrics.heightPixels
    val page = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    page.addView(TextView(context).apply {
        text = t(R.string.camera_frame_label); textSize = 20f; setTextColor(dark); typeface = Typeface.DEFAULT_BOLD
    })
    page.addView(mutedText(t(R.string.camera_frame_intro), top = 4, bottom = 8))
    val status = mutedText(t(R.string.camera_frame_loading), bottom = 8)
    page.addView(status)
    val picture = FrameView(context, accent, maxHeightPx = (screenHeight * 0.45).toInt()).apply {
        contentDescription = t(R.string.camera_frame_area)
        frame = current?.let { CameraFrames.clamp(it) } ?: CameraFrames.WHOLE
    }
    page.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    })
    val previewLabel = mutedText(t(R.string.camera_frame_preview), top = 8, bottom = 4).apply { visibility = View.GONE }
    val preview = CropPreview(context, maxHeightPx = (screenHeight * 0.3).toInt()).apply { visibility = View.GONE }
    picture.onChange = { frame -> preview.frame = frame }
    preview.frame = picture.frame
    var loading = false
    lateinit var retry: Button
    val whole = plainButton(t(R.string.camera_frame_whole)) {
        picture.frame = CameraFrames.WHOLE
        preview.frame = CameraFrames.WHOLE
    }.apply { isEnabled = false }

    fun fetch() {
        if (loading) return
        loading = true
        status.text = t(R.string.camera_frame_loading)
        status.visibility = View.VISIBLE
        retry.visibility = View.GONE
        load { outcome, bitmap ->
            loading = false
            if (!dialog.isShowing) return@load
            if (bitmap != null) {
                picture.bitmap = bitmap
                preview.bitmap = bitmap
                preview.visibility = View.VISIBLE
                previewLabel.visibility = View.VISIBLE
                status.visibility = View.GONE
                whole.isEnabled = true
            } else {
                status.text = cameraFailureText((outcome as? CameraCommands.Outcome.Failed)?.code)
                retry.visibility = View.VISIBLE
            }
        }
    }
    retry = plainButton(t(R.string.camera_frame_retry)) { fetch() }.apply { visibility = View.GONE }
    page.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
        addView(whole)
        addView(retry)
    })
    page.addView(previewLabel)
    page.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    })
    val error = errorLine()
    page.addView(error)
    lateinit var saveButton: Button
    saveButton = dialogButton(t(R.string.paired_save)) {
        if (picture.bitmap == null) {
            error.text = t(R.string.camera_frame_loading)
            error.visibility = View.VISIBLE
            return@dialogButton
        }
        saveButton.isEnabled = false
        error.visibility = View.GONE
        save(CameraFrames.forSave(picture.frame)) { message ->
            if (isDestroyed) return@save
            if (message == null) {
                dialog.dismiss()
            } else {
                saveButton.isEnabled = true
                error.text = message
                error.visibility = View.VISIBLE
            }
        }
    }
    // Cancel and Save as every other editor has them: text buttons at the bottom right.
    page.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(12), 0, 0)
        addView(dialogButton(t(android.R.string.cancel)) { dialog.dismiss() })
        addView(saveButton)
    })
    val scroller = ScrollView(context).apply {
        setBackgroundColor(appBackground)
        addView(page)
    }
    // The page keeps clear of the status and navigation bars (the window is drawn edge to edge).
    scroller.setOnApplyWindowInsetsListener { view, insets ->
        val bars = systemBars(insets)
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    dialog.setContentView(scroller)
    dialog.window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundDrawable(ColorDrawable(appBackground))
    }
    // The pictures go with the editor.
    dialog.setOnDismissListener {
        picture.bitmap = null
        preview.bitmap = null
    }
    dialog.show()
    fetch()
}

/** A reference picture's kind in words. */
internal fun ViewScope.pictureKindText(kind: PictureKind): String =
    t(if (kind == PictureKind.DAY) R.string.reference_day else R.string.reference_night)

/** A thumbnail as the Settings page shows one: [heightDp] high, its width as the picture's. */
internal fun ViewScope.thumbnailView(kind: PictureKind, heightDp: Int): ImageView = ImageView(context).apply {
    contentDescription = pictureKindText(kind)
    adjustViewBounds = true
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp)).apply { marginEnd = dp(8) }
    visibility = View.GONE
}

/** What a slot's button or Delete answered: the car's pictures after it, or the words to show in that slot. */
internal sealed interface ReferenceAnswer {
    data class Pictures(val pictures: List<ReferencePicture>) : ReferenceAnswer
    data class Refused(val message: String) : ReferenceAnswer
}

/**
 * A car's reference pictures, titled with the car's name: one slot each for day and night, side by side,
 * with its thumbnail ([thumbnail] fills it) and when it was taken ([takenAt]), or "No picture"; one button
 * that takes it ("Take day picture", "Take night picture", or "Retake" over a picture); and a quiet Delete
 * over a picture. [act] takes that kind's picture now, or deletes it (`delete`). A slot shows its own
 * progress and refusal, and an answer updates it in place: the dialog stays open until Close.
 */
internal fun ViewScope.openReferenceEditor(
    carName: String,
    pictures: List<ReferencePicture>,
    thumbnail: (ReferencePicture, (Bitmap?) -> Unit) -> Unit,
    takenAt: (String) -> String,
    act: (PictureKind, Boolean, (ReferenceAnswer) -> Unit) -> Unit
) {
    val body = editorBody()
    body.addView(mutedText(t(R.string.reference_intro), bottom = 8))
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    body.addView(row)
    val help = mutedText(t(R.string.reference_help), top = 8)
    body.addView(help)
    var current = pictures
    val busy = mutableSetOf<PictureKind>()
    val paints = mutableMapOf<PictureKind, (String?) -> Unit>()
    lateinit var dialog: AlertDialog

    for ((index, kind) in listOf(PictureKind.DAY, PictureKind.NIGHT).withIndex()) {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
        })
        column.addView(TextView(context).apply {
            text = pictureKindText(kind); textSize = 15f; setTextColor(dark); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        val frame = FrameLayout(context)
        column.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        val image = ImageView(context).apply {
            contentDescription = pictureKindText(kind)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(cardBackground) }
        }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val empty = TextView(context).apply {
            text = t(R.string.reference_empty); textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
        }
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val spinner = ProgressBar(context).apply { isIndeterminate = true }
        frame.addView(spinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        val caption = mutedText("", top = 4)
        column.addView(caption)
        val status = TextView(context).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
        column.addView(status)
        val take = plainButton("") {}
        column.addView(take, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        val remove = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = t(R.string.reference_delete)
            isAllCaps = false
            textSize = 13f
            setTextColor(muted)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            minHeight = dp(36); minimumHeight = dp(36)
            contentDescription = "${t(R.string.reference_delete)}: ${pictureKindText(kind)}"
        }
        column.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // The thumbnail asked for last: an answer for a picture since replaced is not shown.
        var shownFor: String? = null
        fun paint(error: String?) {
            val slot = CameraSetup.slots(current).first { it.kind == kind }
            val picture = slot.picture
            val working = kind in busy
            take.text = t(slot.takeLabel)
            take.isEnabled = !working
            remove.visibility = if (slot.canDelete) View.VISIBLE else View.INVISIBLE
            remove.isEnabled = !working
            // An empty slot keeps the caption's line, so both slots' buttons stand level.
            caption.text = picture?.let { takenAt(it.takenAt) } ?: ""
            caption.visibility = if (picture == null) View.INVISIBLE else View.VISIBLE
            spinner.visibility = if (working) View.VISIBLE else View.GONE
            frame.alpha = if (working) 0.6f else 1f
            status.text = if (working) t(R.string.reference_taking) else error ?: ""
            status.setTextColor(if (working) muted else ERROR_RED)
            status.visibility = if (working || error != null) View.VISIBLE else View.GONE
            if (picture == null) {
                shownFor = null
                image.setImageBitmap(null)
                empty.visibility = if (working) View.GONE else View.VISIBLE
                return
            }
            empty.visibility = View.GONE
            if (shownFor != picture.takenAt) {
                shownFor = picture.takenAt
                val wanted = picture.takenAt
                image.setImageBitmap(null)
                thumbnail(picture) { bitmap ->
                    if (bitmap != null && shownFor == wanted && dialog.isShowing) image.setImageBitmap(bitmap)
                }
            }
        }
        paints[kind] = ::paint
        fun run(delete: Boolean) {
            if (kind in busy) return
            busy += kind
            paint(null)
            act(kind, delete) { answer ->
                if (isDestroyed) return@act
                busy -= kind
                when (answer) {
                    is ReferenceAnswer.Pictures -> {
                        current = answer.pictures
                        help.visibility = if (current.isEmpty()) View.VISIBLE else View.GONE
                        for ((other, repaint) in paints) if (other == kind || other !in busy) repaint(null)
                    }
                    is ReferenceAnswer.Refused -> paint(answer.message)
                }
            }
        }
        take.setOnClickListener { run(delete = false) }
        remove.setOnClickListener { run(delete = true) }
    }
    help.visibility = if (current.isEmpty()) View.VISIBLE else View.GONE
    dialog = AlertDialog.Builder(context)
        .setTitle(t(R.string.reference_title, carName))
        .setView(ScrollView(context).apply { addView(body) })
        .setPositiveButton(t(R.string.reference_close), null)
        .create()
    dialog.show()
    for (paint in paints.values) paint(null)
}

/** A slot's refusal, in the red the editors' error lines use. */
private const val ERROR_RED = 0xFFD65C5C.toInt()
