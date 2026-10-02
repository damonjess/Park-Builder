package com.example.parkbuilder.game

import com.example.parkbuilder.game.model.AttractionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class GameEngineTest {

    private val engine = GameEngine()

    @Test
    fun testInitialStateHasAttractionsAndVisitors() {
        val state = engine.createInitialState(1000f, 1800f)

        assertEquals("Wonder Park", state.parkName)
        assertEquals(2, state.attractions.size)
        assertEquals(8, state.visitors.size)
        assertEquals(1200, state.money)
    }

    @Test
    fun testUpdateRotatesAttractionsAndMovesVisitors() {
        val initialState = engine.createInitialState(1000f, 1800f)
        val initialAttractionAngle = initialState.attractions[0].rotationAngle
        val initialVisitorX = initialState.visitors[0].x

        val updatedState = engine.update(initialState, deltaTime = 0.016f)

        // Rotation should advance
        assertTrue(updatedState.attractions[0].rotationAngle > initialAttractionAngle)

        // Visitor should move towards target
        assertTrue(updatedState.visitors[0].x != initialVisitorX)
    }

    @Test
    fun testBuildAttractionDeductsMoney() {
        val initialState = engine.createInitialState(1000f, 1800f)
        val cost = AttractionType.ROLLER_COASTER.cost

        val updatedState = engine.buildAttraction(initialState, AttractionType.ROLLER_COASTER)

        assertEquals(initialState.money - cost, updatedState.money)
        assertEquals(initialState.attractions.size + 1, updatedState.attractions.size)
    }

    @Test
    fun testHandleTapSelectsExistingAttraction() {
        val initialState = engine.createInitialState(1000f, 1800f)
        val targetRide = initialState.attractions[0]

        // Tap near targetRide
        val stateWithTap = engine.handleTap(initialState, targetRide.x, targetRide.y)

        assertTrue(stateWithTap.attractions.find { it.id == targetRide.id }?.isSelected == true)
        assertFalse(stateWithTap.attractions.find { it.id != targetRide.id }?.isSelected == true)
    }

    @Test
    fun testHandleTapSnapsToGridWhenBuilding() {
        val initialState = engine.createInitialState(1000f, 1800f)
        val buildState = engine.selectBuildType(initialState, AttractionType.ROLLER_COASTER)

        val tapX = 240f
        val tapY = 360f
        val expectedSnappedX = round(tapX / GameEngine.GRID_SIZE) * GameEngine.GRID_SIZE // 200f
        val expectedSnappedY = round(tapY / GameEngine.GRID_SIZE) * GameEngine.GRID_SIZE // 400f

        val stateAfterTap = engine.handleTap(buildState, tapX, tapY)

        assertEquals(initialState.money - AttractionType.ROLLER_COASTER.cost, stateAfterTap.money)
        assertEquals(initialState.attractions.size + 1, stateAfterTap.attractions.size)

        val newRide = stateAfterTap.attractions.last()
        assertEquals(expectedSnappedX, newRide.x, 0.01f)
        assertEquals(expectedSnappedY, newRide.y, 0.01f)
        assertTrue(newRide.isSelected)
        assertNull(stateAfterTap.selectedAttractionType)
    }

    @Test
    fun testHandleTapDeselectsAttractionsWhenEmptySpaceTapped() {
        val initialState = engine.createInitialState(1000f, 1800f)
        // Select first ride
        val ride = initialState.attractions[0]
        val selectedState = engine.handleTap(initialState, ride.x, ride.y)
        assertTrue(selectedState.attractions.any { it.isSelected })

        // Tap far away in empty space (e.g., -2000f, -2000f)
        val deselectedState = engine.handleTap(selectedState, -2000f, -2000f)
        assertFalse(deselectedState.attractions.any { it.isSelected })
    }
}
