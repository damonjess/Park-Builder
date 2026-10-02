package com.example.parkbuilder.game

import androidx.lifecycle.ViewModel
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameSpeed
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.ToolCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the engine and the single source of truth for the simulation.
 *
 * Everything the UI needs to *render* lives in [GameState]; the camera lives in the
 * composable so panning and pinching never re-enter the simulation.
 */
class GameViewModel : ViewModel() {

    private var engine = GameEngine()

    private val _gameState = MutableStateFlow(ParkGenerator.newPark())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    fun onFrameTick(deltaTime: Float) {
        _gameState.update { engine.update(it, deltaTime) }
    }

    fun selectItem(item: BuildItem?) {
        _gameState.update { engine.selectItem(it, item) }
    }

    fun setSpeed(speed: GameSpeed) {
        _gameState.update { engine.setSpeed(it, speed) }
    }

    /** Tap on the park: build with the active tool, otherwise inspect what is there. */
    fun tapTile(col: Int, row: Int) {
        _gameState.update { state ->
            val tool = state.selectedItem
            if (tool != null) engine.applyTool(state, tool, col, row)
            else engine.selectStructure(state, col, row)
        }
    }

    /** Drag with a tool selected: lay path, or plant rows of scenery. */
    fun dragTile(col: Int, row: Int) {
        _gameState.update { state ->
            val tool = state.selectedItem ?: return@update state
            if (!tool.isTerrainBrush && tool.category != ToolCategory.SCENERY) {
                return@update state
            }
            engine.applyTool(state, tool, col, row)
        }
    }

    /** Wipe the park and start over with a fresh simulation. */
    fun newPark() {
        engine = GameEngine()
        _gameState.value = ParkGenerator.newPark()
    }
}
