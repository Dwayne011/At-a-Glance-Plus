package com.ataglanceplus.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import com.ataglanceplus.live.LiveTiming
import com.ataglanceplus.live.RaceSession

/**
 * A circuit's outline with each car on it at its share of the lap, in its team's colour: the
 * top three larger with a white ring, cars in the pits or out of the session faded. The outline
 * is from the f1-circuits project; it may not start at the start line on every circuit.
 */
object TrackMap {

    /** Each circuit's outline as a path, read once from its drawing. */
    private val paths = java.util.concurrent.ConcurrentHashMap<Int, Path>()

    private fun outline(context: Context, res: Int): Path? = paths[res] ?: runCatching {
        val parser = context.resources.getXml(res)
        var data: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && data == null) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "path") {
                data = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData")
            }
        }
        androidx.core.graphics.PathParser.createPathFromPathData(data ?: return null)
    }.getOrNull()?.also { paths[res] = it }

    /** The map, [sizeDp] square; null when the circuit's outline is not known. */
    fun draw(context: Context, session: RaceSession, cars: List<LiveTiming.Car>, sizeDp: Float): Bitmap? {
        val d = context.resources.displayMetrics.density
        val size = (sizeDp * d).toInt()
        val res = CircuitIcons.find(session.circuitCity, session.circuitName, aod = false) ?: return null
        val base = outline(context, res) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF().also { base.computeBounds(it, true) }
        val inset = size * 0.08f
        val scale = minOf((size - 2 * inset) / bounds.width(), (size - 2 * inset) / bounds.height())
        val path = Path(base).apply {
            transform(Matrix().apply {
                setTranslate(-bounds.centerX(), -bounds.centerY())
                postScale(scale, scale)
                postTranslate(size / 2f, size / 2f)
            })
        }
        val unit = size / 84f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * unit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x8CFFFFFF.toInt()
        }
        canvas.drawPath(path, paint)
        val measure = PathMeasure(path, false)
        val point = FloatArray(2)
        paint.style = Paint.Style.FILL
        // Back markers first, so the leaders are drawn on top.
        cars.sortedByDescending { it.position }.forEach { car ->
            measure.getPosTan(measure.length * (car.lapFraction % 1f), point, null)
            val top = car.position <= 3
            val alpha = if (car.inPit || car.out) 90 else 255
            val r = (if (top) 4.2f else 2.8f) * unit
            paint.color = if (top) 0xFFFFFFFF.toInt() else 0xFF0B1E1A.toInt()
            paint.alpha = alpha
            canvas.drawCircle(point[0], point[1], r + (if (top) 1.4f else 0.9f) * unit, paint)
            paint.color = car.colour
            paint.alpha = alpha
            canvas.drawCircle(point[0], point[1], r, paint)
        }
        return bitmap
    }
}
