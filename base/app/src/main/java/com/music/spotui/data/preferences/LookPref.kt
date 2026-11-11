package com.music.spotui.data.preferences

/*
 * The app's look: Classic (today's) or Immersive, picked during the onboarding.
 */

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two looks. [CLASSIC] is the default, so nothing changes for anyone who has not chosen. */
enum class Look {
    CLASSIC, IMMERSIVE;

    companion object {
        fun of(name: String?): Look = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

object LookPref {
    private const val PREF = "settings_prefs"
    private const val KEY = "look"
    private const val KEY_CHOSEN = "look_chosen"
    private const val KEY_OFFERED = "look_offered"

    /** The chosen look, as Compose state: permanently locked to Classic Spotify UI */
    var look by mutableStateOf(Look.CLASSIC)
        private set

    fun set(c: Context, value: Look) {
        look = Look.CLASSIC
        prefs(c).edit().putString(KEY, Look.CLASSIC.name).apply()
    }

    /** The look step of the onboarding was seen (new installs and updates alike). */
    fun isChosen(c: Context): Boolean = true
    fun setChosen(c: Context) = prefs(c).edit().putBoolean(KEY_CHOSEN, true).putBoolean(KEY_OFFERED, true).apply()

    /** The new-look popup was shown to an existing user (or the onboarding look step covered it). */
    fun isOffered(c: Context): Boolean = true
    fun setOffered(c: Context) = prefs(c).edit().putBoolean(KEY_OFFERED, true).apply()
    fun clearOffered(c: Context) = prefs(c).edit().remove(KEY_OFFERED).apply()

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
