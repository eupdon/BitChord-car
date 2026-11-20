package com.music.bitchord.car

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.playback.ACTION_TOGGLE_FAVORITE
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car bridge's door into the player.
 *
 * This deliberately binds an ordinary [MediaController] from outside the app's
 * Compose tree. Nothing in PlaybackService has to change: the session already
 * publishes everything a remote needs, and every command goes through the same
 * controller the notification, widget and Wear bridge use.
 */
class CarSessionController(
    context: Context,
    private val onState: (StateDto) -> Unit,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publishState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()
        override fun onPlaybackStateChanged(playbackState: Int) = publishState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = publishState()
        override fun onRepeatModeChanged(repeatMode: Int) = publishState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publishState()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = publishState()
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener(
            {
                controller = runCatching { f.get() }.getOrNull()?.also { c ->
                    c.addListener(listener)
                    publishState()
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        scope.cancel()
    }

    fun publishState() {
        scope.launch {
            val c = controller ?: return@launch
            onState(snapshot(c))
        }
    }

    /** Must be called off the websocket thread; Media3 wants the main thread. */
    suspend fun currentState(): StateDto = withContext(Dispatchers.Main) {
        controller?.let(::snapshot) ?: StateDto()
    }

    fun handle(command: CommandDto, onResult: (ResultDto) -> Unit) {
        scope.launch {
            if (command.name == "search") {
                runCatching { CarSearchGateway.search(command.query.orEmpty()) }
                    .onSuccess { (id, songs) ->
                        onResult(ResultDto(id = command.id, songs = songs, searchId = id))
                    }
                    .onFailure { t ->
                        onResult(ResultDto(id = command.id, ok = false, message = t.message ?: "검색 실패"))
                    }
                return@launch
            }

            runCatching { execute(command) }
                .onSuccess {
                    onResult(ResultDto(id = command.id))
                    publishState()
                }
                .onFailure { t ->
                    onResult(ResultDto(id = command.id, ok = false, message = t.message ?: "명령 실패"))
                }
        }
    }

    private suspend fun execute(command: CommandDto) {
        when (command.name) {
            "toggle" -> requireController().let { c ->
                if (c.playWhenReady) {
                    c.pause()
                } else {
                    if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
                    c.play()
                }
            }
            "play" -> requireController().play()
            "pause" -> requireController().pause()
            "next" -> requireController().seekToNext()
            "previous" -> requireController().seekToPrevious()
            "seek" -> requireController().seekTo(command.positionMs ?: 0L)
            "skipTo" -> requireController().seekTo(command.index ?: 0, 0L)
            "shuffle" -> requireController().let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            "repeat" -> requireController().let { c ->
                c.repeatMode = when (c.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            "like" -> requireController().sendCustomCommand(
                SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            "volume" -> applyVolume(command)
            "playSearch" -> {
                val pair = CarSearchGateway.tracks(command.searchId.orEmpty(), command.index ?: 0)
                    ?: error("검색 결과가 만료되었습니다")
                requireController().playSongs(pair.first, pair.second)
            }
            "queue", "ping" -> Unit
            else -> error("알 수 없는 명령: ${command.name}")
        }
    }

    private fun requireController(): MediaController = controller ?: error("플레이어가 아직 연결되지 않았습니다")

    private fun applyVolume(command: CommandDto) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        command.level?.let { level ->
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            return
        }
        val delta = command.delta ?: 0
        if (delta != 0) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
        }
    }

    private fun snapshot(c: MediaController): StateDto {
        val meta = c.currentMediaItem?.mediaMetadata
        val now = System.currentTimeMillis()

        val tracks = (0 until c.mediaItemCount).mapNotNull { i ->
            val m = c.getMediaItemAt(i).mediaMetadata
            val title = m.title?.toString()
            TrackDto(
                videoId = m.mediaId.orEmpty(),
                title = if (title.isNullOrBlank()) "Unknown" else title,
                artist = m.artist?.toString().orEmpty(),
                album = m.albumTitle?.toString(),
                artwork = m.artworkUri?.toString(),
                durationMs = m.durationMs ?: -1L,
            )
        }

        val index = c.currentMediaItemIndex
        val trackTitle = meta?.title?.toString()
        return StateDto(
            playing = c.playWhenReady,
            advancing = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            positionAt = now,
            durationMs = c.duration.takeIf { it > 0L } ?: (meta?.durationMs ?: -1L),
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
            track = meta?.let {
                TrackDto(
                    videoId = it.mediaId.orEmpty(),
                    title = if (trackTitle.isNullOrBlank()) "Unknown" else trackTitle,
                    artist = it.artist?.toString().orEmpty(),
                    album = it.albumTitle?.toString(),
                    artwork = it.artworkUri?.toString(),
                    durationMs = it.durationMs ?: -1L,
                )
            },
            queue = tracks,
            queueIndex = index,
            hasPrevious = index > 0 || c.repeatMode == Player.REPEAT_MODE_ALL,
            hasNext = (index >= 0 && index < c.mediaItemCount - 1) || c.repeatMode == Player.REPEAT_MODE_ALL,
        )
    }
}
