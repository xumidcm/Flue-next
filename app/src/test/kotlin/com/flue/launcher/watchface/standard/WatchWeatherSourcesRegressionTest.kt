package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchWeatherSourcesRegressionTest {
    @Test
    fun repositoryReturningNullAfterLocationChangeCannotExposePreviousGlobalWeather() {
        val previous = WatchWeatherSources.applicationSource
        var selectedWeather: WatchWeatherSnapshot? = WatchWeatherSnapshot(location = "旧城市")
        try {
            WatchWeatherSources.applicationSource = WatchWeatherSource { selectedWeather }
            assertEquals("旧城市", WatchWeatherSources.default().snapshot(1_000L)?.location)
            selectedWeather = null

            assertNull(WatchWeatherSources.default().snapshot(1_001L))
        } finally {
            WatchWeatherSources.applicationSource = previous
        }
    }

    @Test
    fun publishedWeatherRemainsAvailableWhenNoApplicationRepositoryIsInstalled() {
        val previous = WatchWeatherSources.applicationSource
        try {
            WatchWeatherSources.applicationSource = null
            WatchWeatherSources.publish(WatchWeatherSnapshot(location = "注入城市"), 2_000L)

            assertEquals("注入城市", WatchWeatherSources.default().snapshot(2_001L)?.location)
        } finally {
            WatchWeatherSources.applicationSource = previous
        }
    }
}
