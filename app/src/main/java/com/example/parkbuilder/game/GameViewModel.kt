package com.example.parkbuilder.game

import androidx.lifecycle.ViewModel
import com.example.parkbuilder.game.model.AttractionType
import com.example.parkbuilder.game.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GameViewModel : ViewModel() {

    val GRID_SIZE = GameEngine.GRID_SIZE

    private val engine = GameEngine()

    private val _gameState = MutableStateFlow(
        engine.createInitialState(width = 1000f, height = 1800f)
    )
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    fun updateScreenBounds(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        _gameState.update { current ->
            if (current.screenWidth != width || current.screenHeight != height) {
                current.copy(screenWidth = width, screenHeight = height)
            } else {
                current
            }
        }
    }

    fun onFrameTick(deltaTime: Float) {
        _gameState.update { current ->
            engine.update(current, deltaTime)
        }
    }

    fun togglePause() {
        _gameState.update { current ->
            current.copy(isPaused = !current.isPaused)
        }
    }

    fun handleTap(tapX: Float, tapY: Float) {
        _gameState.update { current ->
            engine.handleTap(current, tapX, tapY)
        }
    }

    fun selectBuildType(type: AttractionType?) {
        _gameState.update { current ->
            engine.selectBuildType(current, type)
        }
    }

    fun buildAttraction(type: AttractionType) {
        _gameState.update { current ->
            engine.buildAttraction(current, type)
        }
    }

    fun addVisitor() {
        _gameState.update { current ->
            val newVisitor = engine.spawnRandomVisitor(current.screenWidth, current.screenHeight)
            current.copy(visitors = current.visitors + newVisitor)
        }
    }
}
