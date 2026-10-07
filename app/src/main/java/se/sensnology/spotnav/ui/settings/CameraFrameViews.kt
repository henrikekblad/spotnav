package se.sensnology.spotnav.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import se.sensnology.spotnav.ha.client.CameraPicture
import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.vehicles.CameraFrames
import kotlin.math.roundToInt

/** A picture Home Assistant sent, decoded no larger than it is shown (on the background executor). */
internal object PictureDecoding {
    /** The camera's picture now, for the frame editor: at most this many pixels along its longer side. */
    const val SNAPSHOT_EDGE = 1600

    /** A reference picture's thumbnail (Home Assistant sends at most 240 pixels). */
    const val THUMBNAIL_EDGE = 240

    fun decode(picture: CameraPicture, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture.jpeg, 0, picture.jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = CameraFrames.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(picture.jpeg, 0, picture.jpeg.size, options)
    }
}

/**
 * The camera's picture with the frame over it: the frame moves when dragged and resizes from a corner,
 * with a finger. What is outside the frame is dimmed. [onChange] hears every new frame.
 */
@SuppressLint("ViewConstructor")
internal class FrameView(context: Context, private val accent: Int, private val maxHeightPx: Int) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var frame: CameraFrame = CameraFrames.WHOLE
        set(value) {
            field = value
            invalidate()
        }

    var onChange: (CameraFrame) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val reachPx = 28 * density
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = DIM }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = accent
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val target = Rect()
    private val box = RectF()

    // One drag at a time: what it holds, where it started, and the frame then.
    private var grip: CameraFrames.Grip? = null
    private var downX = 0f
    private var downY = 0f
    private var start = CameraFrames.WHOLE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val picture = bitmap
        if (picture == null || picture.width <= 0) {
            setMeasuredDimension(available, 0)
            return
        }
        var width = available
        var height = (available.toLong() * picture.height / picture.width).toInt()
        if (height > maxHeightPx) {
            height = maxHeightPx
            width = (maxHeightPx.toLong() * picture.width / picture.height).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val picture = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        target.set(0, 0, width, height)
        canvas.drawBitmap(picture, null, target, picturePaint)
        box.set(frame.x.toFloat() * w, frame.y.toFloat() * h, (frame.x + frame.w).toFloat() * w, (frame.y + frame.h).toFloat() * h)
        canvas.drawRect(0f, 0f, w, box.top, dimPaint)
        canvas.drawRect(0f, box.bottom, w, h, dimPaint)
        canvas.drawRect(0f, box.top, box.left, box.bottom, dimPaint)
        canvas.drawRect(box.right, box.top, w, box.bottom, dimPaint)
        canvas.drawRect(box, linePaint)
        val radius = 7 * density
        canvas.drawCircle(box.left, box.top, radius, handlePaint)
        canvas.drawCircle(box.right, box.top, radius, handlePaint)
        canvas.drawCircle(box.left, box.bottom, radius, handlePaint)
        canvas.drawCircle(box.right, box.bottom, radius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val held = CameraFrames.grip(
                    frame, (event.x / width).toDouble(), (event.y / height).toDouble(),
                    (reachPx / width).toDouble(), (reachPx / height).toDouble()
                ) ?: return false
                grip = held
                downX = event.x
                downY = event.y
                start = frame
                // The page does not scroll while the frame is dragged.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val held = grip ?: return false
                frame = CameraFrames.drag(start, held, ((event.x - downX) / width).toDouble(), ((event.y - downY) / height).toDouble())
                onChange(frame)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (grip == null) return false
                grip = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** What is outside the frame: the picture, dimmed. */
        const val DIM = 0x88000000.toInt()
    }
}

/** What is compared: the frame's part of the picture, as large as fits. */
@SuppressLint("ViewConstructor")
internal class CropPreview(context: Context, private val maxHeightPx: Int) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var frame: CameraFrame = CameraFrames.WHOLE
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val target = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val picture = bitmap
        if (picture == null) {
            setMeasuredDimension(available, 0)
            return
        }
        val crop = CameraFrames.crop(frame, picture.width, picture.height)
        val cropWidth = (crop.right - crop.left).coerceAtLeast(1)
        val cropHeight = (crop.bottom - crop.top).coerceAtLeast(1)
        var width = available
        var height = (available.toDouble() * cropHeight / cropWidth).roundToInt()
        if (height > maxHeightPx) {
            height = maxHeightPx
            width = (maxHeightPx.toDouble() * cropWidth / cropHeight).roundToInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val picture = bitmap ?: return
        val crop = CameraFrames.crop(frame, picture.width, picture.height)
        source.set(crop.left, crop.top, crop.right, crop.bottom)
        target.set(0, 0, width, height)
        canvas.drawBitmap(picture, source, target, paint)
    }
}
