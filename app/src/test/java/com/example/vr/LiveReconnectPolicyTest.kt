package com.example.vr

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectPolicyTest {

    @Test
    fun nextDelayMs_followsExponentialBackoffWithCap() {
        assertEquals(2_000L, LiveReconnectPolicy.nextDelayMs(-1))
        assertEquals(2_000L, LiveReconnectPolicy.nextDelayMs(0))
        assertEquals(4_000L, LiveReconnectPolicy.nextDelayMs(1))
        assertEquals(8_000L, LiveReconnectPolicy.nextDelayMs(2))
        assertEquals(16_000L, LiveReconnectPolicy.nextDelayMs(3))
        assertEquals(30_000L, LiveReconnectPolicy.nextDelayMs(4))
        assertEquals(30_000L, LiveReconnectPolicy.nextDelayMs(5))
        assertEquals(30_000L, LiveReconnectPolicy.nextDelayMs(100))
    }

    @Test
    fun isRetryableLiveError_acceptsTransientIoAndBehindLiveWindow() {
        assertTrue(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                ExoPlaybackException.TYPE_SOURCE
            )
        )
        assertTrue(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                ExoPlaybackException.TYPE_SOURCE
            )
        )
        assertTrue(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                ExoPlaybackException.TYPE_SOURCE
            )
        )
        assertTrue(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                ExoPlaybackException.TYPE_UNEXPECTED
            )
        )
    }

    @Test
    fun isRetryableLiveError_rejectsDecoderAndDrmFailures() {
        assertFalse(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                ExoPlaybackException.TYPE_RENDERER
            )
        )
        assertFalse(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                ExoPlaybackException.TYPE_RENDERER
            )
        )
        assertFalse(
            LiveReconnectPolicy.isRetryableLiveError(
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                ExoPlaybackException.TYPE_RENDERER
            )
        )
    }

    @Test
    fun formatVideoTrackLabel_formatsResolutionAndBitrate() {
        assertEquals(
            "1080p (1920×1080) · 5.2 Mbps",
            LiveReconnectPolicy.formatVideoTrackLabel(1920, 1080, 5_200_000, 0)
        )
        assertEquals(
            "4K (3840×2160) · 18.0 Mbps",
            LiveReconnectPolicy.formatVideoTrackLabel(3840, 2160, 18_000_000, 1)
        )
        assertEquals(
            "720p (1280×720) · 850 kbps",
            LiveReconnectPolicy.formatVideoTrackLabel(1280, 720, 850_000, 2)
        )
        assertEquals(
            "#3 · 1.5 Mbps",
            LiveReconnectPolicy.formatVideoTrackLabel(-1, -1, 1_500_000, 2)
        )
    }
}
