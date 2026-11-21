package com.music.bitchord.car

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The car Library shows only the four sections the app's library is organised
 * around: playlists, artists, podcasts and this phone's own playlists. Liked
 * songs, saved songs, albums and subscriptions are deliberately not mirrored
 * into the car UI.
 */
object CarLibraryGateway {

    suspend fun load(): LibraryDataDto = withContext(Dispatchers.IO) {
        val sections = mutableListOf<LibrarySectionDto>()
        val page = runCatching { YtMusicRepository.library().getOrNull() }.getOrNull()

        val shelves = listOf(
            YtMusicRepository.PLAYLISTS_SHELF to "Playlist",
            "Artists" to "Artists",
            "Podcasts" to "Podcasts",
        )
        shelves.forEach { (source, label) ->
            val shelf = page?.shelves?.firstOrNull { it.title.equals(source, ignoreCase = true) }
            if (shelf != null && shelf.items.isNotEmpty()) {
                sections += LibrarySectionDto(label, "cards", shelf.items.map { it.toCard() })
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
}
