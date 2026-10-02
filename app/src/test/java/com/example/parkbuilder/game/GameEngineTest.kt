package com.example.parkbuilder.game

import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameSpeed
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.TilePos
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private fun engine(seed: Int = 42) = GameEngine(Random(seed))

    private fun newPark(seed: Int = 42): GameState = ParkGenerator.newPark(seed)

    // ------------------------------------------------------------------
    // Starting park
    // ------------------------------------------------------------------

    @Test
    fun testStartingParkIsFurnished() {
        val state = newPark()

        assertTrue("expected a park full of rides", state.structures.count { it.item.isAttraction } >= 8)
        assertTrue("expected a path network", state.map.isWalkable(state.entrance.col, state.entrance.row))
        assertEquals(12000, state.money)
        assertTrue(state.visitors.isEmpty())
    }

    @Test
    fun testEveryAttractionCanBeReachedFromTheGate() {
        val state = newPark()
        val walker = engine()

        state.structures.filter { it.item.isAttraction }.forEach { structure ->
            val touching = structure.perimeter().filter { state.map.isWalkable(it.col, it.row) }
            assertTrue(
                "${structure.item.displayName} at (${structure.col},${structure.row}) has no path beside it",
                touching.isNotEmpty()
            )
            assertTrue(
                "${structure.item.displayName} is walled off from the entrance",
                touching.any { walker.isReachableFromGate(state, it) }
            )
        }
    }

    @Test
    fun testFindPathWalksThePathNetwork() {
        val state = newPark()
        val walker = engine()
        val ride = state.structures.first { it.item == BuildItem.FERRIS_WHEEL }

        val path = walker.findPath(state.map, state.entrance, ride.perimeter())

        assertNotNull(path)
        assertTrue("expected a multi-tile route", (path?.size ?: 0) > 10)
        // Every node of the route must be a path tile, so guests never cut across grass.
        path!!.forEach { assertTrue(state.map.isWalkable(it.col, it.row)) }
    }

    @Test
    fun testFindPathFailsWhenTheRideIsWalledOff() {
        val state = newPark()
        val walker = engine()
        val ride = state.structures.first { it.item == BuildItem.CAROUSEL }

        var map = state.map
        ride.perimeter().forEach { map = map.withTerrain(it.col, it.row, Terrain.WATER) }
        val isolated = state.copy(map = map)

        assertNull(walker.findPath(isolated.map, isolated.entrance, ride.perimeter()))
    }

    // ------------------------------------------------------------------
    // Placement rules
    // ------------------------------------------------------------------

    @Test
    fun testPlacementRejectsOccupiedTiles() {
        val state = newPark()
        val existing = state.structures.first { it.item == BuildItem.CAROUSEL }

        assertEquals(
            Placement.OCCUPIED,
            placementFor(state, BuildItem.ROLLER_COASTER, existing.col, existing.row)
        )
    }

    @Test
    fun testPlacementRejectsWater() {
        val state = newPark()
        var map = state.map
        for (col in 0 until state.map.cols) {
            for (row in 0 until state.map.rows) {
                if (map.terrainAt(col, row) == Terrain.WATER) {
                    assertEquals(
                        Placement.BLOCKED_BY_WATER,
                        placementFor(state.copy(map = map), BuildItem.BURGER_BAR, col, row)
                    )
                    return
                }
            }
        }
        throw AssertionError("expected at least one water tile in the starting park")
    }

    @Test
    fun testPlacementRejectsBeingShortOfCash() {
        val rich = newPark()
        val spot = freeSpot(rich, BuildItem.DODGEMS)
        val broke = rich.copy(money = 10)

        assertEquals(Placement.OK, placementFor(rich, BuildItem.DODGEMS, spot.col, spot.row))
        assertEquals(
            Placement.NOT_ENOUGH_MONEY,
            placementFor(broke, BuildItem.DODGEMS, spot.col, spot.row)
        )
    }

    @Test
    fun testPlacingARideDeductsCostAndClearsTheTool() {
        val base = newPark()
        val spot = freeSpot(base, BuildItem.DODGEMS)
        val state = base.copy(selectedItem = BuildItem.DODGEMS)
        val before = state.structures.size

        val after = engine().applyTool(state, BuildItem.DODGEMS, spot.col, spot.row)

        assertEquals(state.money - BuildItem.DODGEMS.cost, after.money)
        assertEquals(before + 1, after.structures.size)
        assertNull(after.selectedItem)
    }

    @Test
    fun testPaintingTerrainChargesPerTile() {
        val state = newPark()
        val grass = firstTileOfType(state, Terrain.GRASS)

        val after = engine().applyTool(state, BuildItem.PATH, grass.col, grass.row)

        assertEquals(Terrain.PATH, after.map.terrainAt(grass.col, grass.row))
        assertEquals(state.money - BuildItem.PATH.cost, after.money)
    }

    @Test
    fun testPaintingTheSameTerrainTwiceChargesNothing() {
        val state = newPark()
        val grass = firstTileOfType(state, Terrain.GRASS)
        val once = engine().applyTool(state, BuildItem.GRASS, grass.col, grass.row)

        assertEquals(state.money, once.money)
    }

    @Test
    fun testBulldozingABuildingRefundsHalf() {
        val state = newPark()
        val ride = state.structures.first { it.item == BuildItem.ROLLER_COASTER }

        val after = engine().applyTool(state, BuildItem.BULLDOZE, ride.col, ride.row)

        assertNull(after.structures.firstOrNull { it.id == ride.id })
        assertEquals(state.money + ride.item.cost / 2, after.money)
    }

    // ------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------

    @Test
    fun testPausedParkDoesNotAdvance() {
        val state = newPark().copy(speed = GameSpeed.PAUSED)

        val after = engine().update(state, 1f)

        assertEquals(state.money, after.money)
        assertEquals(state.gameTime, after.gameTime, 0f)
        assertEquals(state.day, after.day)
    }

    @Test
    fun testGuestsArriveAndSpendMoneyOnRides() {
        val walker = engine()
        var state = newPark()

        repeat(6_000) { state = walker.update(state, 0.05f) }

        assertTrue("expected guests to have entered the park", state.totalVisitors > 0)
        assertTrue("expected a crowd in the park", state.stats.visitors > 0)
        assertTrue(
            "takings should have grown the bank balance above the opening 12000",
            state.money > 12000
        )
        assertTrue(
            "rides should have recorded riders",
            state.structures.any { it.lifetimeVisitors > 0 }
        )
    }

    @Test
    fun testGuestsNeverStandOffThePathNetwork() {
        val walker = engine()
        var state = newPark()

        repeat(2_000) {
            state = walker.update(state, 0.05f)
            state.visitors.forEach { visitor ->
                val col = visitor.col.toInt()
                val row = visitor.row.toInt()
                assertTrue(
                    "guest at ($col,$row) left the paths",
                    state.map.isWalkable(col, row) || visitor.state.name == "LEAVING"
                )
            }
        }
    }

    @Test
    fun testUpkeepIsChargedAtTheEndOfTheDay() {
        val walker = engine()
        // Seal the park off so nobody can get in: with no takings, a day's books are
        // exactly opening balance minus upkeep.
        var sealed = newPark()
        var map = sealed.map
        for (col in 12..26) {
            for (row in 34..39) map = map.withTerrain(col, row, Terrain.GRASS)
        }
        sealed = sealed.copy(map = map)
        val upkeep = sealed.totalUpkeep
        assertTrue(upkeep > 0)

        // A few extra frames of slack: 1200 steps of 0.05f lands on 0.99999 of a day once
        // float rounding is accounted for.
        var after = sealed
        repeat((GameEngine.DAY_SECONDS / 0.05f).toInt() + 10) { after = walker.update(after, 0.05f) }

        assertEquals("guests should not be able to enter a sealed park", 0, after.totalVisitors)
        assertEquals(2, after.day)
        assertEquals(sealed.money - upkeep, after.money)
    }

    @Test
    fun testRatingRisesWithMoreRides() {
        val walker = engine()
        val bare = newPark().let { it.copy(structures = it.structures.filterNot { s -> s.item.isAttraction }) }

        var furnished = bare
        repeat(5) { furnished = walker.applyTool(furnished, BuildItem.FERRIS_WHEEL, 2 + it, 2) }

        repeat(60) { furnished = walker.update(furnished, 0.05f) }

        assertTrue(bare.stats.rating == 0 || furnished.stats.rating >= bare.stats.rating)
    }

    @Test
    fun testSelectingAToolTogglesItOff() {
        val walker = engine()
        val state = newPark()

        val armed = walker.selectItem(state, BuildItem.TREE)
        assertEquals(BuildItem.TREE, armed.selectedItem)

        val disarmed = walker.selectItem(armed, BuildItem.TREE)
        assertNull(disarmed.selectedItem)
    }

    @Test
    fun testSkintGuestsGoHome() {
        val walker = engine()
        var state = newPark()
        repeat(2_000) { state = walker.update(state, 0.05f) }

        val starving = state.visitors.map { it.id }.toSet()
        assertTrue("expected guests in the park", starving.isNotEmpty())
        state = state.copy(visitors = state.visitors.map { it.copy(wallet = 1) })

        // Long enough for a guest to ride out their ride and walk back to the gate.
        repeat(1_500) { state = walker.update(state, 0.05f) }

        assertFalse(
            "broke guests should have left, but ${state.visitors.count { it.id in starving }} stayed",
            state.visitors.any { it.id in starving }
        )
    }

    /** First tile where [item] could legally be built today. */
    private fun freeSpot(state: GameState, item: BuildItem): TilePos {
        for (col in 0 until state.map.cols) {
            for (row in 0 until state.map.rows) {
                if (placementFor(state, item, col, row) == Placement.OK) return TilePos(col, row)
            }
        }
        throw AssertionError("no legal spot for ${item.displayName}")
    }

    private fun firstTileOfType(state: GameState, terrain: Terrain): TilePos {
        for (col in 0 until state.map.cols) {
            for (row in 0 until state.map.rows) {
                if (state.map.terrainAt(col, row) == terrain) {
                    // Make sure nothing is standing on it.
                    if (state.structureAt(col, row) == null) return TilePos(col, row)
                }
            }
        }
        throw AssertionError("no free $terrain tile found")
    }
}
