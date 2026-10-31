package io.github.thinke.biwurth.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.thinke.biwurth.R

// Inter (SIL Open Font License, licenses/Inter-OFL.txt): one variable font, every weight.
@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    listOf(FontWeight.ExtraLight, FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { weight ->
        Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

/**
 * The panel's base text style, for the theme's font: tabular figures everywhere, so the clock and
 * changing values don't shift sideways as digits change.
 */
fun panelTextStyle(font: String) = TextStyle(
    fontFamily = if (font == "system") FontFamily.Default else Inter,
    fontFeatureSettings = "tnum",
)
