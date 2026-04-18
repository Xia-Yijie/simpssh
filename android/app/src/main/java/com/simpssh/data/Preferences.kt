package com.simpssh.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.simpssh.ui.DefaultToolbarKeyIds
import com.simpssh.ui.ThemePalette
import org.json.JSONArray
import org.json.JSONObject

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("simpssh_prefs", Context.MODE_PRIVATE)

    var themeName: String
        get() = prefs.getString(KEY_THEME, "default") ?: "default"
        set(v) { prefs.edit().putString(KEY_THEME, v).apply() }

    // 必须先用 isFinite 排除 NaN,单独的 coerceIn 不会把 NaN 夹回范围,
    // 脏值会一路传到文本布局引起崩溃
    var terminalFontSize: Float
        get() {
            val raw = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_TERM_FONT_SIZE)
            return if (raw.isFinite()) raw.coerceIn(TERM_FONT_SIZE_MIN, TERM_FONT_SIZE_MAX)
                   else DEFAULT_TERM_FONT_SIZE
        }
        set(v) {
            val safe = if (v.isFinite()) v.coerceIn(TERM_FONT_SIZE_MIN, TERM_FONT_SIZE_MAX)
                       else DEFAULT_TERM_FONT_SIZE
            prefs.edit().putFloat(KEY_FONT_SIZE, safe).apply()
        }

    var showModeBadges: Boolean
        get() = prefs.getBoolean(KEY_MODE_BADGES, false)
        set(v) { prefs.edit().putBoolean(KEY_MODE_BADGES, v).apply() }

    var toolbarKeyIds: List<String>
        get() {
            val raw = prefs.getString(KEY_TOOLBAR, null) ?: return DefaultToolbarKeyIds
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrElse { DefaultToolbarKeyIds }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            prefs.edit().putString(KEY_TOOLBAR, arr.toString()).apply()
        }

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
        const val KEY_TOOLBAR = "toolbar_keys"
        const val KEY_FONT_SIZE = "terminal_font_size"
        const val KEY_MODE_BADGES = "show_mode_badges"
    }
}

const val DEFAULT_TERM_FONT_SIZE: Float = 11f
const val TERM_FONT_SIZE_MIN: Float = 8f
const val TERM_FONT_SIZE_MAX: Float = 28f
