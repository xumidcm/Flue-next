package com.flue.launcher.ui.common

import com.flue.launcher.R
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the rounded fallback icon set explicit. These IDs are referenced by
 * Compose painters in the store, control center and notification surfaces;
 * a missing resource should fail the unit suite before a release build.
 */
class RoundedIconResourceTest {
    @Test
    fun roundedFallbackIconsAreGenerated() {
        val ids = listOf(
            R.drawable.ic_flue_check_round,
            R.drawable.ic_flue_download_round,
            R.drawable.ic_flue_media_next_round,
            R.drawable.ic_flue_media_pause_round,
            R.drawable.ic_flue_media_play_round,
            R.drawable.ic_flue_media_previous_round,
            R.drawable.ic_flue_music_round,
            R.drawable.ic_flue_notification_round,
            R.drawable.ic_flue_refresh_round,
        )
        ids.forEach { id -> assertTrue("drawable resource ID must be non-zero", id != 0) }
    }
}
