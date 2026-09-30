package com.journeyvisualizer.app.export

/**
 * Deterministic frame clock (Phase 7). Pure Kotlin — no Android dependency.
 *
 * Every output frame is addressed by its index; presentation timestamps
 * are computed arithmetically, never accumulated from sleeps, handler
 * delays, or wall-clock measurements. The same (frameIndex, fps) always
 * yields the same timestamp, so a render can be reproduced exactly.
 */
object RenderClock {

    /**
     * Number of frames covering [totalVideoMs] at [fps] frames/second.
     * Rounds up so the final partial frame interval is still rendered.
     */
    fun totalFrames(totalVideoMs: Long, fps: Int): Int {
        require(totalVideoMs > 0) { "totalVideoMs must be positive" }
        require(fps > 0) { "fps must be positive" }
        return ((totalVideoMs * fps + 999) / 1000).toInt()
    }

    /**
     * Encoder presentation timestamp for [frameIndex], in nanoseconds.
     * Frame 0 = 0 ns, frame 1 = 1e9/fps ns, and so on — exact, no drift.
     */
    fun presentationTimeNs(frameIndex: Int, fps: Int): Long {
        require(frameIndex >= 0) { "frameIndex must be >= 0" }
        require(fps > 0) { "fps must be positive" }
        return frameIndex * 1_000_000_000L / fps
    }

    /**
     * Video-clock position of [frameIndex] in milliseconds: the instant the
     * frame depicts. Always strictly below the total video duration.
     */
    fun videoTimeMs(frameIndex: Int, fps: Int): Long {
        require(frameIndex >= 0) { "frameIndex must be >= 0" }
        require(fps > 0) { "fps must be positive" }
        return frameIndex * 1000L / fps
    }

    /** Which part of the video [videoTimeMs] falls in. */
    fun regionFor(videoTimeMs: Long, introMs: Long, journeyEndMs: Long): FrameRegion =
        when {
            introMs > 0 && videoTimeMs < introMs -> FrameRegion.INTRO
            videoTimeMs >= journeyEndMs -> FrameRegion.OUTRO
            else -> FrameRegion.JOURNEY
        }
}

/** Intro card / journey animation / outro card portion of the video. */
enum class FrameRegion {
    INTRO,
    JOURNEY,
    OUTRO,
}
