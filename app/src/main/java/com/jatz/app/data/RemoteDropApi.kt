package com.jatz.app.data

import com.jatz.app.data.model.DropDto
import com.jatz.app.data.model.DropIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Talks to the static site GitHub Pages serves out of the jatz-daily repo
 * (see .github/workflows/daily.yml). No backend, no auth — it's public JSON.
 */
object RemoteDropApi {
    // GitHub Pages, branch `main`, root. Update this if the repo is ever
    // renamed or Pages is reconfigured to serve from a different path.
    private const val BASE = "https://fides402.github.io/jatz"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun fetch(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$BASE/$path").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        }.getOrNull()
    }

    suspend fun fetchIndex(): DropIndex? =
        fetch("drops/index.json")?.let { runCatching { json.decodeFromString<DropIndex>(it) }.getOrNull() }

    suspend fun fetchDrop(date: String): DropDto? =
        fetch("drops/$date.json")?.let { runCatching { json.decodeFromString<DropDto>(it) }.getOrNull() }
}
