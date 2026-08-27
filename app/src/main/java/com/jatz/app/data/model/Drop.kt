package com.jatz.app.data.model

import kotlinx.serialization.Serializable

/**
 * Mirrors the JSON written by engine/jatz/curate.py::to_album_json /
 * build_drop verbatim. Field names match the Python output's keys exactly so
 * no @SerialName mapping is needed — keep it that way if either side changes.
 */
@Serializable
data class TrackDto(
    val position: String,
    val title: String,
    val artist: String,
    val duration: String = "",
)

@Serializable
data class AlbumDto(
    val id: String,
    val discogsId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val era: String,              // "VINTAGE" | "MODERN"
    val label: String = "",
    val country: String = "",
    val styles: List<String> = emptyList(),
    val coverUrl: String = "",
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val score: Double = 0.0,
    val vibe: Int = 0,
    val confidence: String = "",
    val scoredTracks: Int = 0,
    val notes: String = "",
    val tracks: List<TrackDto> = emptyList(),
)

@Serializable
data class DropCounts(val vintage: Int = 0, val modern: Int = 0)

@Serializable
data class DropDto(
    val date: String,             // "YYYY-MM-DD"
    val generatedAt: String = "",
    val profile: String = "jazz",
    val albums: List<AlbumDto> = emptyList(),
    val counts: DropCounts = DropCounts(),
)

@Serializable
data class DropIndex(
    val latest: String? = null,
    val count: Int = 0,
    val dates: List<String> = emptyList(),
)

enum class Era { VINTAGE, MODERN, UNKNOWN }

fun AlbumDto.eraEnum(): Era = when (era.uppercase()) {
    "VINTAGE" -> Era.VINTAGE
    "MODERN" -> Era.MODERN
    else -> Era.UNKNOWN
}

/** Stable identity for a single track, used as the LOVED TRACKS key. */
fun trackKey(albumId: String, position: String): String = "$albumId#$position"
