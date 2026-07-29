package com.flue.launcher.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlueLocaleManagerTest {

    @Test
    fun saveLanguageCommitsCanonicalValueSynchronously() {
        val preferences = RecordingPreferences(commitResult = true)
        val store = LanguagePreferenceStore(preferences.instance)

        assertTrue(store.saveLanguage(" EN-us ").isSuccess)
        assertEquals("en", preferences.savedLanguage)
        assertFalse(preferences.applyCalled)
    }

    @Test
    fun saveLanguageReportsCommitFailureWithoutReplacingStoredValue() {
        val preferences = RecordingPreferences(
            commitResult = false,
            initialLanguage = "ja",
        )
        val store = LanguagePreferenceStore(preferences.instance)

        assertFalse(store.saveLanguage("zh").isSuccess)
        assertEquals("ja", preferences.savedLanguage)
        assertFalse(preferences.applyCalled)
    }

    @Test
    fun readLanguageNormalizesSupportedTagsAndUnknownValues() {
        val cases = mapOf(
            " zh_CN " to "zh",
            "EN-us" to "en",
            "ja-JP" to "ja",
            "" to FlueLocaleManager.LANGUAGE_SYSTEM,
            "unsupported" to FlueLocaleManager.LANGUAGE_SYSTEM,
        )

        cases.forEach { (stored, expected) ->
            val preferences = RecordingPreferences(
                commitResult = true,
                initialLanguage = stored,
            )

            assertEquals(expected, LanguagePreferenceStore(preferences.instance).readLanguage())
        }
    }

    @Test
    fun localeSelectionUsesSupportedSystemLocaleAndFallsBackToEnglish() {
        val systemLocale = Locale.CANADA_FRENCH

        assertEquals(Locale.ENGLISH, localeForLanguage("", systemLocale))
        assertEquals(Locale.JAPAN, localeForLanguage("", Locale.JAPAN))
        assertEquals(Locale.SIMPLIFIED_CHINESE, localeForLanguage("", Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh", localeForLanguage("zh", systemLocale).language)
        assertEquals("en", localeForLanguage("en", systemLocale).language)
        assertEquals("ja", localeForLanguage("ja", systemLocale).language)
    }

    @Test
    fun languageSelectionPersistsStartupTruthThenAwaitsMirror() = runBlocking {
        val events = mutableListOf<String>()

        val result = persistLanguageSelection(
            language = " JA-jp ",
            saveStartupLanguage = { canonicalLanguage ->
                events += "startup:$canonicalLanguage"
                true
            },
            mirrorLanguage = { canonicalLanguage ->
                events += "mirror-start:$canonicalLanguage"
                events += "mirror-complete:$canonicalLanguage"
            },
        )

        assertTrue(result.isSuccess)
        assertEquals("ja", result.language)
        assertEquals(
            listOf("startup:ja", "mirror-start:ja", "mirror-complete:ja"),
            events,
        )
    }

    @Test
    fun languageSelectionDoesNotMirrorWhenStartupCommitFails() = runBlocking {
        val events = mutableListOf<String>()

        val result = persistLanguageSelection(
            language = "zh",
            saveStartupLanguage = {
                events += "startup"
                false
            },
            mirrorLanguage = {
                events += "mirror"
            },
        )

        assertFalse(result.isSuccess)
        assertEquals(listOf("startup"), events)
    }

    @Test
    fun mirrorFailureDoesNotOverrideCommittedStartupTruth() = runBlocking {
        val events = mutableListOf<String>()

        val result = persistLanguageSelection(
            language = "en",
            saveStartupLanguage = {
                events += "startup"
                true
            },
            mirrorLanguage = {
                events += "mirror-start"
                delay(1)
                events += "mirror-fail"
                error("mirror unavailable")
            },
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("startup", "mirror-start", "mirror-fail"), events)
    }

    @Test
    fun failedPersistenceDoesNotOpenTheProcessRestartGate() = runBlocking {
        var restartCalled = false
        val result = persistLanguageSelection(
            language = "ja",
            saveStartupLanguage = { false },
            mirrorLanguage = { error("must not run") },
        )

        if (result.isSuccess) restartCalled = true

        assertFalse(restartCalled)
    }

    @Test
    fun saveLanguageFollowSystemRestoresSystemDefaultLocale() {
        val preferences = RecordingPreferences(commitResult = true, initialLanguage = "ja")
        val context = PreferencesContext(preferences.instance)
        val systemLocale = Locale.CANADA_FRENCH
        val previousLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.JAPANESE)

            assertTrue(
                FlueLocaleManager.saveLanguage(
                    context,
                    FlueLocaleManager.LANGUAGE_SYSTEM,
                    systemLocale,
                ),
            )
            assertEquals(Locale.ENGLISH, Locale.getDefault())
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

private class PreferencesContext(
    private val preferences: SharedPreferences,
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

private class RecordingPreferences(
    private val commitResult: Boolean,
    initialLanguage: String? = null,
) {
    var savedLanguage: String? = initialLanguage
        private set
    var applyCalled: Boolean = false
        private set

    private var pendingLanguage: String? = initialLanguage

    private val editor = proxy<SharedPreferences.Editor> { proxy, methodName, args ->
        when (methodName) {
            "putString" -> {
                pendingLanguage = args?.get(1) as String?
                proxy
            }
            "commit" -> {
                if (commitResult) savedLanguage = pendingLanguage
                commitResult
            }
            "apply" -> {
                applyCalled = true
                if (commitResult) savedLanguage = pendingLanguage
                null
            }
            else -> error("Unexpected SharedPreferences.Editor call: $methodName")
        }
    }

    val instance = proxy<SharedPreferences> { _, methodName, args ->
        when (methodName) {
            "edit" -> editor
            "getString" -> savedLanguage ?: args?.get(1)
            else -> error("Unexpected SharedPreferences call: $methodName")
        }
    }
}

private inline fun <reified T> proxy(
    crossinline invocation: (proxy: Any, methodName: String, args: Array<out Any?>?) -> Any?,
): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { proxy, method, args -> invocation(proxy, method.name, args) } as T
