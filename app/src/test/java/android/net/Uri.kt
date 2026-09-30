package android.net

/**
 * Minimal JVM test stub for android.net.Uri. Only what the Phase 7
 * pure-Kotlin tests need: parse/toString plus value equality so
 * RenderState.Completed comparisons behave.
 */
class Uri private constructor(private val raw: String) {
    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean =
        other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}
