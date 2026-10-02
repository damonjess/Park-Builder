package com.example.parkbuilder.game

import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.ParkMap
import com.example.parkbuilder.game.model.Structure
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.TilePos
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds the park the player starts with.
 *
 * The reference screenshot is *packed*: rides lining a path network, lakes, decorations
 * everywhere. An empty green field is a bad first impression and explains why a fresh
 * build "looks nothing like it" — so we generate the same shape of park: a wide entrance
 * plaza, two avenues with connector paths, five rides already running, lakes and greenery.
 *
 * Everything here is placed on a fixed grid so the starting park is identical every run
 * (and so the reachability test in `GameEngineTest` can assert it stays walkable).
 */
object ParkGenerator {

    const val COLS = 40
    const val ROWS = 40
    const val ENTRANCE_COL = 19
    const val ENTRANCE_ROW = ROWS - 1

    fun newPark(seed: Int = 7): GameState {
        val random = Random(seed)
        var map = ParkMap(COLS, ROWS)

        // ---- Water first, so paths always win the carve ----------------------
        map = map.paintLake(4, 16, 2.2f, random)
        map = map.paintLake(35, 16, 2.2f, random)
        map = map.paintLake(5, 29, 2.0f, random)
        map = map.paintLake(35, 28, 2.0f, random)

        // ---- Path network ---------------------------------------------------
        // Avenues are two tiles wide so the park reads as a place, not a maze.
        map = map.paintRect(18, 8, 21, 38, Terrain.PATH)   // main avenue through the middle
        map = map.paintRect(5, 9, 34, 10, Terrain.PATH)    // north avenue
        map = map.paintRect(5, 23, 34, 24, Terrain.PATH)   // south avenue
        map = map.paintRect(8, 9, 9, 24, Terrain.PATH)     // west link
        map = map.paintRect(30, 9, 31, 24, Terrain.PATH)   // east link
        map = map.paintRect(11, 23, 12, 37, Terrain.PATH)  // west approach
        map = map.paintRect(27, 23, 28, 37, Terrain.PATH)  // east approach
        map = map.paintRect(15, 33, 24, 39, Terrain.PATH)  // entrance plaza

        // ---- Queue lines ----------------------------------------------------
        // Two rides open with a queue drawn for them, so the mechanic is visible from the
        // first second: guests walk to the back of the line and shuffle forwards.
        map = map.paintRect(10, 11, 12, 11, Terrain.QUEUE)  // ferris wheel, off the north avenue
        map = map.paintRect(10, 20, 10, 22, Terrain.QUEUE)  // dodgems, off the west link

        // Every ride below sits in a free block and butts onto one of those paths.
        val layout = listOf(
            BuildItem.CAROUSEL to TilePos(14, 11),
            BuildItem.FOUNTAIN to TilePos(16, 15),
            BuildItem.FERRIS_WHEEL to TilePos(10, 12),
            BuildItem.DODGEMS to TilePos(11, 20),
            BuildItem.LOG_FLUME to TilePos(22, 11),
            BuildItem.ROLLER_COASTER to TilePos(22, 18),
            BuildItem.BURGER_BAR to TilePos(22, 25),
            BuildItem.SODA_STAND to TilePos(22, 28),
            BuildItem.ICE_CREAM to TilePos(22, 30),
            BuildItem.GIFT_SHOP to TilePos(26, 30),
            BuildItem.RESTROOM to TilePos(29, 26),
            BuildItem.BENCH to TilePos(23, 34),
            BuildItem.LAMP to TilePos(16, 32),
            BuildItem.LAMP to TilePos(23, 32)
        )

        val structures = ArrayList<Structure>(layout.size + 300)
        layout.forEachIndexed { index, (item, at) ->
            if (!fits(map, structures, at.col, at.row, item)) return@forEachIndexed
            structures += Structure(
                id = "seed-$index",
                item = item,
                col = at.col,
                row = at.row,
                animAngle = random.nextFloat() * 360f
            )
        }

        // ---- High street ----------------------------------------------------
        // The reference is packed: rows of shops and small rides lining every path, with
        // barely a bare patch of grass in sight. So once the headline rides are down, fill
        // the frontage: anything that can sit beside a path and still have elbow room.
        val shopRotation = listOf(
            BuildItem.BURGER_BAR,
            BuildItem.SODA_STAND,
            BuildItem.CAROUSEL,
            BuildItem.ICE_CREAM,
            BuildItem.GIFT_SHOP,
            BuildItem.DODGEMS
        )
        val spots = ArrayList<TilePos>(COLS * ROWS)
        for (col in 1 until COLS - 1) {
            for (row in 1 until ROWS - 1) spots += TilePos(col, row)
        }
        spots.shuffle(random)

        var built = 0
        spots.forEach { spot ->
            if (built >= 16) return@forEach
            val item = shopRotation[built % shopRotation.size]
            if (!fits(map, structures, spot.col, spot.row, item)) return@forEach
            if (!hasFrontage(map, structures, spot.col, spot.row, item)) return@forEach
            structures += Structure(
                id = "street-${spot.col}-${spot.row}",
                item = item,
                col = spot.col,
                row = spot.row,
                animAngle = random.nextFloat() * 360f
            )
            built++
        }

        // ---- Greenery -------------------------------------------------------
        // Benches and lamps hug the paths; trees and flowers fill the open ground.
        for (col in 0 until COLS) {
            for (row in 0 until ROWS) {
                if (map.terrainAt(col, row) != Terrain.GRASS) continue
                if (structures.any { it.covers(col, row) }) continue

                // Dense, but not a forest: enough open lawn that the grass texture still
                // reads, with planting along every path the way the reference does it.
                val riverside = touchesPath(map, col, row, 1)
                val item = if (riverside) {
                    when {
                        random.nextDouble() < 0.28 -> BuildItem.BENCH
                        random.nextDouble() < 0.22 -> BuildItem.LAMP
                        random.nextDouble() < 0.40 -> BuildItem.FLOWERS
                        random.nextDouble() < 0.26 -> BuildItem.TREE
                        else -> null
                    }
                } else {
                    when {
                        random.nextDouble() < 0.36 -> BuildItem.TREE
                        random.nextDouble() < 0.22 -> BuildItem.FLOWERS
                        else -> null
                    }
                }
                if (item == null) continue
                if (!fits(map, structures, col, row, item)) continue
                structures += Structure(
                    id = "deco-$col-$row",
                    item = item,
                    col = col,
                    row = row,
                    animAngle = random.nextFloat() * 360f
                )
            }
        }

        return GameState(
            parkName = "Wonderland",
            money = 12000,
            map = map,
            structures = structures,
            entrance = TilePos(ENTRANCE_COL, ENTRANCE_ROW),
            visitors = emptyList()
        )
    }

