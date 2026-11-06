package com.vorontsov.neurostenographer.data.export

import android.content.Context
import android.net.Uri
import com.vorontsov.neurostenographer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes an already serialized public transcript JSON document to a URI chosen
 * by the user through the Storage Access Framework.
 */
@Singleton
class TranscriptExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun export(uri: Uri, json: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val resolver = context.contentResolver
            val stream = resolver.openOutputStream(uri, "w")
                ?: error("The selected destination could not be opened")
            stream.bufferedWriter().use { writer -> writer.write(json) }
        }
    }
}
