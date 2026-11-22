package com.music.bitchord.car

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One codec for the socket and for the values carried inside a call result. */
object CarJson {
    val codec: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

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
 * [positionAt] is the phone's wall-clock time at which [positionMs] was sampled.
 * It is kept for other thin clients, but the car browser deliberately ignores it:
 * the two clocks can be tens of seconds apart, so it extrapolates from the time
 * the frame arrived instead and re-syncs on every state push.
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
    /** Whether the current track is liked, from the phone's own like state. */
    val liked: Boolean = false,
    val volume: Int = 0,
    val volumeMax: Int = 15,
    val track: TrackDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val queueIndex: Int = -1,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** Browser -> phone command. Kept for the original thin client. */
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

/**
 * Browser -> phone RPC call. The browser asks for data by name; everything that
 * needs a network session, a database or a player runs on the phone.
 */
@Serializable
data class CallDto(
    val type: String = "call",
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
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

/** Phone -> browser answer. [data] is the typed result of an RPC call. */
@Serializable
data class ResultDto(
    val type: String = "result",
    val id: String,
    val ok: Boolean = true,
    val songs: List<SongDto> = emptyList(),
    val searchId: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class ErrorDto(
    val type: String = "error",
    val id: String? = null,
    val message: String,
)

// ---- RPC payloads ---------------------------------------------------------

/** One row in a search or library section: a playable song or a browse card. */
@Serializable
data class SearchItemDto(
    /** `song`, `video`, `artist`, `album`, `playlist`, `other` or `local`. */
    val kind: String = "song",
    /** videoId for songs, browseId for cards, playlist id for local cards. */
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /** Index into [SearchDataDto.songs] for playable rows; -1 for cards. */
    val playIndex: Int = -1,
)

@Serializable
data class SearchItemListDto(
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
data class SearchSectionDto(
    val title: String? = null,
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
data class SearchDataDto(
    val songs: List<SongDto> = emptyList(),
    val searchId: String? = null,
    val top: SearchItemDto? = null,
    val sections: List<SearchSectionDto> = emptyList(),
)

@Serializable
data class LibrarySectionDto(
    val title: String,
    /** `tracks` for song rows, `cards` for artwork grids. */
    val kind: String = "cards",
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
data class LibraryDataDto(
    val sections: List<LibrarySectionDto> = emptyList(),
)

@Serializable
data class SongListDto(
    val songs: List<SongDto> = emptyList(),
)

/**
 * One browse page: a released collection, an artist, a saved playlist or a
 * device playlist. The browser only ever holds the small DTO and the opaque
 * [key]; the real track list stays on the phone behind that key.
 */
@Serializable
data class DetailDataDto(
    val key: String = "",
    /** `album`, `artist`, `playlist` or `podcast`. Drives the header label. */
    val kind: String = "playlist",
    val title: String = "",
    val subtitle: String = "",
    val artwork: String? = null,
    val songs: List<SongDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val title: String,
    /** `local`, `offline` or `spotify`. Drives the badge in the browser. */
    val kind: String = "local",
    val subtitle: String? = null,
    val artwork: String? = null,
    val count: Int = 0,
)

@Serializable
data class PlaylistListDto(
    val playlists: List<PlaylistDto> = emptyList(),
)

@Serializable
data class LyricSyllableDto(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
)

@Serializable
data class LyricWordDto(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<LyricSyllableDto> = emptyList(),
)

@Serializable
data class LyricsLineDto(
    val timeMs: Long,
    val text: String,
    val endMs: Long = timeMs,
    val words: List<LyricWordDto> = emptyList(),
    val alignment: String = "start",
    val background: LyricsLineDto? = null,
)

@Serializable
data class LyricsDataDto(
    val videoId: String = "",
    val title: String = "",
    val source: String = "",
    val lines: List<LyricsLineDto> = emptyList(),
)

/** One card in a Home shelf. A card is either a track ([videoId]) or a collection ([browseId]). */
@Serializable
data class HomeItemDto(
    val title: String = "",
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val videoId: String = "",
    val browseId: String = "",
    val isVideo: Boolean = false,
)

/** A Home row, laid out the way the app's shelf would draw it. */
@Serializable
data class HomeShelfDto(
    val title: String = "",
    val subtitle: String = "",
    val layout: String = "CARDS",
    val items: List<HomeItemDto> = emptyList(),
)

/** The phone's personalised Home feed, assembled where the session lives. */
@Serializable
data class HomeDataDto(
    val shelves: List<HomeShelfDto> = emptyList(),
)
