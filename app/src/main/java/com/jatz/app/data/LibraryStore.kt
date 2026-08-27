package com.jatz.app.data

import android.content.Context
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.data.model.DropDto
import com.jatz.app.data.model.trackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything JATZ knows locally, as plain JSON files under [Context.getFilesDir].
 *
 * No SQL: the whole dataset is a handful of small drop files plus two tiny
 * index files (loved tracks, resolved-video cache). A real database would buy
 * nothing here and would add Room/KSP codegen as one more way for a build I
 * can't compile locally to fail. One [Mutex] serialises writes since the UI
 * and the background WorkManager fetch can both touch this.
 */
object LibraryStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()

    private fun dropsDir(ctx: Context): File =
        File(ctx.filesDir, "drops").apply { mkdirs() }

    // Separate directory, separate cadence, separate content stream from
    // the jazz drops -- a weekly rap-release round-up isn't part of the
    // "5 records a day" ration the rest of the app is built around, so it
    // doesn't share a folder or a fetch history with it.
    private fun rapDropsDir(ctx: Context): File =
        File(ctx.filesDir, "drops_rap").apply { mkdirs() }

    private fun lovedFile(ctx: Context) = File(ctx.filesDir, "loved.json")
    private fun videoCacheFile(ctx: Context) = File(ctx.filesDir, "video_cache.json")

    /** First launch only: unpack the bundled seed drop so the app is never empty. */
    suspend fun ensureSeeded(ctx: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = dropsDir(ctx)
            if (dir.listFiles()?.isNotEmpty() == true) return@withLock
            runCatching {
                val text = ctx.assets.open("seed_drop.json").bufferedReader().use { it.readText() }
                val drop = json.decodeFromString<DropDto>(text)
                File(dir, "${drop.date}.json").writeText(text)
            }
        }
    }

    /** Same idea as [ensureSeeded], for the weekly rap section: the first
     * build ships with one real, already-curated week so Libreria isn't
     * empty before the first Friday cron run lands. */
    suspend fun ensureSeededRap(ctx: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = rapDropsDir(ctx)
            if (dir.listFiles()?.isNotEmpty() == true) return@withLock
            runCatching {
                val text = ctx.assets.open("seed_drop_rap.json").bufferedReader().use { it.readText() }
                val drop = json.decodeFromString<DropDto>(text)
                File(dir, "${drop.date}.json").writeText(text)
            }
        }
    }

    suspend fun saveDrop(ctx: Context, drop: DropDto): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = File(dropsDir(ctx), "${drop.date}.json")
            if (f.exists()) return@withLock false
            f.writeText(json.encodeToString(drop))
            true
        }
    }

    suspend fun hasDrop(ctx: Context, date: String): Boolean = withContext(Dispatchers.IO) {
        File(dropsDir(ctx), "$date.json").exists()
    }

    /** All accumulated drops, most recent first — this IS the library. */
    suspend fun allDrops(ctx: Context): List<DropDto> = withContext(Dispatchers.IO) {
        dropsDir(ctx).listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.nameWithoutExtension }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<DropDto>(f.readText()) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun latestDrop(ctx: Context): DropDto? = allDrops(ctx).firstOrNull()

    /** Every album ever delivered, newest drop first — feeds the library grid. */
    suspend fun allAlbums(ctx: Context): List<AlbumDto> =
        allDrops(ctx).flatMap { it.albums }

    /** Look-up spans both content streams: the album detail screen doesn't
     * care whether the tap that opened it came from the jazz library grid
     * or the weekly rap section. */
    suspend fun findAlbum(ctx: Context, albumId: String): AlbumDto? =
        allAlbums(ctx).firstOrNull { it.id == albumId }
            ?: allRapAlbums(ctx).firstOrNull { it.id == albumId }

    // ---- Weekly rap section --------------------------------------------

    suspend fun saveRapDrop(ctx: Context, drop: DropDto): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = File(rapDropsDir(ctx), "${drop.date}.json")
            if (f.exists()) return@withLock false
            f.writeText(json.encodeToString(drop))
            true
        }
    }

    suspend fun hasRapDrop(ctx: Context, week: String): Boolean = withContext(Dispatchers.IO) {
        File(rapDropsDir(ctx), "$week.json").exists()
    }

    /** All weekly rap round-ups, most recent week first. */
    suspend fun allRapDrops(ctx: Context): List<DropDto> = withContext(Dispatchers.IO) {
        rapDropsDir(ctx).listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.nameWithoutExtension }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<DropDto>(f.readText()) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun allRapAlbums(ctx: Context): List<AlbumDto> =
        allRapDrops(ctx).flatMap { it.albums }

    // ---- Loved tracks -------------------------------------------------

    suspend fun lovedKeys(ctx: Context): Set<String> = withContext(Dispatchers.IO) {
        val f = lovedFile(ctx)
        if (!f.exists()) return@withContext emptySet()
        runCatching { json.decodeFromString<Set<String>>(f.readText()) }.getOrDefault(emptySet())
    }

    suspend fun isLoved(ctx: Context, albumId: String, position: String): Boolean =
        trackKey(albumId, position) in lovedKeys(ctx)

    /** Returns the new loved state (true = now loved). */
    suspend fun toggleLoved(ctx: Context, albumId: String, position: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = trackKey(albumId, position)
                val cur = lovedKeys(ctx).toMutableSet()
                val nowLoved = if (key in cur) {
                    cur.remove(key); false
                } else {
                    cur.add(key); true
                }
                lovedFile(ctx).writeText(json.encodeToString(cur as Set<String>))
                nowLoved
            }
        }

    /** [(album, track)] for every loved track, in loved-file iteration order. */
    suspend fun lovedTracks(ctx: Context): List<Pair<AlbumDto, com.jatz.app.data.model.TrackDto>> {
        val keys = lovedKeys(ctx)
        if (keys.isEmpty()) return emptyList()
        val albums = allAlbums(ctx)
        val out = mutableListOf<Pair<AlbumDto, com.jatz.app.data.model.TrackDto>>()
        for (album in albums) {
            for (t in album.tracks) {
                if (trackKey(album.id, t.position) in keys) out += album to t
            }
        }
        return out
    }

    // ---- Resolved YouTube watch-URL cache -------------------------------
    // The audio stream URL itself expires in a few hours and is never
    // cached; only *which video matches this track* is worth keeping, so a
    // replay skips the NewPipe search and goes straight to stream resolution.

    suspend fun cachedStreamRef(ctx: Context, albumId: String, position: String): String? =
        withContext(Dispatchers.IO) {
            val f = videoCacheFile(ctx)
            if (!f.exists()) return@withContext null
            val map = runCatching { json.decodeFromString<Map<String, String>>(f.readText()) }
                .getOrDefault(emptyMap())
            map[trackKey(albumId, position)]
        }

    suspend fun cacheStreamRef(ctx: Context, albumId: String, position: String, watchUrl: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = videoCacheFile(ctx)
                val map = if (f.exists()) {
                    runCatching { json.decodeFromString<Map<String, String>>(f.readText()) }
                        .getOrDefault(emptyMap())
                } else emptyMap()
                val updated = map + (trackKey(albumId, position) to watchUrl)
                f.writeText(json.encodeToString(updated))
            }
        }
    }
}
