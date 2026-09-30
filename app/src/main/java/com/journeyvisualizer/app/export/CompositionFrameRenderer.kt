package com.journeyvisualizer.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.journeyvisualizer.app.animation.AnimationFrameState
import com.journeyvisualizer.app.animation.AnimationPoint
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.ExportSpec
import com.journeyvisualizer.app.composition.OverlayPosition
import com.journeyvisualizer.app.composition.OverlayTextAlign
import com.journeyvisualizer.app.composition.OverlayTextWeight
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.BasemapStyles
import com.journeyvisualizer.app.map.TileKey
import com.journeyvisualizer.app.map.TileMath
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.ui.util.formatDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Deterministic Canvas frame painter (Phase 7).
 *
 * Renders one export frame at the exact export resolution from a single
 * [FrameRequest]: video-clock time → Phase 5 animation state → camera →
 * route/marker/overlays. It consumes the SAME [ExportSpec] and the SAME
 * [AnimationFrameState] semantics as the Phase 6 preview — there is no
 * second marker-movement, route-progress, or timestamp algorithm.
 *
 * Sizing is resolution-proportional: one export unit `u = width / 360`
 * plays the role of dp, so graphics keep the same visual proportions as
 * the preview on a 360 dp-wide phone while text is rasterized at the real
 * export resolution (never an upscaled low-res overlay).
 *
 * App chrome is never drawn — only map, route, markers, and the
 * composition's own overlays appear in the frame.
 */
