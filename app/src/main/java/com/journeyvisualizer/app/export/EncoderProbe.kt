package com.journeyvisualizer.app.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Pre-flight encoder capability check (Phase 7).
 *
 * Runs BEFORE any tile fetching or encoding, so an unsupported
 * configuration fails fast with a useful message instead of after a long
 * render. Never assumes universal surface/codec support — everything is
 * probed against the device's actual [MediaCodecList].
 */
object EncoderProbe {

    /** A concrete lower setting the device is likely to accept. */
    data class Fallback(
        val width: Int,
        val height: Int,
        val fps: Int,
        val label: String,
    )

    data class Result(
        val ok: Boolean,
        val error: RenderError?,
        /** Requested bitrate coerced into the codec's supported range. */
        val bitrate: Int,
        val fallback: Fallback?,
    )

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    fun check(width: Int, height: Int, fps: Int, requestedBitrate: Int): Result {
        val encoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && MIME in it.supportedTypes }
            ?: return Result(
                ok = false,
                error = RenderError.EncoderUnavailable("no AVC encoder on this device"),
                bitrate = requestedBitrate,
                fallback = null,
            )
        val caps: MediaCodecInfo.CodecCapabilities = try {
            encoder.getCapabilitiesForType(MIME)
        } catch (_: Exception) {
            return Result(false, RenderError.EncoderUnavailable(encoder.name), requestedBitrate, null)
        }
        if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) {
            return Result(
                false,
                RenderError.EncoderUnavailable("${encoder.name} has no surface input"),
                requestedBitrate,
                null,
            )
        }
        val videoCaps = caps.videoCapabilities
            ?: return Result(false, RenderError.EncoderUnavailable("no video caps"), requestedBitrate, null)

        if (!videoCaps.isSizeSupported(width, height)) {
            val fallback = suggestFallback(videoCaps, width, height, fps)
            val is4k = width.toLong() * height > 1920L * 1080
            val error: RenderError = if (is4k) {
                RenderError.FourKUnsupported("${width}x$height rejected by ${encoder.name}")
            } else {
                RenderError.UnsupportedConfig(
                    detail = "${width}x$height rejected by ${encoder.name}",
                    suggestion = fallback?.let { "Try ${it.label} instead." },
                )
            }
            return Result(false, error, requestedBitrate, fallback)
        }
        if (!videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())) {
            val fallback30 = if (fps != 30 &&
                videoCaps.areSizeAndRateSupported(width, height, 30.0)
            ) {
                Fallback(width, height, 30, "${width}x$height at 30 fps")
            } else {
                suggestFallback(videoCaps, width, height, 30)
            }
            return Result(
                false,
                RenderError.UnsupportedConfig(
                    detail = "$fps fps at ${width}x$height rejected by ${encoder.name}",
                    suggestion = fallback30?.let { "Try ${it.label} instead." },
                ),
                requestedBitrate,
                fallback30,
            )
        }
        val bitrate = BitratePolicy.coerceToCodecRange(
            requestedBitrate,
            videoCaps.bitrateRange.lower,
            videoCaps.bitrateRange.upper,
        )
        return Result(true, null, bitrate, null)
    }

    /**
     * Largest well-known preset at the same aspect ratio that the codec
     * accepts (checked at 30 fps), or null when even 720p-class output
     * is unsupported.
     */
    private fun suggestFallback(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        fps: Int,
    ): Fallback? {
        val portrait = height > width
        val candidates = listOf(
            Triple(1920, 1080, "1080p"),
            Triple(1280, 720, "720p"),
        )
        for ((cw, ch, label) in candidates) {
            val w = if (portrait) ch else cw
            val h = if (portrait) cw else ch
            if (w == width && h == height) continue
            val rate = fps.coerceAtMost(30).toDouble()
            if (videoCaps.isSizeSupported(w, h) &&
                videoCaps.areSizeAndRateSupported(w, h, rate)
            ) {
                return Fallback(w, h, rate.toInt(), "$label at ${rate.toInt()} fps")
            }
        }
        return null
    }
}
