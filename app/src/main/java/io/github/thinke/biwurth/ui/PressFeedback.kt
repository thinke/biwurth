package io.github.thinke.biwurth.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The panel's tap feedback: the pressed tile shrinks a little and springs back. It replaces the
 * Material ripple, whose Android 12+ "sparkle" (a noise pattern over the whole ripple) showed as
 * white speckles across a tile on every tap. A shrink also follows any tile's shape, pills and
 * rounded cards alike, where a highlight drawn over the bounds would show square corners.
 */
object PressFeedback : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val shrink = Animatable(1f)

        override fun onAttach() {
            coroutineScope.launch {
                var presses = 0
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses++
                        is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                        else -> return@collect
                    }
                    launch {
                        shrink.animateTo(if (presses > 0) PRESSED else 1f, tween(if (presses > 0) 80 else 160))
                    }
                }
            }
            coroutineScope.launch {
                androidx.compose.runtime.snapshotFlow { shrink.value }.collect { invalidateDraw() }
            }
        }

        override fun ContentDrawScope.draw() {
            val s = shrink.value
            if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
        }
    }

    private const val PRESSED = 0.96f
}
