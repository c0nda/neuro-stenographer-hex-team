package com.vorontsov.neurostenographer.data.file

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all audio paths. Audio files live in app-specific external storage so no
 * storage permission is required and files are removed with the app.
 */
@Singleton
class AudioFileStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        "audio",
    )

    private val recordingsDir = File(root, "recordings").apply { mkdirs() }
    private val originalsDir = File(root, "originals").apply { mkdirs() }

    fun createRecordingFile(prefix: String = "rec"): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "${prefix}_${stamp}_${UUID.randomUUID().toString().take(8)}.wav")
    }

    fun createImportedWorkingFile(displayName: String): File {
        val safeName = displayName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9А-Яа-яЁё_-]"), "_")
            .take(40)
            .ifBlank { "import" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "${safeName}_${stamp}_${UUID.randomUUID().toString().take(8)}.wav")
    }

    fun createOriginalCopy(displayName: String): File {
        val extension = displayName.substringAfterLast('.', "").take(8)
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return File(originalsDir, "original_${UUID.randomUUID()}$suffix")
    }

    fun copyToOriginal(input: InputStream, displayName: String): File {
        val target = createOriginalCopy(displayName)
        input.use { source ->
            target.outputStream().buffered().use { sink ->
                source.copyTo(sink, DEFAULT_BUFFER_SIZE)
            }
        }
        return target
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    fun delete(paths: Iterable<String?>) {
        paths.forEach(::delete)
    }
}
