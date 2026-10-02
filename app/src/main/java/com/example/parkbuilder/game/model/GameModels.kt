package com.example.parkbuilder.game.model

import androidx.compose.ui.graphics.Color

enum class AttractionType(val displayName: String, val cost: Int, val ticketPrice: Int) {
    FERRIS_WHEEL("Ferris Wheel", 200, 15),
    ROLLER_COASTER("Roller Coaster", 500, 30),
    CAROUSEL("Carousel", 150, 10)
}

data class Attraction(
    val id: String,
    val name: String,
    val type: AttractionType,
    val x: Float,
    val y: Float,
    val radius: Float = 60f,
    val color: Color,
    val rotationAngle: Float = 0f,
    val rotationSpeed: Float = 45f, // degrees per second
    val isSelected: Boolean = false
)

data class Visitor(
    val id: String,
    val x: Float,
    val y: Float,
    val targetX: Float,
    val targetY: Float,
    val speed: Float = 100f, // pixels per second
    val color: Color,
    val happiness: Float = 1.0f // 0.0 to 1.0
)

data class GameState(
    val parkName: String = "Wonder Park",
    val money: Int = 1000,
    val attractions: List<Attraction> = emptyList(),
    val visitors: List<Visitor> = emptyList(),
    val selectedAttractionType: AttractionType? = null,
    val isPaused: Boolean = false,
    val fps: Int = 60,
    val screenWidth: Float = 1000f,
    val screenHeight: Float = 1800f
)
