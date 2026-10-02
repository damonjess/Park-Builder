package com.example.parkbuilder.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.ParkMap

/**
 * Toolbar icons, rasterised once from the park's own art.
 *
 * The emoji glyphs that used to stand in for the catalogue were the weakest thing on
 * screen: they render differently on every device, they are never the right colour, and
 * they share nothing with the park. Drawing each entry through [drawItemIcon] means the
 * button for a carousel shows *the* carousel — the same sprites, the same palette.
 */
internal object ParkIcons {

    /** Icon edge in device pixels. Big enough to hold a 4x4 coaster footprint legibly. */
    private const val SIZE = 72

    private val cache = HashMap<BuildItem, ImageBitmap>()

    /** Terrain samples read a tile hash; a 4x4 scratch map is plenty for that. */
    private val scratchMap = ParkMap(4, 4)

    fun icon(item: BuildItem, density: Density, measurer: TextMeasurer): ImageBitmap =
        cache.getOrPut(item) { rasterise(item, density, measurer) }

    private fun rasterise(
        item: BuildItem,
        density: Density,
        measurer: TextMeasurer
    ): ImageBitmap {
        val bitmap = ImageBitmap(SIZE, SIZE)
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(SIZE.toFloat(), SIZE.toFloat())
        ) {
            drawItemIcon(item, scratchMap, measurer)
        }
        return bitmap
    }
}
