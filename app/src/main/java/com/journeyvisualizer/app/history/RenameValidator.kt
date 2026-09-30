package com.journeyvisualizer.app.history

/**
 * Pure validation for user-chosen video display names.
 *
 * The user controls only the display name — never a raw storage path.
 * When the underlying MediaStore file is renamed as well, the name is
 * additionally sanitized into a safe file name (extension preserved).
 */
object RenameValidator {

    const val MAX_LENGTH = 80

    /** Characters that are unsafe in file names on Android/FAT/exFAT. */
    private val UNSAFE = Regex("[\\\\/:*?\"<>|]")

    sealed interface Result {
        data object Ok : Result
        data class Invalid(val reason: String) : Result
        data class Duplicate(val reason: String) : Result
    }

    /**
     * Validates a proposed display name.
     *
     * @param name the raw user input.
     * @param existingNames display names of the other videos (for dup check).
     */
    fun validate(name: String, existingNames: Collection<String>): Result {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Name cannot be empty.")
        if (trimmed.length > MAX_LENGTH) {
            return Result.Invalid("Name is too long (max $MAX_LENGTH characters).")
        }
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
            return Result.Invalid("Name cannot contain path separators.")
        }
        if (existingNames.any { it.equals(trimmed, ignoreCase = true) }) {
            return Result.Duplicate("A video with this name already exists.")
        }
        return Result.Ok
    }

    /**
     * Turns a validated display name into a safe MediaStore DISPLAY_NAME.
     * Keeps the ".mp4" extension; strips unsafe characters; never allows
     * path traversal.
     */
    fun toSafeFileName(displayName: String): String {
        val base = displayName.trim()
            .replace(UNSAFE, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_LENGTH)
            .ifEmpty { "Video" }
        return if (base.endsWith(".mp4", ignoreCase = true)) base else "$base.mp4"
    }

    /** Display title for a file name: drops the extension, keeps it short. */
    fun defaultDisplayName(fileName: String): String =
        fileName.substringBeforeLast('.').ifBlank { fileName }
}
