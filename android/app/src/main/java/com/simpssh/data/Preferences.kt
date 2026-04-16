package com.simpssh.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.simpssh.ui.ThemePalette
import org.json.JSONArray
import org.json.JSONObject

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("simpssh_prefs", Context.MODE_PRIVATE)

    var themeName: String
        get() = prefs.getString(KEY_THEME, "default") ?: "default"
        set(v) { prefs.edit().putString(KEY_THEME, v).apply() }

    fun loadCustomPalettes(): List<ThemePalette> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ThemePalette(
                    name = o.getString("name"),
                    displayName = o.getString("displayName"),
                    primary = o.colorAt("primary"),
                    onPrimary = o.colorAt("onPrimary"),
                    primaryContainer = o.colorAt("primaryContainer"),
                    onPrimaryContainer = o.colorAt("onPrimaryContainer"),
                    darkBackground = o.colorAt("darkBackground"),
                    darkSurface = o.colorAt("darkSurface"),
                    darkSurfaceVariant = o.colorAt("darkSurfaceVariant"),
                    custom = true,
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveCustomPalettes(list: List<ThemePalette>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("displayName", p.displayName)
                put("primary", p.primary.toArgb())
                put("onPrimary", p.onPrimary.toArgb())
                put("primaryContainer", p.primaryContainer.toArgb())
                put("onPrimaryContainer", p.onPrimaryContainer.toArgb())
                put("darkBackground", p.darkBackground.toArgb())
                put("darkSurface", p.darkSurface.toArgb())
                put("darkSurfaceVariant", p.darkSurfaceVariant.toArgb())
            })
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun JSONObject.colorAt(key: String): Color = Color(getInt(key))

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_CUSTOM = "custom_palettes"
    }
}
