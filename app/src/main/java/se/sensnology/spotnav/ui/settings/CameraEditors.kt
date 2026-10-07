package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import se.sensnology.spotnav.ui.common.openEditor
import se.sensnology.spotnav.vehicles.CameraFrames

/**
 * The camera's two editors on the Settings page, as the Home Assistant card has them: the frame editor
 * (the camera's picture now, fetched from Home Assistant, with a frame to drag and resize, a preview of
 * what is inside it, the whole picture, another try, and Save), and a car's reference pictures (each with
 * its thumbnail and when it was taken, "Take reference picture now", "Take night picture" and Delete).
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

/**
 * A car's reference pictures: each with its thumbnail ([thumbnail] fills it when it has one) and when it
 * was taken ([takenAt]); "Take reference
 * picture now" as the dialog's own button, "Take night picture" and, with any picture, Delete. [act]
 * takes a picture now (a kind) or deletes the car's pictures (`null`), answering `null` when it took or
 * the words to show; a button that took closes the dialog.
 */
internal fun ViewScope.openReferenceEditor(
    carName: String,
    pictures: List<ReferencePicture>,
    thumbnail: (ReferencePicture, (Bitmap?) -> Unit) -> Unit,
    takenAt: (String) -> String,
    act: (PictureKind?, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    body.addView(mutedText(t(R.string.reference_intro, carName), bottom = 8))
    if (pictures.isNotEmpty()) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (picture in pictures) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
            val image = thumbnailView(picture.kind, 72)
            column.addView(image)
            column.addView(mutedText("${pictureKindText(picture.kind)} · ${takenAt(picture.takenAt)}", top = 2))
            row.addView(column)
            thumbnail(picture) { bitmap ->
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                }
            }
        }
        body.addView(row)
    }
    val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    body.addView(tools)
    body.addView(mutedText(t(R.string.reference_help), top = 4))
    val error = errorLine().also { body.addView(it) }
    lateinit var dialog: AlertDialog
    fun button(label: String, kind: PictureKind?) = plainButton(label) {}.also { button ->
        button.setOnClickListener {
            button.isEnabled = false
            error.visibility = View.GONE
            act(kind) { message ->
                if (isDestroyed) return@act
                button.isEnabled = true
                if (message == null) {
                    dialog.dismiss()
                } else {
                    error.text = message
                    error.visibility = View.VISIBLE
                }
            }
        }
    }
    tools.addView(button(t(R.string.reference_take_night), PictureKind.NIGHT))
    if (pictures.isNotEmpty()) tools.addView(button(t(R.string.reference_delete), null))
    // The dialog's own button takes the daylight picture.
    dialog = openEditor(t(R.string.reference_label), body, error, positive = t(R.string.reference_take)) { done ->
        act(PictureKind.DAY, done)
    }
}
