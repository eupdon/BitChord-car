package com.music.bitchord.car

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns any card the browser shows into a real queue on the phone.
 *
 * Songs already carry a videoId. Albums, artists and playlists only carry a
 * browseId, so their listing is read here — never in the browser — before the
 * controller is handed the tracks to play.
 */
object CarPlayGateway {

    suspend fun play(
        kind: String,
        id: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
    ): List<Song> = withContext(Dispatchers.IO) {
        require(id.isNotBlank()) { "재생할 항목이 없습니다" }
        if (kind == "song" || kind == "video") {
            listOf(
                Song(
                    videoId = id,
                    title = title.ifBlank { "Unknown" },
                    artist = artist,
                    thumbnailUrl = thumbnailUrl,
                ),
            )
        } else {
            val songs = YtMusicRepository.browseSongs(id).getOrNull()?.songs.orEmpty()
            require(songs.isNotEmpty()) { "재생할 곡이 없습니다" }
            songs
        }
    }
}
