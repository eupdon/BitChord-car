package com.music.bitchord.car

import kotlinx.serialization.Serializable

/** One row in the now-playing track or in the queue. */
@Serializable
data class TrackDto(
    val videoId: String = "",
    val title: String = "Unknown",
    val artist: String = "",
    val album: String? = null,
    val artwork: String? = null,
    val durationMs: Long = -1L,
)

/**
 * Phone -> browser state.
 *
 * [positionAt] is wall-clock millis at which [positionMs] was sampled. The browser
 * extrapolates from it instead of waiting for a tick, and re-syncs on every state
 * frame. This is the same idea WearProtocol uses for its watch.
 */
@Serializable
data class StateDto(
    val type: String = "state",
    val playing: Boolean = false,
    val advancing: Boolean = false,
    val positionMs: Long = 0L,
    val positionAt: Long = 0L,
    val durationMs: Long = -1L,
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
    val volume: Int = 0,
    val volumeMax: Int = 15,
    val track: TrackDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val queueIndex: Int = -1,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** Browser -> phone command. Only the fields relevant to [name] are set. */
@Serializable
data class CommandDto(
    val type: String = "command",
    val id: String,
    val name: String,
    val query: String? = null,
    val searchId: String? = null,
    val index: Int? = null,
    val positionMs: Long? = null,
    val delta: Int? = null,
    val level: Int? = null,
)

/** A search hit, kept deliberately small enough for a car browser to render. */
@Serializable
data class SongDto(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
)

/** Phone -> browser answer to a command. Search results ride in [songs]. */
@Serializable
data class ResultDto(
    val type: String = "result",
    val id: String,
    val ok: Boolean = true,
    val songs: List<SongDto> = emptyList(),
    val searchId: String? = null,
    val message: String? = null,
)

@Serializable
data class ErrorDto(
    val type: String = "error",
    val id: String? = null,
    val message: String,
)
