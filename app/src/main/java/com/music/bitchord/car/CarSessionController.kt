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
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ACTION_TOGGLE_FAVORITE
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toSong
import com.music.bitchord.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How many similar tracks to queue behind a song started from search. */
private const val MAX_STATION_TRACKS = 25

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
        CarPlaylistGateway.init(app)
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

    /** RPC entry point: the browser names a method and gets one typed answer. */
    fun handle(call: CallDto, onResult: (ResultDto) -> Unit) {
        scope.launch {
            runCatching { execute(call) }
                .onSuccess { data ->
                    onResult(ResultDto(id = call.id, data = data))
                    publishState()
                }
                .onFailure { t ->
                    val message = t.message ?: "호출 실패"
                    onResult(ResultDto(id = call.id, ok = false, error = message, message = message))
                }
        }
    }

    private suspend fun execute(call: CallDto): JsonElement? {
        val p = call.params
        return when (call.method) {
            "search" -> {
                val (id, songs) = CarSearchGateway.search(p.string("query").orEmpty())
                CarJson.codec.encodeToJsonElement(
                    SearchDataDto.serializer(),
                    SearchDataDto(songs = songs, searchId = id),
                )
            }
            "search.detailed" -> CarJson.codec.encodeToJsonElement(
                SearchDataDto.serializer(),
                CarSearchGateway.detailed(
                    p.string("query").orEmpty(),
                    runCatching { SearchFilter.valueOf(p.string("filter").orEmpty()) }
                        .getOrDefault(SearchFilter.ALL),
                ),
            )
            "search.recents" -> CarJson.codec.encodeToJsonElement(
                SearchItemListDto.serializer(),
                SearchItemListDto(CarSearchGateway.recents()),
            )
            "library" -> CarJson.codec.encodeToJsonElement(
                LibraryDataDto.serializer(),
                CarLibraryGateway.load(),
            )
            "play.item" -> {
                val kind = p.string("kind").orEmpty()
                require(kind == "song" || kind == "video") { "앨범·아티스트·플레이리스트는 상세 페이지에서 재생해 주세요" }
                val songs = CarPlayGateway.play(
                    kind = kind,
                    id = p.string("id").orEmpty(),
                    title = p.string("title").orEmpty(),
                    artist = p.string("artist").orEmpty(),
                    thumbnailUrl = p.string("thumbnailUrl"),
                )
                require(songs.isNotEmpty()) { "재생할 곡이 없습니다" }
                requireController().playSongs(songs, 0)
                null
            }
            "home" -> CarJson.codec.encodeToJsonElement(
                HomeDataDto.serializer(),
                CarHomeGateway.load(),
            )
            "home.play" -> {
                val song = CarHomeGateway.playable(p.int("shelf"), p.int("item"))
                    ?: error("홈 피드가 만료되었습니다")
                requireController().playSongs(listOf(song), 0)
                null
            }
            "player.play", "player.pause", "player.toggle", "player.next",
            "player.previous", "player.shuffle", "player.repeat", "player.like" -> {
                execute(CommandDto(id = call.id, name = call.method.removePrefix("player.")))
                null
            }
            "player.seek" -> {
                execute(CommandDto(id = call.id, name = "seek", positionMs = p.long("positionMs")))
                null
            }
            "player.skipTo" -> {
                execute(CommandDto(id = call.id, name = "skipTo", index = p.int("index")))
                null
            }
            "player.volume" -> {
                execute(
                    CommandDto(
                        id = call.id,
                        name = "volume",
                        level = p["level"]?.jsonPrimitive?.intOrNull,
                        delta = p["delta"]?.jsonPrimitive?.intOrNull,
                    ),
                )
                null
            }
            "player.playStation" -> {
                // A song tapped in search starts a station: the tapped track
                // leads, and YouTube Music's own "up next" for that track fills
                // the rest of the queue. The search result list is not a queue.
                val id = p.string("id").orEmpty()
                require(id.isNotBlank()) { "재생할 곡이 없습니다" }
                val kind = p.string("kind").orEmpty()
                val isVideo = kind == "video"
                val title = p.string("title").orEmpty()
                val seed = Song(
                    videoId = id,
                    title = title,
                    artist = p.string("artist").orEmpty(),
                    thumbnailUrl = p.string("thumbnailUrl"),
                    durationText = p.string("durationText"),
                    isVideo = isVideo,
                    isVideoOrigin = isVideo,
                    radioName = title.ifBlank { "라디오" },
                )
                val related = withContext(Dispatchers.IO) {
                    runCatching {
                        YtMusicRepository.radio(id, preferVideo = isVideo).getOrNull().orEmpty()
                    }.getOrDefault(emptyList())
                }
                val station = seed.title.ifBlank { "라디오" }
                val queue = buildList {
                    add(seed.copy(radioName = station))
                    related.asSequence()
                        .filter { it.videoId.isNotBlank() && it.videoId != id }
                        .distinctBy { it.videoId }
                        .take(MAX_STATION_TRACKS)
                        .forEach { add(it.copy(radioName = station, queueTier = QueueTier.AUTOPLAY)) }
                }
                requireController().playSongs(queue, 0)
                null
            }
            "player.playSearch" -> {
                val pair = CarSearchGateway.tracks(p.string("searchId").orEmpty(), p.int("index"))
                    ?: error("검색 결과가 만료되었습니다")
                requireController().playSongs(pair.first, pair.second)
                null
            }
            "queue.move" -> {
                requireController().moveMediaItem(p.int("from"), p.int("to"))
                null
            }
            "queue.remove" -> {
                requireController().removeMediaItem(p.int("index"))
                null
            }
            "lyrics.get" -> CarJson.codec.encodeToJsonElement(
                LyricsDataDto.serializer(),
                CarLyricsGateway.current(app, requireController()),
            )
            "detail" -> CarJson.codec.encodeToJsonElement(
                DetailDataDto.serializer(),
                CarDetailGateway.load(
                    kind = p.string("kind").orEmpty(),
                    id = p.string("id").orEmpty(),
                    title = p.string("title").orEmpty(),
                    subtitle = p.string("subtitle").orEmpty(),
                    artwork = p.string("artwork"),
                ),
            )
            "detail.play" -> {
                CarDetailGateway.play(requireController(), p.string("key").orEmpty(), p.int("index"))
                null
            }
            "playlist.list" -> CarJson.codec.encodeToJsonElement(
                PlaylistListDto.serializer(),
                CarPlaylistGateway.list(),
            )
            "playlist.tracks" -> CarJson.codec.encodeToJsonElement(
                SongListDto.serializer(),
                CarPlaylistGateway.tracks(p.string("id").orEmpty()),
            )
            "playlist.play" -> {
                CarPlaylistGateway.play(
                    requireController(),
                    p.string("id").orEmpty(),
                    p.int("index"),
                )
                null
            }
            "ping" -> null
            else -> error("알 수 없는 메서드: ${call.method}")
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String, fallback: Int = 0): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: fallback

    private fun JsonObject.long(key: String, fallback: Long = 0L): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: fallback

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

    /**
     * The same three sources the notification uses: a device file's like lives
     * in [OfflinePlaylists], a YouTube track's in the in-memory [LikeState], and
     * a freshly started process falls back to the persisted widget snapshot.
     * No network call here — the heart follows the tap immediately.
     */
    private fun isLiked(item: MediaItem?): Boolean {
        if (item == null) return false
        val id = item.mediaId.orEmpty()
        if (id.isBlank()) return false
        val song = runCatching { item.toSong() }.getOrNull()
        if (song?.localUri != null && OfflinePlaylists.isLiked(song)) return true
        if (song?.localUri == null && LikeState.overrides.value[id] == LikeStatus.LIKE) return true
        return runCatching {
            MediaWidgetSnapshot.load(app).takeIf { it.mediaId == id }?.isLiked == true
        }.getOrDefault(false)
    }

    private fun snapshot(c: MediaController): StateDto {
        val currentItem = c.currentMediaItem
        val meta = currentItem?.mediaMetadata
        val now = System.currentTimeMillis()

        val tracks = (0 until c.mediaItemCount).mapNotNull { i ->
            val item = c.getMediaItemAt(i)
            val m = item.mediaMetadata
            val title = m.title?.toString()
            TrackDto(
                videoId = item.mediaId.orEmpty(),
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
            liked = isLiked(currentItem),
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
            track = meta?.let {
                TrackDto(
                    videoId = currentItem.mediaId.orEmpty(),
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
