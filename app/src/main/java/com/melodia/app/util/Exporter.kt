package com.melodia.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Exporter {

    private fun safeName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().ifEmpty { "Melodia song" }.take(80)

    /** Copies the song into the phone's Music/Melodia folder so music apps can see it. */
    suspend fun saveToMusic(context: Context, file: File, title: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = "${safeName(title)}.mp3"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, "Melodia")
                put(MediaStore.Audio.Media.ALBUM, "Melodia")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Melodia")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android refused to create the file.")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not open the Music folder.")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Saved to Music/Melodia/$name"
        }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(context: Context, file: File, title: String) {
        val uri = uriFor(context, file)
        val type = if (file.name.endsWith(".txt") || file.name.endsWith(".log")) "text/plain" else "audio/mpeg"
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share \"$title\"").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a file the user picked (voice reference, adapter) into app storage. */
    suspend fun importUri(context: Context, uri: Uri, target: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("Could not read the file.")
            target
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
