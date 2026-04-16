package com.simpssh.data

import android.content.Context

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("simpssh_prefs", Context.MODE_PRIVATE)

    var themeName: String
        get() = prefs.getString(KEY_THEME, "default") ?: "default"
        set(v) { prefs.edit().putString(KEY_THEME, v).apply() }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
