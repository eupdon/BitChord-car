package com.music.bitchord.car

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home for the car browser.
 *
 * The browser is display-only: it asks for a feed and gets plain display rows,
 * then sends back a shelf/index pair when the driver taps a card. The real
 * [Song] is rebuilt here, on the phone, out of the shelf we cached — the same
 * place the Innertube session and the account's history already live.
 */
object CarHomeGateway {

    /** Shelves from the last [load], addressed by index from the browser. */
    @Volatile
    private var shelves: List<HomeShelf> = emptyList()

    suspend fun load(): HomeDataDto = withContext(Dispatchers.IO) {
        // Each source is independent: a signed-out or failing endpoint leaves
        // the rest of the feed intact rather than blanking Home.
        val recent = runCatching { YtMusicRepository.homeRecentlyPlayed().getOrNull() }
            .getOrNull()
        val feed = runCatching { YtMusicRepository.home().getOrNull() }
            .getOrNull()

        val combined = mutableListOf<HomeShelf>()
        if (recent != null && recent.items.isNotEmpty()) combined += recent
        feed?.let { combined.addAll(it.shelves) }

        shelves = combined.toList()
        HomeDataDto(
            shelves = combined.map { shelf ->
                HomeShelfDto(
                    title = shelf.title,
                    subtitle = shelf.subtitle,
                    layout = shelf.layout.name,
                    items = shelf.items.map { it.toDto() },
                )
            },
        )
    }

    /** Turn a tapped card back into a playable track using only phone-side data. */
    fun playable(shelf: Int, item: Int): Song? {
        val found = shelves.getOrNull(shelf)?.items?.getOrNull(item) ?: return null
        val videoId = found.videoId
        if (videoId.isNullOrBlank()) return null
        return Song(
            videoId = videoId,
            title = found.title,
            artist = found.subtitle,
            thumbnailUrl = found.thumbnailUrl,
            isVideo = found.isVideo,
        )
    }

    private fun ShelfItem.toDto() = HomeItemDto(
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId.orEmpty(),
        isVideo = isVideo,
    )
}
