package com.example.livetranslator

import java.util.Locale

data class LanguageOption(
    val label: String,
    val bcp47: String
) {
    fun locale(): Locale = Locale.forLanguageTag(bcp47)
}

object LanguageOptions {
    val list: List<LanguageOption> = listOf(
        LanguageOption("English",    "en"),
        LanguageOption("Czech",      "cs"),
        LanguageOption("Polish",     "pl"),
        LanguageOption("German",     "de"),
        LanguageOption("French",     "fr"),
        LanguageOption("Spanish",    "es"),
        LanguageOption("Italian",    "it"),
        LanguageOption("Portuguese", "pt"),
        LanguageOption("Dutch",      "nl"),
        LanguageOption("Russian",    "ru"),
        LanguageOption("Ukrainian",  "uk"),
        LanguageOption("Turkish",    "tr"),
        LanguageOption("Arabic",     "ar"),
        LanguageOption("Chinese",    "zh"),
        LanguageOption("Japanese",   "ja"),
        LanguageOption("Korean",     "ko")
    )

    /** Alias kept for backward compatibility */
    val ALL: List<LanguageOption> get() = list

    fun indexOfTag(tag: String): Int {
        val i = list.indexOfFirst { it.bcp47.equals(tag, ignoreCase = true) }
        return if (i >= 0) i else 0
    }
}
