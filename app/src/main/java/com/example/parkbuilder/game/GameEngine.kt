package com.example.parkbuilder.game

import androidx.compose.ui.graphics.Color
import com.example.parkbuilder.game.model.Attraction
import com.example.parkbuilder.game.model.AttractionType
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Visitor
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameEngine {

    companion object {
        const val GRID_SIZE = 100f
    }

    private var incomeAccumulator = 0f
    private var visitorSpawnTimer = 0f
    private var frameCount = 0
    private var fpsTimer = 0f
    private var currentFps = 60

    fun createInitialState(width: Float, height: Float): GameState {
        val centerX = if (width > 0) round((width / 2f) / GRID_SIZE) * GRID_SIZE else 500f
        val centerY = if (height > 0) round((height / 2f) / GRID_SIZE) * GRID_SIZE else 900f

        val initialAttractions = listOf(
            Attraction(
                id = UUID.randomUUID().toString(),
                name = "Grand Ferris Wheel",
                type = AttractionType.FERRIS_WHEEL,
                x = centerX - 200f,
                y = centerY - 200f,
                radius = GRID_SIZE / 2f,
                color = Color(0xFF6200EE),
                rotationSpeed = 40f
            ),
            Attraction(
                id = UUID.randomUUID().toString(),
                name = "Merry Carousel",
                type = AttractionType.CAROUSEL,
                x = centerX + 200f,
                y = centerY + 200f,
                radius = GRID_SIZE / 2f,
                color = Color(0xFF03DAC6),
                rotationSpeed = 60f
            )
        )

        val initialVisitors = List(8) {
            spawnRandomVisitor(width = width, height = height)
        }

        return GameState(
            parkName = "Wonder Park",
            money = 1200,
            attractions = initialAttractions,
            visitors = initialVisitors,
            screenWidth = width,
            screenHeight = height
        )
    }

    fun update(currentState: GameState, deltaTime: Float): GameState {
        if (currentState.isPaused || deltaTime <= 0f) {
            return currentState
        }

        // Cap deltaTime to prevent huge jumps on lag spikes
        val safeDelta = deltaTime.coerceAtMost(0.1f)

        // 1. Calculate FPS
        frameCount++
        fpsTimer += safeDelta
        if (fpsTimer >= 0.5f) {
            currentFps = (frameCount / fpsTimer).toInt()
            frameCount = 0
            fpsTimer = 0f
        }

        // 2. Update Attractions (Rotation & Animations)
        val updatedAttractions = currentState.attractions.map { attraction ->
            val newAngle = (attraction.rotationAngle + attraction.rotationSpeed * safeDelta) % 360f
            attraction.copy(rotationAngle = newAngle)
        }

        // 3. Update Visitors Position & Movement
        val updatedVisitors = currentState.visitors.map { visitor ->
            updateVisitorPosition(visitor, safeDelta, currentState.screenWidth, currentState.screenHeight)
        }

        // 4. Update Income Generation
        var newMoney = currentState.money
        incomeAccumulator += safeDelta * (updatedAttractions.size * 5f)
        if (incomeAccumulator >= 1.0f) {
            val earned = incomeAccumulator.toInt()
            newMoney += earned
            incomeAccumulator -= earned
        }

        // 5. Periodic Visitor Spawning
        var finalVisitors = updatedVisitors
        visitorSpawnTimer += safeDelta
        if (visitorSpawnTimer >= 5.0f && updatedVisitors.size < 30) {
            visitorSpawnTimer = 0f
            finalVisitors = updatedVisitors + spawnRandomVisitor(currentState.screenWidth, currentState.screenHeight)
        }

        return currentState.copy(
            money = newMoney,
            attractions = updatedAttractions,
            visitors = finalVisitors,
            fps = currentFps
        )
    }

    private fun updateVisitorPosition(visitor: Visitor, deltaTime: Float, width: Float, height: Float): Visitor {
        val dx = visitor.targetX - visitor.x
        val dy = visitor.targetY - visitor.y
        val distance = sqrt(dx * dx + dy * dy)

        // If visitor reached target, pick a new random target point
        if (distance < 10f) {
            val newTargetX = Random.nextFloat() * (width.coerceAtLeast(400f) - 100f) + 50f
            val newTargetY = Random.nextFloat() * (height.coerceAtLeast(600f) - 100f) + 50f
            return visitor.copy(targetX = newTargetX, targetY = newTargetY)
        }

        val angle = atan2(dy, dx)
        val moveDistance = visitor.speed * deltaTime
        val newX = visitor.x + cos(angle) * moveDistance
        val newY = visitor.y + sin(angle) * moveDistance

        return visitor.copy(x = newX, y = newY)
    }

    fun spawnRandomVisitor(width: Float, height: Float): Visitor {
        val w = if (width > 0) width else 1000f
        val h = if (height > 0) height else 1800f
        val startX = Random.nextFloat() * (w - 100f) + 50f
        val startY = Random.nextFloat() * (h - 100f) + 50f
        val targetX = Random.nextFloat() * (w - 100f) + 50f
        val targetY = Random.nextFloat() * (h - 100f) + 50f

        val visitorColors = listOf(
            Color(0xFFFF5722),
            Color(0xFFE91E63),
            Color(0xFF9C27B0),
            Color(0xFF2196F3),
            Color(0xFF4CAF50),
            Color(0xFFFFEB3B)
        )

        return Visitor(
            id = UUID.randomUUID().toString(),
            x = startX,
            y = startY,
            targetX = targetX,
            targetY = targetY,
            speed = Random.nextFloat() * 60f + 70f,
            color = visitorColors.random()
        )
    }

    fun selectBuildType(currentState: GameState, type: AttractionType?): GameState {
        val newType = if (currentState.selectedAttractionType == type) null else type
        return currentState.copy(selectedAttractionType = newType)
    }

    fun handleTap(currentState: GameState, tapX: Float, tapY: Float): GameState {
        // 1. Snap coordinates to grid node
        val snappedX = round(tapX / GRID_SIZE) * GRID_SIZE
        val snappedY = round(tapY / GRID_SIZE) * GRID_SIZE

        // 2. Check if a ride exists at this exact grid node or within hit radius
        val tappedAttraction = currentState.attractions.find { attraction ->
            (attraction.x == snappedX && attraction.y == snappedY) ||
                    sqrt((tapX - attraction.x).pow(2) + (tapY - attraction.y).pow(2)) <= attraction.radius
        }

        return if (tappedAttraction != null) {
            // Select tapped attraction, deselect others
            currentState.copy(
                attractions = currentState.attractions.map {
                    it.copy(isSelected = (it.id == tappedAttraction.id))
                }
            )
        } else {
            // 3. Build a new ride exactly on the grid node
            val buildType = currentState.selectedAttractionType
            if (buildType != null && currentState.money >= buildType.cost) {
                val color = when (buildType) {
                    AttractionType.FERRIS_WHEEL -> Color(0xFF3F51B5)
                    AttractionType.ROLLER_COASTER -> Color(0xFFE91E63)
                    AttractionType.CAROUSEL -> Color(0xFF009688)
                }
                val newAttraction = Attraction(
                    id = UUID.randomUUID().toString(),
                    name = buildType.displayName,
                    type = buildType,
                    x = snappedX,
                    y = snappedY,
                    radius = GRID_SIZE / 2f,
                    color = color,
                    rotationSpeed = if (buildType == AttractionType.ROLLER_COASTER) 120f else 45f,
                    isSelected = true
                )
                currentState.copy(
                    money = currentState.money - buildType.cost,
                    attractions = currentState.attractions.map { it.copy(isSelected = false) } + newAttraction,
                    selectedAttractionType = null
                )
            } else {
                // Deselect all existing attractions
                currentState.copy(
                    attractions = currentState.attractions.map { it.copy(isSelected = false) }
                )
            }
        }
    }

    fun buildAttraction(currentState: GameState, type: AttractionType): GameState {
        if (currentState.money < type.cost) return currentState

        val rawX = Random.nextFloat() * (currentState.screenWidth - 200f) + 100f
        val rawY = Random.nextFloat() * (currentState.screenHeight - 300f) + 150f
        val snappedX = round(rawX / GRID_SIZE) * GRID_SIZE
        val snappedY = round(rawY / GRID_SIZE) * GRID_SIZE

        val color = when (type) {
            AttractionType.FERRIS_WHEEL -> Color(0xFF3F51B5)
            AttractionType.ROLLER_COASTER -> Color(0xFFE91E63)
            AttractionType.CAROUSEL -> Color(0xFF009688)
        }

        val newAttraction = Attraction(
            id = UUID.randomUUID().toString(),
            name = type.displayName,
            type = type,
            x = snappedX,
            y = snappedY,
            radius = GRID_SIZE / 2f,
            color = color,
            rotationSpeed = if (type == AttractionType.ROLLER_COASTER) 120f else 45f
        )

        return currentState.copy(
            money = currentState.money - type.cost,
            attractions = currentState.attractions + newAttraction
        )
    }
}
