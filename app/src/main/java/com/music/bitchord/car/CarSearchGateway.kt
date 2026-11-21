package com.music.bitchord.car

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Search is resolved on the phone, not in the car browser.
 *
 * The browser never sees Innertube or a Google session: it sends a query and a
 * filter, gets the app's top result and grouped sections, and sends back an
 * opaque id when a song is tapped. Browse cards carry their browseId; the real
 * page is opened and its songs are handed to the player on this side.
 */
object CarSearchGateway {

    private const val MAX_CACHE = 12
    private const val MAX_RESULTS = 50

    private val cache = ConcurrentHashMap<String, List<Song>>()

    /** Legacy command path, kept for the original thin client. */
    suspend fun search(query: String): Pair<String, List<SongDto>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "검색어가 비어 있습니다" }

        val response = Innertube.search(clean)
        val songs = InnertubeParser.parseSearchSongs(response).take(MAX_RESULTS)
        require(songs.isNotEmpty()) { "결과가 없습니다" }

        val id = UUID.randomUUID().toString()
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[id] = songs

        id to songs.map { it.toDto() }
    }

    /**
     * The app's result groups, in the car's order: 곡 → 아티스트 → 앨범 →
     * 플레이리스트. YouTube Music's "Songs" filter still mixes in video uploads
     * on some accounts, so the explicit Videos page is read as well and its rows
     * are appended after the real tracks — music first, videos last.
     */
    suspend fun detailed(query: String, filter: SearchFilter): SearchDataDto = withContext(Dispatchers.IO) {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "검색어가 비어 있습니다" }

        val mainRows = YtMusicRepository.searchPage(clean, filter).getOrNull()?.rows.orEmpty()
        val videoRows = if (filter == SearchFilter.SONGS) {
            runCatching {
                YtMusicRepository.searchPage(clean, SearchFilter.VIDEOS).getOrNull()?.rows.orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val pool = mutableListOf<Song>()
        val seenIds = HashSet<String>()
        fun addSong(song: Song) {
            if (song.videoId.isBlank() || !seenIds.add(song.videoId)) return
            if (pool.size < MAX_RESULTS) pool += song
        }
        (mainRows + videoRows).forEach { row ->
            when (row) {
                is SearchResult.TopTrack -> addSong(row.song)
                is SearchResult.Track -> addSong(row.song)
                is SearchResult.Browse -> Unit
            }
        }

        // Stable sort: relevance order is kept inside each of the two groups.
        val songs = pool.sortedWith(compareBy<Song> { song ->
            if (song.isVideo || song.isVideoOrigin) 1 else 0
        })
        require(songs.isNotEmpty() || mainRows.any { it is SearchResult.Browse }) { "결과가 없습니다" }

        val playable = mutableListOf<Song>()
        val playIndex = HashMap<String, Int>()
        songs.forEach { song ->
            playIndex[song.videoId] = playable.size
            playable += song
        }

        val groups = LinkedHashMap<String, MutableList<SearchItemDto>>()
        fun add(group: String, item: SearchItemDto) {
            groups.getOrPut(group) { mutableListOf() } += item
        }

        songs.forEach { song ->
            val kind = if (song.isVideo || song.isVideoOrigin) "video" else "song"
            add("songs", song.toItem(kind, playIndex[song.videoId] ?: -1))
        }

        var top: SearchItemDto? = null
        mainRows.forEach { row ->
            when (row) {
                is SearchResult.TopTrack -> {
                    if (filter == SearchFilter.ALL && !row.song.isVideo) {
                        top = row.song.toItem("song", playIndex[row.song.videoId] ?: -1)
                    }
                }
                is SearchResult.Track -> Unit
                is SearchResult.Browse -> {
                    val group = when (row.item.type) {
                        BrowseType.ARTIST -> "artists"
                        BrowseType.ALBUM -> "albums"
                        BrowseType.PLAYLIST -> "playlists"
                        BrowseType.OTHER -> "more"
                    }
                    add(group, row.item.toItem())
                }
            }
        }

        val id = UUID.randomUUID().toString()
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[id] = playable.toList()

        val titles = linkedMapOf(
            "songs" to "곡",
            "artists" to "아티스트",
            "albums" to "앨범",
            "playlists" to "플레이리스트",
            "more" to "더 보기",
        )
        val sections = titles.mapNotNull { (key, title) ->
            val items = groups[key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SearchSectionDto(title, items)
        }

        SearchDataDto(
            songs = playable.map { it.toDto() },
            searchId = id,
            top = top,
            sections = sections,
        )
    }

    /** Recent searches, rendered on the empty Search screen like the app. */
    fun recents(): List<SearchItemDto> = SearchHistory.recent.value.take(12).map { entity ->
        val kind = when (entity.entityType) {
            EntityType.TRACK -> "song"
            EntityType.ALBUM -> "album"
            EntityType.ARTIST -> "artist"
            EntityType.PLAYLIST -> "playlist"
            EntityType.OTHER -> "other"
        }
        SearchItemDto(
            kind = kind,
            id = entity.id,
            title = entity.title,
            subtitle = entity.subtitle,
            thumbnailUrl = entity.artworkUrl,
        )
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

    private fun Song.toItem(kind: String, index: Int) = SearchItemDto(
        kind = kind,
        id = videoId,
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        playIndex = index,
    )

    private fun BrowseItem.toItem() = SearchItemDto(
        kind = when (type) {
            BrowseType.ARTIST -> "artist"
            BrowseType.ALBUM -> "album"
            BrowseType.PLAYLIST -> "playlist"
            BrowseType.OTHER -> "other"
        },
        id = browseId,
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
    )
}
