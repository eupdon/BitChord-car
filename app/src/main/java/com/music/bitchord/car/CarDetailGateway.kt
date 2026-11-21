package com.music.bitchord.car

import androidx.media3.session.MediaController
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The second page behind an album, artist, playlist or podcast card.
 *
 * A card never becomes a queue by itself: the browser asks for this page first,
 * gets the collection's name, cover and tracks, and only then sends back the
 * opaque key together with the row it tapped (or 0 for "play all"). The actual
 * [Song] list stays on the phone, so the browser never sees a network client or
 * holds playback state.
 */
object CarDetailGateway {

    private const val MAX_CACHE = 8
    private val cache = ConcurrentHashMap<String, List<Song>>()

    suspend fun load(
        kind: String,
        id: String,
        title: String,
        subtitle: String,
        artwork: String?,
    ): DetailDataDto = withContext(Dispatchers.IO) {
        require(id.isNotBlank()) { "열 수 있는 항목이 없습니다" }

        val resolvedTitle: String
        val resolvedSubtitle: String
        val resolvedArtwork: String?
        val resolvedKind: String
        val songs: List<Song>
        var message: String? = null

        if (kind == "local" || CarPlaylistGateway.isLocal(id)) {
            val playlist = CarPlaylistGateway.list().playlists.firstOrNull { it.id == id }
            songs = CarPlaylistGateway.songs(id)
            resolvedKind = "playlist"
            resolvedTitle = playlist?.title?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { "플레이리스트" }
            resolvedSubtitle = playlist?.subtitle?.takeIf { it.isNotBlank() } ?: subtitle
            resolvedArtwork = playlist?.artwork ?: artwork
        } else {
            // browseSongs carries the header (name, byline, cover) and the first
            // rows. A continuation means the page is longer than one response:
            // follow it here, where the network session lives, so "play all"
            // really means every track rather than the first screenful.
            val page = runCatching { YtMusicRepository.browseSongs(id).getOrNull() }.getOrNull()
            val first = page?.songs.orEmpty()
            songs = when {
                first.isEmpty() -> runCatching { YtMusicRepository.allSongs(id).getOrNull() }
                    .getOrNull().orEmpty()
                page?.continuation != null -> runCatching { YtMusicRepository.allSongs(id).getOrNull() }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: first
                else -> first
            }
            resolvedKind = when (kind) {
                "artist", "album", "podcast" -> kind
                else -> when {
                    id.startsWith("UC") -> "artist"
                    id.startsWith("MPREb") -> "album"
                    else -> "playlist"
                }
            }
            resolvedTitle = page?.header?.title?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { "컬렉션" }
            resolvedSubtitle = page?.header?.subtitle?.takeIf { it.isNotBlank() } ?: subtitle
            resolvedArtwork = page?.header?.thumbnailUrl ?: artwork
            if (songs.isEmpty()) message = "곡을 불러오지 못했습니다. 폰의 YouTube Music 연결을 확인해 주세요."
        }

        if (songs.isEmpty() && message == null) message = "이 목록에는 곡이 없습니다."

        val key = UUID.randomUUID().toString()
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[key] = songs

        DetailDataDto(
            key = key,
            kind = resolvedKind,
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            artwork = resolvedArtwork,
            songs = songs.map { it.toDto() },
            message = message,
        )
    }

    /** Replaces the queue with the whole loaded list, starting at the tapped row. */
    suspend fun play(controller: MediaController, key: String, index: Int) {
        val songs = cache[key] ?: error("목록이 만료되었습니다. 다시 열어 주세요")
        require(songs.isNotEmpty()) { "재생할 곡이 없습니다" }
        controller.playSongs(songs, if (index in songs.indices) index else 0)
    }

    private fun Song.toDto() = SongDto(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )
}
