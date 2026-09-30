package com.journeyvisualizer.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.journeyvisualizer.app.data.geo.CityStop
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Draws one animation frame onto an [android.graphics.Canvas].
 * Used identically by the Compose preview (via the native canvas) and the
 * MP4 exporter, so what you preview is what gets rendered.
 */
object FrameRenderer {

    private const val ATTRIBUTION = "© OpenStreetMap contributors © CARTO © GeoNames"

    private val tilePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val routeUpcomingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 255, 255, 255)
    }
    private val routeTraveledPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val markerOuterPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val markerInnerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
    }
    private val textShadowPaint = Paint().apply {
        isAntiAlias = true
        color = Color.argb(200, 0, 0, 0)
    }
    private val scrimPaint = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0B1220")
    }
    private val dstRect = RectF()

    fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        engine: JourneyEngine,
        videoTimeSec: Double,
        durationSec: Double,
        mode: CameraMode,
        tileProvider: (TileKey) -> Bitmap?,
        title: String,
        accentColor: Int,
        cities: List<CityStop> = emptyList(),
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val cam = engine.cameraAt(videoTimeSec, durationSec, mode, width, height)
        drawTiles(canvas, width, height, cam, engine, tileProvider)

        val journeyTime = engine.journeyTimeAt(videoTimeSec, durationSec)
        drawRoute(canvas, width, height, engine, cam, journeyTime, accentColor)
        drawMarker(canvas, width, height, engine, cam, journeyTime, accentColor)
        drawCityMarkers(canvas, width, height, engine, cam, cities, accentColor)
        drawAttribution(canvas, width, height)
        drawTitle(canvas, title)
    }

    private fun drawTiles(
        canvas: Canvas,
        width: Int,
        height: Int,
        cam: CameraState,
        engine: JourneyEngine,
        tileProvider: (TileKey) -> Bitmap?,
    ) {
        val z0 = cam.zoom.toInt().coerceIn(TileMath.MIN_ZOOM, TileMath.MAX_ZOOM)
        val scale = 2.0.pow(cam.zoom - z0)
        val tilePx = (TileMath.TILE_SIZE * scale).toFloat()
        val cx = TileMath.lngToWorldX(cam.lng, cam.zoom)
        val cy = TileMath.latToWorldY(cam.lat, cam.zoom)
        for (key in engine.visibleTiles(cam, width, height)) {
            val bmp = tileProvider(key) ?: continue
            val left = (key.x * tilePx - cx + width / 2.0).toFloat()
            val top = (key.y * tilePx - cy + height / 2.0).toFloat()
            dstRect.set(left, top, left + tilePx, top + tilePx)
            canvas.drawBitmap(bmp, null, dstRect, tilePaint)
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        width: Int,
        height: Int,
        engine: JourneyEngine,
        cam: CameraState,
        journeyTimeMs: Long,
        accentColor: Int,
    ) {
        val pts = engine.journey.points
        if (pts.size < 2) return
        val stroke = max(6f, width / 130f)
        routeUpcomingPaint.strokeWidth = stroke
        routeTraveledPaint.strokeWidth = stroke
        routeTraveledPaint.color = accentColor

        // Decimate only for drawing; geometry and stats keep every point.
        val stride = max(1, pts.size / 4000)
        val upcoming = Path()
        val traveled = Path()
        var startedUpcoming = false
        var startedTraveled = false
        var i = 0
        while (i < pts.size) {
            val p = pts[i]
            val (sx, sy) = engine.toScreen(p.lat, p.lng, cam, width, height)
            if (!startedUpcoming) {
                upcoming.moveTo(sx, sy)
                startedUpcoming = true
            } else {
                upcoming.lineTo(sx, sy)
            }
            if (p.timeMs <= journeyTimeMs) {
                if (!startedTraveled) {
                    traveled.moveTo(sx, sy)
                    startedTraveled = true
                } else {
                    traveled.lineTo(sx, sy)
                }
            }
            i += stride
        }
        // Always include the final point so the line reaches the destination.
        val last = pts.last()
        val (lsx, lsy) = engine.toScreen(last.lat, last.lng, cam, width, height)
        upcoming.lineTo(lsx, lsy)
        if (last.timeMs <= journeyTimeMs) traveled.lineTo(lsx, lsy)

        canvas.drawPath(upcoming, routeUpcomingPaint)
        canvas.drawPath(traveled, routeTraveledPaint)
    }

    private fun drawMarker(
        canvas: Canvas,
        width: Int,
        height: Int,
        engine: JourneyEngine,
        cam: CameraState,
        journeyTimeMs: Long,
        accentColor: Int,
    ) {
        val (lat, lng) = engine.positionAt(journeyTimeMs)
        val (sx, sy) = engine.toScreen(lat, lng, cam, width, height)
        val r = max(10f, width / 90f)
        markerInnerPaint.color = accentColor
        canvas.drawCircle(sx, sy, r, markerOuterPaint)
        canvas.drawCircle(sx, sy, r * 0.68f, markerInnerPaint)
    }

    private fun drawCityMarkers(
        canvas: Canvas,
        width: Int,
        height: Int,
        engine: JourneyEngine,
        cam: CameraState,
        cities: List<CityStop>,
        accentColor: Int,
    ) {
        if (cities.isEmpty()) return
        textPaint.textSize = height * 0.028f
        textShadowPaint.textSize = height * 0.028f
        val dotR = max(5f, width / 220f)
        markerInnerPaint.color = accentColor
        for (stop in cities) {
            val (sx, sy) = engine.toScreen(stop.city.lat, stop.city.lng, cam, width, height)
            if (sx < -100 || sy < -50 || sx > width + 100 || sy > height + 50) continue
            canvas.drawCircle(sx, sy, dotR * 1.6f, markerOuterPaint)
            canvas.drawCircle(sx, sy, dotR, markerInnerPaint)
            val lx = sx + dotR * 2.6f
            canvas.drawText(stop.city.name, lx + 2f, sy + 2f, textShadowPaint)
            canvas.drawText(stop.city.name, lx, sy, textPaint)
        }
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int) {
        textPaint.textSize = height * 0.024f
        val pad = width * 0.02f
        val tw = textPaint.measureText(ATTRIBUTION)
        val ascent = -textPaint.ascent()
        val x = width - tw - pad * 1.5f
        val y = height - pad * 1.5f
        canvas.drawRect(
            x - pad / 2, y - ascent - pad / 2,
            x + tw + pad / 2, y + pad / 2, scrimPaint
        )
        canvas.drawText(ATTRIBUTION, x, y, textPaint)
    }

    private fun drawTitle(canvas: Canvas, title: String) {
        if (title.isBlank()) return
        textPaint.textSize = canvas.width * 0.045f
        val x = canvas.width * 0.04f
        val y = canvas.height * 0.07f
        canvas.drawText(title, x + 2f, y + 2f, textShadowPaint)
        canvas.drawText(title, x, y, textPaint)
    }
}
