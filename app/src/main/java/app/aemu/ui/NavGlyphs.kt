/* AEmulator-Plus (somerandomusernam2): the NavGlyphs line drawings from ui/HoloNavigation.kt, ported for the
 * VM screen themes (#32). The Plus HoloNavButton composable is not ported: this fork's bar keeps its own
 * hold-the-key gesture handling in VmActivity and only borrows the glyph drawings. GPL-3.0. */
package app.aemu.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.aemu.core.GlyphSet
import app.aemu.core.NavButton
import app.aemu.core.VmTheme

/** The line drawings of the keys on a 32x32 grid, per glyph set; keys a set has no own shape for use the Sunset ones. */
internal object NavGlyphs {
    fun draw(scope: DrawScope, button: NavButton, theme: VmTheme, pressed: Boolean) = with(scope) {
        val s = size.width / 32f
        val ink = theme.iconColor?.let { Color(it) } ?: Color.White
        val width = when (theme.glyphs) { GlyphSet.GINGERBREAD -> 2.6f; GlyphSet.HONEYCOMB -> 2.2f; GlyphSet.HOLO -> 1.7f; else -> 2f }
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            moveTo(pts[0].first * s, pts[0].second * s)
            pts.drop(1).forEach { lineTo(it.first * s, it.second * s) }
            if (close) close()
        }
        // a line, with the theme's soft halo under it (Honeycomb, Holo)
        fun stroke(pathToDraw: Path) {
            if (theme.halo) drawPath(pathToDraw, ink.copy(alpha = if (pressed) 0.35f else 0.16f), style = Stroke(5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(pathToDraw, ink.copy(alpha = if (theme.halo) 0.92f else 1f), style = Stroke(width * s, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
        fun lines(vararg pts: Pair<Float, Float>, close: Boolean = false) = stroke(path(*pts, close = close))
        fun fill(pathToFill: Path) = drawPath(pathToFill, ink)
        fun ring(cx: Float, cy: Float, r: Float) = stroke(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx * s, cy * s), r * s)) })
        fun magnifier() { ring(13f, 13f, 7f); lines(18.5f to 18.5f, 26f to 26f) }
        // U-turn arrow: head pointing left, shaft along the top, half circle down the right (Gingerbread key, Holo bar)
        fun uTurn() {
            lines(11f to 6.5f, 5.5f to 12f, 11f to 17.5f)
            stroke(Path().apply {
                moveTo(5.5f * s, 12f * s); lineTo(19.5f * s, 12f * s)
                arcTo(androidx.compose.ui.geometry.Rect(13f * s, 12f * s, 26f * s, 25f * s), -90f, 180f, false)
                lineTo(9f * s, 25f * s)
            })
        }
        fun windows() {
            lines(6f to 11f, 21f to 11f, 21f to 26f, 6f to 26f, close = true)
            lines(10.5f to 11f, 10.5f to 6f, 26f to 6f, 26f to 21.5f, 21f to 21.5f)
        }
        val own: Boolean = when (theme.glyphs) {
            // Gingerbread 2.x: the hardware key icons - curved back, solid house, three menu bars, magnifier
            GlyphSet.GINGERBREAD -> when (button) {
                NavButton.BACK -> { uTurn(); true }
                NavButton.HOME -> { fill(path(4f to 16f, 16f to 6f, 28f to 16f, 25f to 16f, 25f to 26f, 7f to 26f, 7f to 16f, close = true)); true }
                NavButton.MENU -> { for (y in listOf(8f, 14.5f, 21f)) drawRect(ink, p(7f, y), androidx.compose.ui.geometry.Size(18f * s, 3.5f * s)); true }
                NavButton.SEARCH -> { magnifier(); true }
                else -> false
            }
            // Honeycomb 3.x: the angular "Tron" keys - hooked back arrow, tall house, stacked windows
            GlyphSet.HONEYCOMB -> when (button) {
                NavButton.BACK -> { lines(12f to 9f, 5f to 16f, 12f to 23f); lines(5f to 16f, 25f to 16f, 25f to 7f); true }
                NavButton.HOME -> { lines(6f to 14f, 16f to 5f, 26f to 14f); lines(9f to 12f, 9f to 27f, 23f to 27f, 23f to 12f); true }
                NavButton.RECENTS -> { windows(); true }
                else -> false
            }
            // Holo 4.x: the ICS/JB system bar
            GlyphSet.HOLO -> when (button) {
                NavButton.BACK -> { uTurn(); true }
                NavButton.HOME -> { lines(4.5f to 16f, 16f to 8f, 27.5f to 16f); lines(8f to 13.5f, 8f to 25.5f, 24f to 25.5f, 24f to 13.5f); true }
                NavButton.RECENTS -> { windows(); true }
                else -> false
            }
            // Lollipop 5.x: triangle, circle, square; menu as three dots
            GlyphSet.LOLLIPOP -> when (button) {
                NavButton.BACK -> { lines(22f to 7f, 8f to 16f, 22f to 25f, close = true); true }
                NavButton.HOME -> { ring(16f, 16f, 8f); true }
                NavButton.RECENTS -> { lines(8.5f to 8.5f, 23.5f to 8.5f, 23.5f to 23.5f, 8.5f to 23.5f, close = true); true }
                NavButton.MENU -> { for (y in listOf(9f, 16f, 23f)) drawCircle(ink, 2.2f * s, p(16f, y)); true }
                NavButton.SEARCH -> { magnifier(); true }
                else -> false
            }
            else -> false
        }
        if (own) return@with
        // Sunset outlines (also every key an era set leaves to them)
        when (button) {
            NavButton.BACK -> { lines(13f to 9f, 6f to 16f, 13f to 23f); lines(6f to 16f, 24f to 16f, 24f to 23f) }
            NavButton.HOME -> lines(6f to 16f, 16f to 8f, 26f to 16f, 26f to 25f, 6f to 25f, close = true)
            NavButton.RECENTS -> { lines(10f to 10f, 10f to 6f, 26f to 6f, 26f to 21f, 22f to 21f); lines(6f to 11f, 21f to 11f, 21f to 26f, 6f to 26f, close = true) }
            NavButton.MENU -> for (y in listOf(7f, 14f, 21f)) drawRect(ink, p(14f, y), androidx.compose.ui.geometry.Size(4f * s, 4f * s))
            NavButton.SEARCH -> magnifier()
            NavButton.POWER -> {
                stroke(Path().apply { arcTo(androidx.compose.ui.geometry.Rect(6f * s, 6f * s, 26f * s, 26f * s), -55f, 290f, true) })
                lines(16f to 3f, 16f to 15f)
            }
            NavButton.VOLUME_DOWN, NavButton.VOLUME_UP -> {
                lines(5f to 13f, 10f to 13f, 16f to 8f, 16f to 24f, 10f to 19f, 5f to 19f, close = true)
                lines(21f to 16f, 29f to 16f)
                if (button == NavButton.VOLUME_UP) lines(25f to 12f, 25f to 20f)
            }
            NavButton.UP -> lines(8f to 21f, 16f to 12f, 24f to 21f)
            NavButton.DOWN -> lines(8f to 11f, 16f to 20f, 24f to 11f)
            NavButton.LEFT -> lines(21f to 8f, 12f to 16f, 21f to 24f)
            NavButton.RIGHT -> lines(11f to 8f, 20f to 16f, 11f to 24f)
            NavButton.CENTER -> ring(16f, 16f, 7f)
        }
    }
}
