package com.tingyiting.playback

/**
 * 手写 Fake：以简单字段记录调用，替代 mock 巨型播放器接口。
 * 状态字段均可直接赋值，由测试驱动；不模拟真实播放行为。
 */
class FakeAudioPlayer : AudioPlayer {

    override var isPlaying: Boolean = false
    override var playWhenReady: Boolean = false
    override var playState: PlayState = PlayState.IDLE
    override var currentPosition: Long = 0
    override var duration: Long = 0
    override var currentItemIndex: Int = 0
    override val itemCount: Int get() = items.size

    val items = mutableListOf<PlayableItem>()
    val listeners = mutableListOf<AudioPlayer.Listener>()

    /** [itemIndex] 为 null 表示单参数 seekTo 重载。 */
    data class SeekCall(val itemIndex: Int?, val positionMs: Long)

    val seekCalls = mutableListOf<SeekCall>()
    val setItemCalls = mutableListOf<Pair<PlayableItem, Long>>()
    val replacedItems = mutableListOf<Pair<Int, PlayableItem>>()
    var prepareCount = 0
    var playCount = 0
    var pauseCount = 0
    var seekToNextCount = 0
    var seekToPreviousCount = 0

    /** 唯一已注册的监听器（ViewModel 应恰好注册一个）。 */
    val listener: AudioPlayer.Listener get() = listeners.single()

    override fun setPlaylist(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        this.items.clear()
        this.items += items
        currentItemIndex = startIndex
        currentPosition = startPositionMs
    }

    override fun setItem(item: PlayableItem, startPositionMs: Long) {
        items.clear()
        items += item
        currentItemIndex = 0
        currentPosition = startPositionMs
        setItemCalls += item to startPositionMs
    }

    override fun replaceItem(index: Int, item: PlayableItem) {
        items[index] = item
        replacedItems += index to item
    }

    override fun prepare() {
        prepareCount++
    }

    override fun play() {
        playCount++
        playWhenReady = true
    }

    override fun pause() {
        pauseCount++
        playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        seekCalls += SeekCall(null, positionMs)
        currentPosition = positionMs
    }

    override fun seekTo(itemIndex: Int, positionMs: Long) {
        seekCalls += SeekCall(itemIndex, positionMs)
        currentItemIndex = itemIndex
        currentPosition = positionMs
    }

    override fun seekToNextItem() {
        seekToNextCount++
    }

    override fun seekToPreviousItem() {
        seekToPreviousCount++
    }

    override fun addListener(listener: AudioPlayer.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioPlayer.Listener) {
        listeners -= listener
    }
}