    /** True when [item] fits at [col],[row] on dry, unoccupied land. */
    private fun fits(
        map: ParkMap,
        structures: List<Structure>,
        col: Int,
        row: Int,
        item: BuildItem
    ): Boolean {
        for (c in col until col + item.wTiles) {
            for (r in row until row + item.hTiles) {
                if (!map.inBounds(c, r)) return false
                if (map.terrainAt(c, r) == Terrain.WATER) return false
                if (structures.any { it.covers(c, r) }) return false
            }
        }
        return true
    }

    /**
     * True when [item] would stand on a path frontage with a ring of clearance around it —
     * what gives the park its lines of buildings rather than one solid block of rides.
     */
    private fun hasFrontage(
        map: ParkMap,
        structures: List<Structure>,
        col: Int,
        row: Int,
        item: BuildItem
    ): Boolean {
        var touchesPath = false
        for (c in col - 1..col + item.wTiles) {
            for (r in row - 1..row + item.hTiles) {
                val onEdge = c == col - 1 || c == col + item.wTiles ||
                    r == row - 1 || r == row + item.hTiles
                if (!onEdge) continue
                if (!map.inBounds(c, r)) continue
                if (map.isWalkable(c, r)) touchesPath = true
                if (structures.any { it.covers(c, r) }) return false
            }
        }
        return touchesPath
    }

    private fun touchesPath(map: ParkMap, col: Int, row: Int, radius: Int): Boolean {
        for (c in col - radius..col + radius) {
            for (r in row - radius..row + radius) {
                if (map.isWalkable(c, r)) return true
            }
        }
        return false
    }

    private fun ParkMap.paintRect(
        colFrom: Int,
        rowFrom: Int,
        colTo: Int,
        rowTo: Int,
        terrain: Terrain
    ): ParkMap {
        var result = this
        for (c in colFrom..colTo) {
            for (r in rowFrom..rowTo) {
                result = result.withTerrain(c, r, terrain)
            }
        }
        return result
    }

    /**
     * Irregular lake. The radius wobbles with a smooth function of the angle (rather than
     * per-tile randomness) so the shoreline comes out lumpy but continuous.
     */
    private fun ParkMap.paintLake(
        centerCol: Int,
        centerRow: Int,
        radius: Float,
        random: Random
    ): ParkMap {
        var result = this
        val phase = random.nextFloat() * 6.283f
        val reach = radius * 1.5f
        for (c in kotlin.math.floor(centerCol - reach).toInt()..kotlin.math.ceil(centerCol + reach).toInt()) {
            for (r in kotlin.math.floor(centerRow - reach).toInt()..kotlin.math.ceil(centerRow + reach).toInt()) {
                val dx = c - centerCol
                val dy = r - centerRow
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val theta = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                val edge = radius * (1f + 0.28f * sin(theta * 3f + phase))
                if (distance <= edge) {
                    result = result.withTerrain(c, r, Terrain.WATER)
                }
            }
        }
        return result
    }
}
