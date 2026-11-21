package com.music.bitchord.car

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Library screen's contents, assembled from the same sources as the app.
 *
 * YouTube Music has no single library feed, so this reads each part the app
 * reads — recents, Liked Music, saved songs, and the saved collection shelves —
 * and adds the phone's own playlists as one more shelf.
 */
object CarLibraryGateway {

    suspend fun load(): LibraryDataDto = withContext(Dispatchers.IO) {
        val sections = mutableListOf<LibrarySectionDto>()

        val recent = runCatching { YtMusicRepository.homeRecentlyPlayed().getOrNull() }.getOrNull()
        if (recent != null && recent.items.isNotEmpty()) {
            sections += LibrarySectionDto("최근 재생", "cards", recent.items.map { it.toCard() })
        }

        val page = runCatching { YtMusicRepository.library().getOrNull() }.getOrNull()
        if (page != null) {
            if (page.likedSongs.isNotEmpty()) {
                sections += LibrarySectionDto("좋아요", "tracks", page.likedSongs.map { it.toItem() })
            }
            if (page.librarySongs.isNotEmpty()) {
                sections += LibrarySectionDto("노래", "tracks", page.librarySongs.map { it.toItem() })
            }
            page.shelves.forEach { shelf ->
                if (shelf.items.isEmpty()) return@forEach
                sections += LibrarySectionDto(shelf.title, "cards", shelf.items.map { it.toCard() })
            }
        }

        val local = CarPlaylistGateway.list().playlists
        if (local.isNotEmpty()) {
            sections += LibrarySectionDto(
                "기기 플레이리스트",
                "cards",
                local.map { playlist ->
                    SearchItemDto(
                        kind = "local",
                        id = playlist.id,
                        title = playlist.title,
                        subtitle = playlist.subtitle.orEmpty(),
                        thumbnailUrl = playlist.artwork,
                    )
                },
            )
        }

        LibraryDataDto(sections)
    }

    private fun ShelfItem.toCard() = SearchItemDto(
        kind = if (videoId.isNullOrBlank()) "browse" else "song",
        id = videoId ?: browseId.orEmpty(),
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
    )

    private fun Song.toItem() = SearchItemDto(
        kind = "song",
        id = videoId,
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )
}
