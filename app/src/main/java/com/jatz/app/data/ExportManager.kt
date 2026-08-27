package com.jatz.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

@Serializable
data class LibraryExport(
    val exportedAt: String,
    val profile: String,
    val dropCount: Int,
    val drops: List<com.jatz.app.data.model.DropDto>,
    val lovedTrackKeys: List<String>,
)

/**
 * Everything JATZ knows lives only on this one phone -- no server, no
 * account, no cloud. This is the one way out: a single JSON file with the
 * whole accumulated library (every drop ever delivered) and every loved
 * track key, handed to the share sheet so the user can save it to Drive,
 * mail it to themselves, or move it to a new phone.
 */
object ExportManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun buildExportFile(ctx: Context): File = withContext(Dispatchers.IO) {
        val drops = LibraryStore.allDrops(ctx)
        val loved = LibraryStore.lovedKeys(ctx).toList().sorted()

        val export = LibraryExport(
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            profile = "jazz",
            dropCount = drops.size,
            drops = drops,
            lovedTrackKeys = loved,
        )

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        // Only ever one export file at a time -- this directory isn't a
        // history, just a hand-off point to the share sheet.
        dir.listFiles()?.forEach { it.delete() }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val file = File(dir, "jatz_libreria_$stamp.json")
        file.writeText(json.encodeToString(export))
        file
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Libreria JATZ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(sendIntent, "Esporta libreria JATZ"))
    }
}
