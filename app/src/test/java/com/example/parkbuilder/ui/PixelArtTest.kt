package com.example.parkbuilder.ui

import androidx.compose.ui.graphics.Color
import com.example.parkbuilder.game.model.BuildItem
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The park's look is generated code, so it is worth asserting things about it.
 *
 * "Is this texture flat?" is exactly the bug that made the game look like solid colours,
 * so most of these tests count colours and edges. The last one composites a patch of park
 * the same way the renderer does and writes `build/reports/art-preview.png`, which is the
 * quickest way to see the art without a device.
 */
class PixelArtTest {

    private val backgrounds = setOf(Palette.GrassA, Palette.PathA, Palette.WaterMid)

    private fun PixelImage.opaque(x: Int, y: Int): Boolean = (get(x, y) ushr 24) != 0

    private fun PixelImage.colours(): Set<Int> {
        val seen = HashSet<Int>()
        pixels.forEach { if (it != 0) seen += it }
        return seen
    }

    @Test
    fun testTerrainTilesAreDiamondShapedAndTextured() {
        val tiles = listOf(
            "grass" to PixelArt.grassTile(0),
            "path" to PixelArt.pathTile(0, PixelArt.EDGE_NW or PixelArt.EDGE_SE),
            "water" to PixelArt.waterTile(0, PixelArt.EDGE_NE),
            "queue" to PixelArt.queueTile(0, true)
        )

        tiles.forEach { (name, image) ->
            assertEquals("$name tile width", PixelArt.TILE_W, image.w)
            assertEquals("$name tile height", PixelArt.TILE_H, image.h)

            // Corners are outside the diamond, the centre is inside it.
            assertTrue("$name should be transparent at its top corner", !image.opaque(0, 0))
            assertTrue("$name should be transparent at its left corner", !image.opaque(0, PixelArt.TILE_H - 1))
            assertTrue("$name should be opaque in the middle", image.opaque(PixelArt.TILE_W / 2, PixelArt.TILE_H / 2))

            // The whole point of the exercise: a texture, not a fill.
            assertTrue(
                "$name looks flat — only ${image.colours().size} colours in it",
                image.colours().size >= 5
            )
        }
    }

    @Test
    fun testGrassWeaveIsNotOneFlatGreen() {
        val image = PixelArt.grassTile(3)
        val mid = PixelArt.TILE_H / 2
        // Two neighbouring 2x2 weave blocks must differ, or the weave has been lost.
        val a = image.get(PixelArt.TILE_W / 2, mid)
        val b = image.get(PixelArt.TILE_W / 2 + 2, mid)
        assertTrue("expected a woven grass pattern", a != b)
    }

    @Test
    fun testPathOnlyWearsAKerbWhereItMeetsSomethingElse() {
        val allEdges = PixelArt.EDGE_NW or PixelArt.EDGE_NE or PixelArt.EDGE_SE or PixelArt.EDGE_SW
        val walled = PixelArt.pathTile(0, allEdges)
        val open = PixelArt.pathTile(0, 0)

        var differing = 0
        walled.pixels.indices.forEach { if (walled.pixels[it] != open.pixels[it]) differing++ }
        assertTrue("a kerb should visibly change the tile, $differing pixels differ", differing > 60)

        // The kerb sits inside the diamond: the centre of the tile must be untouched.
        val centre = (PixelArt.TILE_H / 2) * PixelArt.TILE_W + PixelArt.TILE_W / 2
        assertEquals(
            "the middle of a path tile should not be kerb",
            open.pixels[centre],
            walled.pixels[centre]
        )
    }

    @Test
    fun testWaterGetsAShorelineWhereItMeetsLand() {
        val shore = PixelArt.waterTile(0, PixelArt.EDGE_NW)
        val midLake = PixelArt.waterTile(0, 0)

        var differing = 0
        shore.pixels.indices.forEach { if (shore.pixels[it] != midLake.pixels[it]) differing++ }
        assertTrue("expected a shoreline along the masked edge", differing > 40)
    }

    @Test
    fun testQueueRailsFollowTheDirectionOfTheLine() {
        val alongCol = PixelArt.queueTile(0, true)
        val alongRow = PixelArt.queueTile(1, false)

        var differing = 0
        alongCol.pixels.indices.forEach { if (alongCol.pixels[it] != alongRow.pixels[it]) differing++ }
        assertTrue("the two queue orientations should differ", differing > 40)

        // The NE edge midpoint carries a rail when the line runs up the columns.
        val midX = (PixelArt.TILE_W * 3) / 4
        val midY = PixelArt.TILE_H / 4
        assertTrue(
            "expected a hand rail near the middle of the NE edge",
            (0..4).any { d -> alongCol.opaque(midX + d, midY + d) }
        )
    }

    @Test
    fun testMaterialsAreTexturedNotFlat() {
        val materials = mapOf(
            "bricks" to PixelArt.bricks(Palette.BrickRed, Palette.Mortar, 3),
            "siding" to PixelArt.siding(Palette.WallCream, 3),
            "planks" to PixelArt.planks(Palette.Wood, 3),
            "shingles" to PixelArt.shingles(Palette.RoofRed, 3),
            "plates" to PixelArt.plates(Palette.Metal, 3),
            "checker" to PixelArt.checker(Palette.RoofTeal, Palette.RoofCream, 3),
            "stripes" to PixelArt.stripes(Palette.RoofRed, Palette.RoofCream),
            "asphalt" to PixelArt.asphalt(3)
        )
        materials.forEach { (name, image) ->
            val histogram = HashMap<Int, Int>()
            image.pixels.forEach { if (it != 0) histogram[it] = (histogram[it] ?: 0) + 1 }
            val total = histogram.values.sum()
            val dominant = histogram.values.maxOrNull() ?: 0
            assertTrue("$name is a flat colour", histogram.size >= 3)
            assertTrue(
                "$name is one solid tone ($dominant of $total pixels) — the walls and roofs would look like plastic",
                dominant * 100 / total < 80
            )
        }
    }

