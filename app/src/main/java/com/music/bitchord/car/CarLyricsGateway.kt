package com.music.bitchord.car

import android.content.Context
import androidx.media3.session.MediaController
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics for the car browser, resolved entirely on the phone.
 *
 * The browser sends no video id of its own: the metadata comes from the live
 * MediaController, so a page cannot ask for lyrics to a track it forged. The
 * result is cached by video id because a car session reopens the lyrics panel
 * far more often than the track changes.
 */
object CarLyricsGateway {

    private const val MAX_CACHE = 12
    private val cache = ConcurrentHashMap<String, LyricsDataDto>()

    suspend fun current(app: Context, controller: MediaController): LyricsDataDto {
        // Media3 must be read on the main thread; the lookup itself is IO.
        val identity = withContext(Dispatchers.Main) {
            val item = controller.currentMediaItem
            val meta = item?.mediaMetadata
            Identity(
                videoId = item?.mediaId.orEmpty(),
                title = meta?.title?.toString().orEmpty(),
                artist = meta?.artist?.toString().orEmpty(),
                album = meta?.albumTitle?.toString(),
                durationMs = controller.duration.takeIf { it > 0L } ?: (meta?.durationMs ?: -1L),
            )
        }
        if (identity.videoId.isEmpty()) return LyricsDataDto()

        cache[identity.videoId]?.let { return it }

        val data = withContext(Dispatchers.IO) {
            val found = runCatching {
                LyricsRepository.lyrics(
                    videoId = identity.videoId,
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    album = identity.album,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
            }.getOrNull()

            val lines = found?.lines.orEmpty()
            LyricsDataDto(
                videoId = identity.videoId,
                title = identity.title,
                source = found?.source?.name.orEmpty(),
                lines = lines.mapIndexed { index, line ->
                    // Upstream stores the line's own end as `sungUntilMs`. When a
                    // provider omits it, the next line's stamp is the best end we
                    // have for a whole-line sweep; the last line gets a short tail.
                    val endMs = line.sungUntilMs
                        ?: lines.getOrNull(index + 1)?.timeMs
                        ?: (line.timeMs + 4_000L)
                    line.toDto(endMs)
                },
            )
        }

        if (cache.size >= MAX_CACHE) cache.clear()
        cache[identity.videoId] = data
        return data
    }

    private data class Identity(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    private fun LyricLine.toDto(endMs: Long): LyricsLineDto = LyricsLineDto(
        timeMs = timeMs,
        text = text,
        endMs = maxOf(timeMs, endMs),
        words = words.map { word ->
            LyricWordDto(
                startMs = word.startMs,
                endMs = word.endMs,
                text = word.text,
                syllables = word.syllables.map { syllable ->
                    LyricSyllableDto(
                        startMs = syllable.startMs,
                        endMs = syllable.endMs,
                        startChar = syllable.startChar,
                        endChar = syllable.endChar,
                    )
                },
            )
        },
        alignment = if (alignment == LyricAlignment.End) "end" else "start",
        background = background?.toDto(endMs),
    )
}
