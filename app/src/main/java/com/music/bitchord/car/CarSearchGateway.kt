package com.music.bitchord.car

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Search is resolved on the phone, not in the car browser.
 *
 * The browser never sees Innertube or a Google session: it sends a query, gets a
 * short list of display rows and an opaque [searchId]. Tapping a row sends that
 * id back, and the full [Song] objects are handed to the existing player through
 * `MediaController.playSongs`. Keeping the real objects here also means the
 * browser cannot forge a queue of arbitrary media ids.
 */
object CarSearchGateway {

    private const val MAX_CACHE = 12
    private const val MAX_RESULTS = 50

    private val cache = ConcurrentHashMap<String, List<Song>>()

    suspend fun search(query: String): Pair<String, List<SongDto>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "검색어가 비어 있습니다" }

        val response = Innertube.search(clean)
        val songs = InnertubeParser.parseSearchSongs(response).take(MAX_RESULTS)
        require(songs.isNotEmpty()) { "결과가 없습니다" }

        val id = UUID.randomUUID().toString()
        // A car session is long-lived; do not let every search leave a queue in memory.
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[id] = songs

        id to songs.map { it.toDto() }
    }

    fun tracks(searchId: String, index: Int): Pair<List<Song>, Int>? {
        val songs = cache[searchId] ?: return null
        if (index !in songs.indices) return null
        return songs to index
    }

    private fun Song.toDto() = SongDto(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )
}
