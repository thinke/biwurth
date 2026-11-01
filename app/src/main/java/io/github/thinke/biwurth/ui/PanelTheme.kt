package io.github.thinke.biwurth.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.thinke.biwurth.config.ThemeSettings
import io.github.thinke.biwurth.config.ThemeStore

/** Colours of the panel (not the website). Picked per layout: a preset and an optional accent. */
data class PanelTheme(
    val background: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    /** Text on an accent-coloured tile (a toggle that is on). */
    val onAccent: Color,
) {
    companion object {
        val PRESETS = mapOf(
            "dark" to PanelTheme(Color(0xFF000000), Color(0xFF1A1A1A), Color.White, Color(0xFF9A9A9A), Color(0xFFE0A030), Color.Black),
            "graphite" to PanelTheme(Color(0xFF16181C), Color(0xFF23262C), Color(0xFFECEDEF), Color(0xFF8E939C), Color(0xFF5AA9E6), Color.Black),
            "nord" to PanelTheme(Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFFECEFF4), Color(0xFFA3ABBA), Color(0xFF88C0D0), Color(0xFF2E3440)),
            "forest" to PanelTheme(Color(0xFF0F1A14), Color(0xFF1A2A20), Color(0xFFE6EFE8), Color(0xFF8FA597), Color(0xFF7BC67E), Color(0xFF0F1A14)),
            "light" to PanelTheme(Color(0xFFF2F2F0), Color.White, Color(0xFF1C1C1C), Color(0xFF6E6E6E), Color(0xFFD48A12), Color.White),
        )

        /** The colours for [settings]: a built-in preset, or an installed theme's ([themes], by id). */
        fun from(settings: ThemeSettings, themes: Map<String, ThemeStore.Theme> = emptyMap()): PanelTheme {
            val preset = PRESETS[settings.preset] ?: themes[settings.preset]?.let(::palette) ?: PRESETS.getValue("dark")
            val accent = settings.accent?.let(::parseColor) ?: return preset
            return preset.copy(accent = accent, onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White)
        }

        // A theme package's colours over its base preset (theme.json "base" and "colors").
        private fun palette(theme: ThemeStore.Theme): PanelTheme {
            val base = PRESETS[theme.json.optString("base")] ?: PRESETS.getValue("dark")
            val colors = theme.json.optJSONObject("colors") ?: return base
            fun pick(key: String, fallback: Color) = colors.optString(key).takeIf { it.isNotEmpty() }?.let(::parseColor) ?: fallback
            val accent = pick("accent", base.accent)
            return PanelTheme(
                background = pick("background", base.background),
                card = pick("card", base.card),
                text = pick("text", base.text),
                muted = pick("muted", base.muted),
                accent = accent,
                onAccent = pick("onAccent", if (colors.has("accent")) (if (accent.luminance() > 0.5f) Color.Black else Color.White) else base.onAccent),
            )
        }

        private fun parseColor(hex: String): Color? =
            hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

        private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    }
}

val LocalPanelTheme = staticCompositionLocalOf { PanelTheme.PRESETS.getValue("dark") }
