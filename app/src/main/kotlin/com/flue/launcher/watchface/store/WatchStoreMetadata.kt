package com.flue.launcher.watchface.store

internal fun normalizedWatchStoreAuthor(author: String): String? =
    author.trim().takeIf(String::isNotEmpty)
