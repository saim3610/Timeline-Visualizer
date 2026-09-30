package com.journeyvisualizer.app.export

/**
 * Bitrate selection for the H.264 encoder (Phase 7). Pure Kotlin.
 *
 * This is a heuristic starting point scaled from resolution and frame
 * rate — not a claim of universal optimality. The caller still intersects
 * the result with the device codec's reported bitrate range
 * ([EncoderProbe]), so an out-of-range value can never reach the encoder.
 */
object BitratePolicy {

    const val MIN_BITRATE = 1_000_000
    const val MAX_BITRATE = 50_000_000

    /**
     * Target average bitrate in bits/second.
     *
     * Base rates per resolution class, scaled linearly with fps relative
     * to a 30 fps reference (more frames carry proportionally more data),
     * then clamped into [MIN_BITRATE, MAX_BITRATE].
     */
    fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        require(fps > 0) { "fps must be positive" }
        val pixels = width.toLong() * height
        val base = when {
            pixels <= 1280L * 720 -> 4_000_000L
            pixels <= 1920L * 1080 -> 8_000_000L
            pixels <= 2560L * 1440 -> 16_000_000L
            else -> 32_000_000L
        }
        return (base * fps / 30).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    /**
     * Intersect a requested bitrate with the codec's supported range.
     * Returns the closest supported value; never throws.
     */
    fun coerceToCodecRange(requested: Int, minSupported: Int, maxSupported: Int): Int {
        if (maxSupported <= 0) return requested.coerceIn(MIN_BITRATE, MAX_BITRATE)
        val lo = minSupported.coerceAtLeast(1)
        val hi = maxSupported.coerceAtLeast(lo)
        return requested.coerceIn(lo, hi)
    }
}