class CompositionFrameRenderer(
    context: Context,
    private val spec: ExportSpec,
    points: List<AnimationPoint>,
    private val planner: ExportCameraPlanner,
) {
    /** Everything that defines one frame; all derived from the frame index. */
    data class FrameRequest(
        val videoTimeMs: Long,
        val region: FrameRegion,
        val anim: AnimationFrameState,
        val camera: ExportCamera,
    )

    data class TileStats(val totalTiles: Int, val missingTiles: Int)

    private val metrics = context.resources.displayMetrics
    /** User font-scale, so export type matches the preview's proportions. */
    private val fontScale: Float =
        (metrics.scaledDensity / metrics.density).takeIf { it > 0f } ?: 1f

    private val styleSpec = BasemapStyles.specFor(spec.mapStyle)
    private val layerSpecs = listOfNotNull(styleSpec.base, styleSpec.overlay)
    private val attribution = BasemapStyles.attributionFor(spec.mapStyle)

    private data class Pt(val lat: Double, val lng: Double, val origIndex: Int)

    /** Route decimated to ≤ 4000 points (matches the preview's display cap). */
    private val routePts: List<Pt> = run {
        val n = points.size
        if (n == 0) return@run emptyList()
        val stride = (n + MAX_ROUTE_PTS - 1) / MAX_ROUTE_PTS
        val out = ArrayList<Pt>((n + stride - 1) / stride + 1)
        for (i in points.indices step stride) {
            val p = points[i]
            out.add(Pt(p.lat, p.lng, i))
        }
        val last = points.last()
        if (out.last().origIndex != n - 1) out.add(Pt(last.lat, last.lng, n - 1))
        out
    }

    private val textScale: Float = spec.textSize.scale
    private val bold: Boolean = spec.textWeight == OverlayTextWeight.BOLD

    /**
     * Paint one frame. [tileLayers] returns the base/overlay bitmaps for a
     * tile (memory/disk only during encoding — no network on the hot path).
     * Returns tile coverage stats for the missing-tile policy.
     */
    fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        req: FrameRequest,
        tileLayers: (TileKey) -> List<Bitmap?>,
    ): TileStats {
        val u = width / 360f
        canvas.drawColor(BG_COLOR)

        val stats = drawTiles(canvas, width, height, req.camera, tileLayers)
        if (spec.routeVisible) drawRoute(canvas, width, height, req)
        if (spec.startEndMarkers) drawStartEnd(canvas, width, height, req, u)
        drawAnimatedMarker(canvas, width, height, req, u)
        drawAttribution(canvas, width, height, u)
        drawOverlays(canvas, width, height, req, u)
        if (req.region != FrameRegion.JOURNEY) drawIntroOutro(canvas, width, height, req, u)
        return stats
    }

    // ------------------------------------------------------------------
    // Map tiles
    // ------------------------------------------------------------------

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileDst = RectF()

    private fun drawTiles(
        canvas: Canvas,
        width: Int,
        height: Int,
        camera: ExportCamera,
        tileLayers: (TileKey) -> List<Bitmap?>,
    ): TileStats {
        val minZ = layerSpecs.minOf { it.minZoom }
        val maxZ = layerSpecs.minOf { it.maxZoom }
        val keys = planner.visibleTileKeys(camera, minZ, maxZ)
        val z0 = camera.zoom.toInt().coerceIn(minZ, maxZ)
        val scale = 2.0.pow(camera.zoom - z0)
        val tilePx = (TileMath.TILE_SIZE * scale).toFloat()
        val cx = TileMath.lngToWorldX(camera.lng, camera.zoom)
        val cy = TileMath.latToWorldY(camera.lat, camera.zoom)
        var missing = 0
        for (key in keys) {
            val layers = tileLayers(key)
            if (layers.firstOrNull() == null) missing++
            val left = (key.x * TileMath.TILE_SIZE * scale - (cx - width / 2.0)).toFloat()
            val top = (key.y * TileMath.TILE_SIZE * scale - (cy - height / 2.0)).toFloat()
            tileDst.set(left, top, left + tilePx, top + tilePx)
            for (bmp in layers) {
                if (bmp != null) canvas.drawBitmap(bmp, null, tileDst, tilePaint)
            }
        }
        return TileStats(keys.size, missing)
    }

    // ------------------------------------------------------------------
    // Route + markers
    // ------------------------------------------------------------------

    private fun project(lat: Double, lng: Double, cam: ExportCamera, w: Int, h: Int): PointF {
        val wx = TileMath.lngToWorldX(lng, cam.zoom)
        val wy = TileMath.latToWorldY(lat, cam.zoom)
        val ccx = TileMath.lngToWorldX(cam.lng, cam.zoom)
        val ccy = TileMath.latToWorldY(cam.lat, cam.zoom)
        return PointF((wx - ccx + w / 2f).toFloat(), (wy - ccy + h / 2f).toFloat())
    }

    /** Builds an antimeridian-safe path: jumps wider than half the frame lift the pen. */
    private fun buildPath(pts: List<Pt>, cam: ExportCamera, w: Int, h: Int): Path {
        val path = Path()
        var prevX = Float.NaN
        for (pt in pts) {
            val p = project(pt.lat, pt.lng, cam, w, h)
            if (prevX.isNaN() || abs(p.x - prevX) > w / 2f) path.moveTo(p.x, p.y)
            else path.lineTo(p.x, p.y)
            prevX = p.x
        }
        return path
    }

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun drawRoute(canvas: Canvas, width: Int, height: Int, req: FrameRequest) {
        if (routePts.size < 2) return
        val alpha = (spec.routeOpacity.coerceIn(0.2f, 1f) * 255).toInt()
        val stroke = max(6f, width / 160f) * spec.routeWidthScale
        if (spec.routeDrawMode == RouteDrawMode.FULL) {
            routePaint.color = withAlpha(ROUTE_COLOR, alpha)
            routePaint.strokeWidth = stroke
            canvas.drawPath(buildPath(routePts, req.camera, width, height), routePaint)
        } else {
            // Dim full route underneath (matches the preview's progressive mode).
            routePaint.color = withAlpha(ROUTE_COLOR_DIM, alpha)
            routePaint.strokeWidth = stroke
            canvas.drawPath(buildPath(routePts, req.camera, width, height), routePaint)
            // Emphasized traveled prefix, ending at the live marker position.
            val traveled = routePts.filter { it.origIndex < req.anim.traveledPointCount } +
                Pt(req.anim.lat, req.anim.lng, Int.MAX_VALUE)
            if (traveled.size >= 2) {
                routePaint.color = withAlpha(TRAVELED_COLOR, alpha)
                routePaint.strokeWidth = stroke * 1.25f
                canvas.drawPath(buildPath(traveled, req.camera, width, height), routePaint)
            }
        }
    }

    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val markerText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, u: Float) {
        markerFill.color = color
        canvas.drawCircle(x, y, r, markerFill)
        markerRim.strokeWidth = 2f * u
        canvas.drawCircle(x, y, r - u, markerRim)
    }

    private fun drawAnimatedMarker(canvas: Canvas, width: Int, height: Int, req: FrameRequest, u: Float) {
        if (spec.markerStyle == VideoMarkerStyle.HIDDEN) return
        val p = project(req.anim.lat, req.anim.lng, req.camera, width, height)
        val r = when (spec.markerStyle) {
            VideoMarkerStyle.STANDARD -> 12f
            VideoMarkerStyle.MINIMAL_DOT -> 7f
            VideoMarkerStyle.HIGHLIGHTED -> 14f
            VideoMarkerStyle.HIDDEN -> 0f
        } * u
        if (spec.markerStyle == VideoMarkerStyle.HIGHLIGHTED) {
            markerRim.strokeWidth = 3f * u
            canvas.drawCircle(p.x, p.y, r + 3f * u, markerRim)
        }
        drawDot(canvas, p.x, p.y, r, ANIMATED_COLOR, u)
    }

    private fun drawStartEnd(canvas: Canvas, width: Int, height: Int, req: FrameRequest, u: Float) {
        if (routePts.size < 2) return
        val r = 15f * u
        markerText.textSize = r * 0.95f
        val fm = markerText.fontMetrics
        val dy = -(fm.ascent + fm.descent) / 2
        val first = routePts.first()
        val last = routePts.last()
        val p0 = project(first.lat, first.lng, req.camera, width, height)
        val p1 = project(last.lat, last.lng, req.camera, width, height)
        drawDot(canvas, p0.x, p0.y, r, START_COLOR, u)
        canvas.drawText("S", p0.x, p0.y + dy, markerText)
        drawDot(canvas, p1.x, p1.y, r, END_COLOR, u)
        canvas.drawText("E", p1.x, p1.y + dy, markerText)
    }

    // ------------------------------------------------------------------
    // Attribution
    // ------------------------------------------------------------------

    private val attrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.RIGHT
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int, u: Float) {
        val (_, _, safeBottom) = safePadding(u)
        attrPaint.textSize = height * 0.022f
        attrPaint.setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        canvas.drawText(
            attribution,
            width - 8f * u,
            height - safeBottom - 8f * u,
            attrPaint,
        )
        attrPaint.clearShadowLayer()
    }

    // ------------------------------------------------------------------
    // Overlays
    // ------------------------------------------------------------------

    private data class Pad(val top: Float, val horiz: Float, val bottom: Float)

    private fun safePadding(u: Float): Pad =
        if (spec.aspectRatio == VideoAspectRatio.NINE_SIXTEEN) Pad(88f * u, 20f * u, 160f * u)
        else Pad(16f * u, 20f * u, 16f * u)

    private fun spToPx(sp: Float, u: Float): Float = sp * textScale * u * fontScale

    private data class TextLine(val text: String, val sp: Float, val bold: Boolean, val alpha: Float)

    private fun layoutAlign(): Layout.Alignment = when (spec.textAlign) {
        OverlayTextAlign.START -> Layout.Alignment.ALIGN_NORMAL
        OverlayTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        OverlayTextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
    }

    /** Draws a wrapped text block; returns its height. */
    private fun drawTextBlock(
        canvas: Canvas,
        lines: List<TextLine>,
        width: Int,
        x: Float,
        yTop: Float,
        u: Float,
        gapDp: Float = 2f,
    ): Float {
        var y = yTop
        val contentW = (width - 2 * x).toInt().coerceAtLeast(1)
        for ((index, line) in lines.withIndex()) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = spToPx(line.sp, u)
                typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                color = Color.WHITE
                this.alpha = (line.alpha * 255).toInt().coerceIn(0, 255)
            }
            paint.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, paint, contentW)
                .setAlignment(layoutAlign())
                .setIncludePad(true)
                .build()
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
            if (index < lines.lastIndex) y += gapDp * u
        }
        return y - yTop
    }

    private fun drawOverlays(canvas: Canvas, width: Int, height: Int, req: FrameRequest, u: Float) {
        val pad = safePadding(u)
        val lines = ArrayList<TextLine>()
        if (spec.title.isNotBlank()) lines.add(TextLine(spec.title, 20f, bold, 1f))
        if (spec.subtitle.isNotBlank()) lines.add(TextLine(spec.subtitle, 14f, false, 1f))
        if (spec.showLocation) {
            val point = req.anim.currentPoint
            lines.add(TextLine(point.displayName, 18f, bold, 1f))
            val sub = point.country
                ?: "%.4f°, %.4f°".format(Locale.US, point.lat, point.lng)
            lines.add(TextLine(sub, 13f, false, 1f))
        }
        if (spec.showDateTime) {
            lines.add(TextLine(formatDateTime(req.anim.displayedTimestampMs), 13f, false, 1f))
        }
        if (lines.isNotEmpty()) {
            val x = pad.horiz + 8f * u
            // Measure first so bottom-anchored blocks sit exactly.
            val h = measureBlock(lines, width, x, u)
            var yTop = when (spec.overlayPosition) {
                OverlayPosition.TOP, OverlayPosition.TOP_LEFT, OverlayPosition.TOP_RIGHT ->
                    pad.top + 8f * u
                else -> height - pad.bottom - 8f * u - h
            }
            if (spec.showProgress && spec.overlayPosition.name.startsWith("BOTTOM")) {
                // Keep clear of the progress bar below.
                yTop -= 64f * u
            }
            drawTextBlock(canvas, lines, width, x, yTop, u)
        }
        if (spec.showProgress) drawProgress(canvas, width, height, req, u)
    }

    private fun measureBlock(lines: List<TextLine>, width: Int, x: Float, u: Float): Float {
        var h = 0f
        val contentW = (width - 2 * x).toInt().coerceAtLeast(1)
        for ((index, line) in lines.withIndex()) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = spToPx(line.sp, u)
                typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            h += StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, paint, contentW)
                .setAlignment(layoutAlign())
                .setIncludePad(true)
                .build().height
            if (index < lines.lastIndex) h += 2f * u
        }
        return h
    }

    private val barTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    private fun drawProgress(canvas: Canvas, width: Int, height: Int, req: FrameRequest, u: Float) {
        val pad = safePadding(u)
        val barH = 4f * u
        val barBottom = height - pad.bottom - 26f * u
        val left = 16f * u
        val right = width - 16f * u
        val label = "%s / %s".format(
            Locale.US, formatMs(req.videoTimeMs), formatMs(spec.totalVideoMs),
        )
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spToPx(11f, u)
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        labelPaint.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        val labelY = barBottom - barH - 2f * u
        canvas.drawText(label, width / 2f, labelY, labelPaint)

        val fraction = (req.videoTimeMs.toFloat() / spec.totalVideoMs).coerceIn(0f, 1f)
        barTrack.color = 0x4DFFFFFF
        barFill.color = PROGRESS_COLOR
        barRect.set(left, barBottom - barH, right, barBottom)
        canvas.drawRoundRect(barRect, 2f * u, 2f * u, barTrack)
        barRect.set(left, barBottom - barH, left + (right - left) * fraction, barBottom)
        canvas.drawRoundRect(barRect, 2f * u, 2f * u, barFill)
    }

    // ------------------------------------------------------------------
    // Intro / outro cards
    // ------------------------------------------------------------------

    private val cardPaint = Paint().apply { color = Color.BLACK; alpha = 184 }

    private fun drawIntroOutro(canvas: Canvas, width: Int, height: Int, req: FrameRequest, u: Float) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), cardPaint)
        val lines = ArrayList<TextLine>()
        if (req.region == FrameRegion.INTRO) {
            lines.add(TextLine(spec.title.ifBlank { "My Journey" }, 26f, true, 1f))
            if (spec.subtitle.isNotBlank()) lines.add(TextLine(spec.subtitle, 16f, false, 0.9f))
        } else {
            lines.add(TextLine("Journey Complete", 26f, true, 1f))
            if (spec.outroShowFinalLocation) {
                val point = req.anim.currentPoint
                lines.add(TextLine(point.displayName, 18f, false, 0.9f))
                lines.add(
                    TextLine(formatDateTime(req.anim.displayedTimestampMs), 14f, false, 0.75f),
                )
            }
        }
        val x = 32f * u
        val h = measureBlock(lines, width, x, u)
        drawTextBlock(canvas, lines, width, x, (height - h) / 2f, u, gapDp = 8f)
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    companion object {
        private const val MAX_ROUTE_PTS = 4000
        private const val BG_COLOR = 0xFF0B1220.toInt()
        private const val ROUTE_COLOR = 0xFF16A34A.toInt()
        private const val ROUTE_COLOR_DIM = 0x6616A34A
        private const val TRAVELED_COLOR = 0xFF15803D.toInt()
        private const val ANIMATED_COLOR = 0xFF2563EB.toInt()
        private const val START_COLOR = 0xFF16A34A.toInt()
        private const val END_COLOR = 0xFFDC2626.toInt()
        private const val PROGRESS_COLOR = 0xFF38BDF8.toInt()
    }
}
