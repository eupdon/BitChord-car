package com.music.bitchord.car

import android.content.Context
import androidx.media3.session.MediaController
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Playlists for the car browser, read from the same stores the phone UI uses.
 *
 * Spotify's live library needs the phone's own token, and a Spotify track id is
 * not playable by the app's streamer, so each one is matched to a YouTube Music
 * track on the phone before it becomes a queue row. Nothing about that search
 * or token ever reaches the browser.
 */
object CarPlaylistGateway {

    private const val MAX_SPOTIFY_TRACKS = 25
    private const val MAX_SPOTIFY_CACHE = 8

    private val spotifyCache = ConcurrentHashMap<String, List<Song>>()

    /** The stores are opened by the UI; do it here too for a bridge started at boot. */
    fun init(context: Context) {
        LocalPlaylistStore.init(context.applicationContext)
        OfflinePlaylists.init(context.applicationContext, "Liked songs")
    }

    suspend fun list(): PlaylistListDto = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlaylistDto>()

        OfflinePlaylists.playlists.value.forEach { playlist ->
            out += PlaylistDto(
                id = playlist.browseId,
                title = playlist.title,
                kind = "offline",
                subtitle = "${playlist.songs.size}곡",
                count = playlist.songs.size,
            )
        }

        LocalPlaylistStore.playlists.value.forEach { playlist ->
            out += PlaylistDto(
                id = playlist.browseId,
                title = playlist.title,
                kind = "local",
                subtitle = "${playlist.songs.size}곡",
                count = playlist.songs.size,
            )
        }

        if (SpotifyLibrary.auth != null) {
            runCatching { SpotifyLibrary.playlists() }.getOrNull()?.forEach { playlist ->
                out += PlaylistDto(
                    id = SPOTIFY_PAGE_PREFIX + playlist.id,
                    title = playlist.name,
                    kind = "spotify",
                    subtitle = playlist.owner,
                    artwork = playlist.imageUrl,
                )
            }
        }

        PlaylistListDto(out)
    }

    suspend fun tracks(id: String): SongListDto = withContext(Dispatchers.IO) {
        SongListDto(resolve(id).map { it.toDto() })
    }

    suspend fun play(controller: MediaController, id: String, index: Int) {
        val songs = withContext(Dispatchers.IO) { resolve(id) }
        require(songs.isNotEmpty()) { "재생할 곡이 없습니다" }
        controller.playSongs(songs, if (index in songs.indices) index else 0)
    }

    private suspend fun resolve(id: String): List<Song> = when {
        id.startsWith(OfflinePlaylists.PREFIX) -> OfflinePlaylists.get(id)?.songs.orEmpty()
        id.startsWith(SPOTIFY_PAGE_PREFIX) ->
            spotifyTracks(id.removePrefix(SPOTIFY_PAGE_PREFIX))
        else -> LocalPlaylistStore.getPlaylist(id)?.songs.orEmpty()
    }

    private suspend fun spotifyTracks(playlistId: String): List<Song> {
        spotifyCache[playlistId]?.let { return it }

        val source = runCatching { SpotifyLibrary.tracks(playlistId) }
            .getOrNull()
            .orEmpty()
            .take(MAX_SPOTIFY_TRACKS)

        val matched = source.mapNotNull { track ->
            runCatching {
                val response = Innertube.search("${track.title} ${track.artist}")
                InnertubeParser.parseSearchSongs(response).firstOrNull()
            }.getOrNull()
        }

        if (spotifyCache.size >= MAX_SPOTIFY_CACHE) spotifyCache.clear()
        spotifyCache[playlistId] = matched
        return matched
    }

    private fun Song.toDto() = SongDto(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
    )
}
