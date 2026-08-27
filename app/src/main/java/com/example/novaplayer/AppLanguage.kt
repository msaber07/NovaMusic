package com.example.novaplayer

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Stores and applies the user's in-app language preference. */
object AppLanguage {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val TURKISH = "tr"

    private const val PREFERENCES = "nova_preferences"
    private const val LANGUAGE_KEY = "app_language"

    fun selected(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(LANGUAGE_KEY, SYSTEM)
        ?: SYSTEM

    fun wrap(context: Context): Context {
        val languageTag = selected(context)
        if (languageTag == SYSTEM) return context

        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isBlank()) return context

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }

    fun set(context: Context, languageTag: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, languageTag)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            localeManager.applicationLocales = if (languageTag == SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
        }
    }
}