    @Test
    fun testVehiclesAndPropsHaveRealDetail() {
        val bus = PixelArt.bus(Palette.RoofRed, Palette.RoofCream, 7)
        assertTrue("a bus should have more than a couple of colours", bus.colours().size >= 6)
        // Bodywork along the top, tyres down at the bottom.
        assertTrue("expected a window band", bus.opaque(bus.w / 2, 14))
        assertTrue("expected a wheel", bus.opaque(15, bus.h - 5))

        BuildItem.entries.filter { it.isAttraction }.forEach { item ->
            val prop = PixelArt.roofProp(item)
            assertTrue("the $item roof prop is blank", prop.colours().size >= 2)
        }
    }

    @Test
    fun testPortraitIsDrawn() {
        val portrait = PixelArt.portrait()
        assertTrue("expected a face", portrait.colours().size >= 6)
        assertTrue("expected a head in the top half", portrait.opaque(15, 12))
    }

    /**
     * Composites a patch of park the way the renderer does and checks the tiles really do
     * tile: the diamond mask is drawn slightly oversized for exactly this reason, and a
     * hairline of background between two tiles is the classic way to break the illusion.
     */
    @Test
    fun testNeighbouringTilesLeaveNoHairlineGaps() {
        val cols = 6
        val rows = 6
        val width = (cols + rows) * PixelArt.TILE_W / 2 + 64
        val height = (cols + rows) * 16 + 64
        val canvas = IntArray(width * height)
        // Camera-ish offset so every tile lands well inside the canvas.
        val originX = width / 2 + 16
        val originY = 40

        for (col in 0 until cols) {
            for (row in 0 until rows) {
                val texture = when {
                    col == 3 -> PixelArt.pathTile(row % 4, 0)
                    col == 4 && row == 1 -> PixelArt.waterTile(0, 0)
                    row == 4 && col <= 2 -> PixelArt.queueTile(0, true)
                    else -> PixelArt.grassTile((col + row) % PixelArt.GRASS_VARIANTS)
                }
                val left = originX + (col - row) * PixelArt.TILE_W / 2 - PixelArt.TILE_W / 2
                val top = originY + (col + row) * 16 - 16
                for (y in 0 until PixelArt.TILE_H) {
                    for (x in 0 until PixelArt.TILE_W) {
                        val pixel = texture.get(x, y)
                        if (pixel == 0) continue
                        canvas[(top + y) * width + (left + x)] = pixel
                    }
                }
            }
        }

        // Probe the interior of the patch: a gap would show up as an untouched pixel.
        var holes = 0
        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val x = originX + (c - r) * PixelArt.TILE_W / 2
                val y = originY + (c + r) * 16
                if (canvas[y * width + x] == 0) holes++
            }
        }
        assertEquals("tiles should overlap and leave no gaps", 0, holes)

        writePreview(canvas, width, height)
    }

    /**
     * Writes the composite, the material swatches and the sprites into one contact sheet
     * so the art can be reviewed in an image viewer.
     */
    private fun writePreview(canvas: IntArray, width: Int, height: Int) {
        val sprites = listOf(
            PixelArt.bus(Palette.RoofRed, Palette.RoofCream, 7),
            PixelArt.car(Palette.RoofBlue, 1),
            PixelArt.roofProp(BuildItem.BURGER_BAR),
            PixelArt.roofProp(BuildItem.SODA_STAND),
            PixelArt.roofProp(BuildItem.ICE_CREAM),
            PixelArt.roofProp(BuildItem.GIFT_SHOP),
            PixelArt.flag(Palette.RoofRed),
            PixelArt.portrait()
        )
        val sheetHeight = height + 48
        val sheet = BufferedImage(width, sheetHeight, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = canvas[y * width + x]
                val rgb = if (pixel == 0) 0xFF1E3A18.toInt() else pixel
                sheet.setRGB(x, y, rgb)
            }
        }

        var cursor = 8
        sprites.forEach { sprite ->
            for (y in 0 until sprite.h) {
                for (x in 0 until sprite.w) {
                    val pixel = sprite.get(x, y)
                    if (pixel == 0) continue
                    val px = cursor + x
                    val py = height + 8 + y
                    if (px < width && py < sheetHeight) sheet.setRGB(px, py, pixel)
                }
            }
            cursor += sprite.w + 8
        }

        val report = File("build/reports/art-preview.png")
        report.parentFile?.mkdirs()
        ImageIO.write(sheet, "png", report)
        assertTrue("expected the preview sheet to be written", report.exists())
    }

    /** Sanity check that the palette helpers are not silently producing the same colour. */
    @Test
    fun testPackedColoursRoundTrip() {
        assertEquals(0xFF62B446.toInt(), Palette.GrassA.packed())
        assertEquals(0x80FF0000.toInt(), Color(0xFFFF0000).packed(0.5f))
    }
}
