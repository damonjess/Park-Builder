package com.example.parkbuilder.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.ParkMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Toolbar icons, rasterised once from the park's own art.
 *
 * The emoji glyphs that used to stand in for the catalogue were the weakest thing on
 * screen: they render differently on every device, they are never the right colour, and
 * they share nothing with the park. Drawing each entry through [drawItemIcon] means the
 * button for a carousel shows *the* carousel — the same sprites, the same palette.
 *
 * The item is drawn once into a generous offscreen canvas, then cropped to its actual
 * non-transparent bounds and fitted to the button. That is what keeps a 4x4 coaster and a
 * one-tile lamp at comparable weight in the tray, and it means making a ride taller can
 * never clip its icon.
 */
internal object ParkIcons {

    /** Offscreen canvas the item is drawn into before being cropped to fit. */
    private const val WORK = 384

    /** Edge of the finished icon, in device pixels — matches the 34.dp button image. */
    private const val BUTTON_DP = 34f

    private val cache = HashMap<Pair<BuildItem, Int>, ImageBitmap>()

    /** Terrain samples read a tile hash; a 4x2 scratch map is plenty for that. */
    private val scratchMap = ParkMap(4, 4)

    fun icon(item: BuildItem, density: Density, measurer: TextMeasurer): ImageBitmap {
        val edge = (BUTTON_DP * density.density).roundToInt().coerceIn(56, 160)
        return cache.getOrPut(item to edge) { rasterise(item, density, measurer, edge) }
    }

    private fun rasterise(
        item: BuildItem,
        density: Density,
        measurer: TextMeasurer,
        edge: Int
    ): ImageBitmap {
        val work = ImageBitmap(WORK, WORK)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(work),
            size = Size(WORK.toFloat(), WORK.toFloat())
        ) {
            drawItemIcon(item, scratchMap, measurer)
        }

        val source = work.asAndroidBitmap()
        val pixels = IntArray(WORK * WORK)
        source.getPixels(pixels, 0, WORK, 0, 0, WORK, WORK)

        var minX = WORK
        var minY = WORK
        var maxX = -1
        var maxY = -1
        for (y in 0 until WORK) {
            val row = y * WORK
            for (x in 0 until WORK) {
                if (pixels[row + x] ushr 24 == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return ImageBitmap(edge, edge)

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        val room = (edge - 4).coerceAtLeast(1).toFloat()
        // Fit, but never blow a small sprite up so far that it turns to mush.
        val fit = min(room / boxW, room / boxH).coerceAtMost(2f)
        val outW = max(1, (boxW * fit).roundToInt())
        val outH = max(1, (boxH * fit).roundToInt())

        val cropped = Bitmap.createBitmap(source, minX, minY, boxW, boxH)
        val fitted = if (outW == boxW && outH == boxH) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, outW, outH, true)
        }

        val result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(result).drawBitmap(
            fitted, (edge - outW) / 2f, (edge - outH) / 2f, null
        )
        if (cropped !== fitted) cropped.recycle()
        return result.asImageBitmap()
    }
}
