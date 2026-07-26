package com.tingyiting.ui.navigation

import androidx.lifecycle.ViewModel
import com.tingyiting.playback.PlaybackInfo
import com.tingyiting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NavigationPlaybackViewModel @Inject constructor(
    playbackState: PlaybackState
) : ViewModel() {
    val playbackInfo: StateFlow<PlaybackInfo?> = playbackState.info
}
