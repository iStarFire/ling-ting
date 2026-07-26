package com.tingyiting.ui.player

import com.tingyiting.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPlaybackStateTest {

    @Test
    fun `track is completed at 95 percent`() {
        assertTrue(isTrackCompleted(track(position = 95_000, duration = 100_000)))
        assertFalse(isTrackCompleted(track(position = 94_999, duration = 100_000)))
    }

    @Test
    fun `track without duration is not completed`() {
        assertFalse(isTrackCompleted(track(position = 10_000, duration = 0)))
        assertEquals(0, trackProgressPercent(track(position = 10_000, duration = 0)))
    }

    @Test
    fun `completed track restarts and unfinished track resumes`() {
        assertEquals(0, resumePosition(track(position = 95_000, duration = 100_000)))
        assertEquals(42_000, resumePosition(track(position = 42_000, duration = 100_000)))
    }

    @Test
    fun `progress percentage is clamped to duration`() {
        assertEquals(42, trackProgressPercent(track(position = 42_999, duration = 100_000)))
        assertEquals(100, trackProgressPercent(track(position = 120_000, duration = 100_000)))
    }

    private fun track(position: Long, duration: Long) = Track(
        index = 0,
        title = "Episode",
        webdavUrl = "https://example.com/episode.mp3",
        path = "/episode.mp3",
        position = position,
        duration = duration
    )
}
