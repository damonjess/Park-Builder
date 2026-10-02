package com.example.parkbuilder.game

import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.Iso
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.TilePos
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkLayoutTest {

    // ------------------------------------------------------------------
    // Isometric projection
    // ------------------------------------------------------------------

    @Test
    fun testScreenToTileInvertsTileToScreen() {
        val tiles = listOf(TilePos(0, 0), TilePos(1, 0), TilePos(0, 1), TilePos(19, 23), TilePos(39, 39))
        tiles.forEach { tile ->
            // Sample well inside the diamond so we are not testing edge rounding.
            val centre = Iso.toScreen(tile.col + 0.5f, tile.row + 0.5f)
            val back = Iso.toTile(centre.x, centre.y)
            assertEquals("col round trip for $tile", tile.col, back.col)
            assertEquals("row round trip for $tile", tile.row, back.row)
        }
    }

    @Test
    fun testIncreasingColGoesDownRightAndRowGoesDownLeft() {
        val origin = Iso.toScreen(10f, 10f)
        val oneColOver = Iso.toScreen(11f, 10f)
        val oneRowOver = Iso.toScreen(10f, 11f)

        assertEquals(32f, oneColOver.x - origin.x, 0.001f)
        assertEquals(16f, oneColOver.y - origin.y, 0.001f)
        assertEquals(-32f, oneRowOver.x - origin.x, 0.001f)
        assertEquals(16f, oneRowOver.y - origin.y, 0.001f)
    }

    @Test
    fun testDeeperTilesSitLowerOnScreen() {
        // Painter's-algorithm depth must match on-screen y, or occlusion breaks.
        val a = Iso.toScreen(3f, 3f)
        val b = Iso.toScreen(4f, 3f)
        val c = Iso.toScreen(3f, 4f)
        assertTrue(b.y > a.y)
        assertTrue(c.y > a.y)
    }

    @Test
    fun testNeighbouringTilesAreExactlyOneDiamondApart() {
        // Tiles must tile the plane with no gaps: the distance between neighbouring
        // centres along a screen axis is half the diamond's width and height.
        val centre = Iso.toScreen(5.5f, 5.5f)
        val neighbour = Iso.toScreen(6.5f, 5.5f)
        assertEquals(32f, abs(neighbour.x - centre.x), 0.001f)
        assertEquals(16f, abs(neighbour.y - centre.y), 0.001f)
    }

    // ------------------------------------------------------------------
    // Layout audit
    // ------------------------------------------------------------------

    /**
     * Prints the starting park top-down so a human can sanity-check the layout.
     * `#` path, `.` grass, `~` water, `G` gate, letters for buildings.
     */
    @Test
    fun testPrintStartingParkLayout() {
        val state = ParkGenerator.newPark()
        val legend = StringBuilder()
        val seen = LinkedHashMap<Char, String>()
        val text = StringBuilder()

        for (row in 0 until state.map.rows) {
            for (col in 0 until state.map.cols) {
                val structure = state.structureAt(col, row)
                val ch = when {
                    state.entrance.col == col && state.entrance.row == row -> 'G'
                    structure != null -> {
                        val glyph = glyphFor(structure.item)
                        // Only label a building once, on its anchor tile.
                        if (structure.col == col && structure.row == row) {
                            seen[glyph] = structure.item.displayName
                            glyph
                        } else {
                            '▪'
                        }
                    }

                    else -> when (state.map.terrainAt(col, row)) {
                        Terrain.PATH -> '#'
                        Terrain.WATER -> '~'
                        Terrain.GRASS -> '.'
                    }
                }
                text.append(ch)
            }
            text.append('\n')
        }

        seen.forEach { (glyph, name) -> legend.append("  $glyph = $name\n") }
        println("\n--- starting park ---\n$text$legend")

        // Also drop it next to the build output so it can be reviewed without trawling
        // gradle's log.
        val report = java.io.File("build/reports/starting-park.txt")
        report.parentFile?.mkdirs()
        report.writeText("--- starting park ---\n$text$legend")

        assertTrue(text.isNotEmpty())
    }

    @Test
    fun testParkHasWaterPathsAndBuildings() {
        val state = ParkGenerator.newPark()
        var water = 0
        var path = 0
        for (col in 0 until state.map.cols) {
            for (row in 0 until state.map.rows) {
                when (state.map.terrainAt(col, row)) {
                    Terrain.WATER -> water++
                    Terrain.PATH -> path++
                    Terrain.GRASS -> Unit
                }
            }
        }
        assertTrue("expected lakes", water > 20)
        assertTrue("expected a path network", path > 200)
        assertTrue("expected rides", state.structures.count { it.item.category.name == "RIDE" } >= 5)
        // The park should be dressed, not bare.
        assertTrue("expected scattered scenery", state.structures.size > 60)
    }

    private fun glyphFor(item: BuildItem): Char = when (item) {
        BuildItem.PATH, BuildItem.WATER, BuildItem.GRASS, BuildItem.BULLDOZE -> '?'
        BuildItem.CAROUSEL -> 'C'
        BuildItem.FERRIS_WHEEL -> 'F'
        BuildItem.DODGEMS -> 'D'
        BuildItem.LOG_FLUME -> 'L'
        BuildItem.ROLLER_COASTER -> 'R'
        BuildItem.BURGER_BAR -> 'b'
        BuildItem.SODA_STAND -> 's'
        BuildItem.ICE_CREAM -> 'i'
        BuildItem.GIFT_SHOP -> 'g'
        BuildItem.RESTROOM -> 'W'
        BuildItem.FOUNTAIN -> 'O'
        BuildItem.TREE -> 'T'
        BuildItem.BENCH -> 'n'
        BuildItem.LAMP -> 'l'
        BuildItem.FLOWERS -> 'f'
    }
}
