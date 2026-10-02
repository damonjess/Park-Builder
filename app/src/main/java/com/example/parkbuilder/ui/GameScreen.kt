package com.example.parkbuilder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.parkbuilder.game.GameViewModel
import com.example.parkbuilder.game.model.Iso
import com.example.parkbuilder.game.model.ParkMap
import com.example.parkbuilder.game.model.TilePos
import com.example.parkbuilder.game.model.ToolCategory
import kotlinx.coroutines.isActive

private const val MIN_ZOOM = 0.35f
private const val MAX_ZOOM = 4.5f

/** A tap that travels less than this many pixels counts as a tap, not a drag. */
private const val TAP_SLOP = 18f

@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    viewModel: GameViewModel = viewModel()
) {
    val state by viewModel.gameState.collectAsStateWithLifecycle()
    val textMeasurer = rememberTextMeasurer()

    var camera by remember { mutableStateOf(Camera()) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var cameraPlaced by remember { mutableStateOf(false) }
    var ghost by remember { mutableStateOf<BuildGhost?>(null) }
    var category by remember { mutableStateOf(ToolCategory.RIDE) }
    var gateOpen by remember { mutableStateOf(false) }

    // Reading these through rememberUpdatedState keeps the long-lived gesture coroutine
    // from capturing a stale camera or tool.
    val liveCamera = rememberUpdatedState(camera)
    val liveMap = rememberUpdatedState(state.map)

    // ---- Frame loop ------------------------------------------------------
    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                viewModel.onFrameTick((now - lastFrame) / 1_000_000_000f)
                lastFrame = now
            }
        }
    }

    // ---- Camera helpers --------------------------------------------------
    fun clamp(candidate: Camera): Camera {
        val size = viewport
        if (size.width <= 0f || size.height <= 0f) return candidate
        val map = state.map
        val focus = Iso.toScreen(map.cols / 2f, map.rows / 2f)
        val sx = focus.x * candidate.zoom + candidate.panX
        val sy = focus.y * candidate.zoom + candidate.panY
        val cx = sx.coerceIn(-size.width * 0.35f, size.width * 1.35f)
        val cy = sy.coerceIn(-size.height * 0.35f, size.height * 1.35f)
        return candidate.copy(
            panX = candidate.panX + (cx - sx),
            panY = candidate.panY + (cy - sy)
        )
    }

    fun zoomAround(factor: Float, focus: Offset) {
        val current = camera
        val zoom = (current.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (zoom == current.zoom) return
        // Keep whatever is under the fingers pinned in place while zooming.
        val world = current.screenToWorld(focus)
        camera = clamp(
            Camera(
                zoom = zoom,
                panX = focus.x - world.x * zoom,
                panY = focus.y - world.y * zoom
            )
        )
    }

    fun tileAt(screen: Offset): TilePos? {
        val world = liveCamera.value.screenToWorld(screen)
        val tile = Iso.toTile(world.x, world.y)
        return if (tile.col in 0 until state.map.cols && tile.row in 0 until state.map.rows) tile else null
    }

    LaunchedEffect(state.selectedItem) {
        if (state.selectedItem == null) ghost = null
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1E3A18))) {

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val size = Size(
                        coordinates.size.width.toFloat(),
                        coordinates.size.height.toFloat()
                    )
                    if (size != viewport) {
                        viewport = size
                        if (!cameraPlaced && size.width > 0f) {
                            // Open with the park filling the frame, gates and road in shot.
                            camera = clamp(framingCamera(size, liveMap.value))
                            cameraPlaced = true
                        }
                    }
                }
                .pointerInput(state.selectedItem) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tool = state.selectedItem
                        val paints = tool != null &&
                            (tool.isTerrainBrush || tool.category == ToolCategory.SCENERY)

                        var pointerCount = 1
                        var lastCentroid = down.position
                        var lastSpread = 0f
                        var travel = 0f
                        var lastTile: TilePos? = null

                        if (tool != null) {
                            tileAt(down.position)?.let {
                                ghost = BuildGhost(tool, it.col, it.row)
                                if (paints) viewModel.dragTile(it.col, it.row)
                                lastTile = it
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size != pointerCount) {
                                // Pointer added or lifted: rebase so the camera does not jump.
                                pointerCount = pressed.size
                                lastCentroid = centroidOf(pressed)
                                lastSpread = 0f
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val centroid = centroidOf(pressed)

                            if (pressed.size >= 2) {
                                val spread = (pressed[0].position - pressed[1].position).getDistance()
                                if (lastSpread > 1f && spread > 1f) {
                                    zoomAround(spread / lastSpread, centroid)
                                }
                                val delta = centroid - lastCentroid
                                if (delta != Offset.Zero) {
                                    camera = clamp(
                                        camera.copy(
                                            panX = camera.panX + delta.x,
                                            panY = camera.panY + delta.y
                                        )
                                    )
                                }
                                lastSpread = spread
                            } else {
                                val delta = centroid - lastCentroid
                                travel += delta.getDistance()
                                if (paints) {
                                    tileAt(centroid)?.let { tile ->
                                        if (tile != lastTile) {
                                            viewModel.dragTile(tile.col, tile.row)
                                            lastTile = tile
                                            ghost = BuildGhost(tool!!, tile.col, tile.row)
                                        }
                                    }
                                } else {
                                    // No tool armed: this is a plain one-finger pan.
                                    if (tool != null) {
                                        ghost = tileAt(centroid)?.let { BuildGhost(tool, it.col, it.row) }
                                    }
                                    camera = clamp(
                                        camera.copy(
                                            panX = camera.panX + delta.x,
                                            panY = camera.panY + delta.y
                                        )
                                    )
                                }
                            }

                            lastCentroid = centroid
                            event.changes.forEach { it.consume() }
                        }

                        ghost = null
                        // A tap with a building tool places it; without a tool it inspects.
                        if (travel < TAP_SLOP && !paints) {
                            tileAt(down.position)?.let { viewModel.tapTile(it.col, it.row) }
                        }
                    }
                }
        ) {
            withTransform({
                translate(camera.panX, camera.panY)
                scale(camera.zoom, camera.zoom, pivot = Offset.Zero)
            }) {
                drawPark(
                    state = state,
                    camera = camera,
                    viewport = viewport,
                    ghost = ghost,
                    textMeasurer = textMeasurer
                )
            }
        }

        // Floating Zoom & Recenter Controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(HudColors.PanelDark.copy(alpha = 0.9f))
                    .border(2.dp, HudColors.Brass, CircleShape)
                    .clickable {
                        zoomAround(1.3f, Offset(viewport.width / 2f, viewport.height / 2f))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = HudColors.Cream,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(HudColors.PanelDark.copy(alpha = 0.9f))
                    .border(2.dp, HudColors.Brass, CircleShape)
                    .clickable {
                        zoomAround(0.77f, Offset(viewport.width / 2f, viewport.height / 2f))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "−",
                    color = HudColors.Cream,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(HudColors.PanelDark.copy(alpha = 0.9f))
                    .border(2.dp, HudColors.Brass, CircleShape)
                    .clickable {
                        camera = clamp(framingCamera(viewport, liveMap.value))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🎯", fontSize = 18.sp)
            }
        }

        // Flush against the top edge: the app runs immersive, so the strip the status bar
        // used to occupy belongs to the park.
        GameHudTop(
            state = state,
            onSpeed = { viewModel.setSpeed(it) },
            onNewPark = { viewModel.newPark() },
            onGate = { gateOpen = !gateOpen },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        GameHudBottom(
            state = state,
            category = category,
            onCategory = { category = it },
            onSelectItem = { viewModel.selectItem(it) },
            onTicketPrice = { id, price -> viewModel.setTicketPrice(id, price) },
            gateOpen = gateOpen,
            onEntranceFee = { viewModel.setEntranceFee(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

/**
 * Zoom at which the park's diamond covers the viewport.
 *
 * The original fills the whole frame with park. A park floating as a small diamond in a
 * dark void is the single biggest reason a screenshot of this looked nothing like it, so
 * the opening shot is framed to fill the screen instead.
 */
fun fillZoom(viewport: Size, map: ParkMap): Float {
    if (viewport.width <= 0f || viewport.height <= 0f) return 1f
    val span = (map.cols + map.rows).toFloat()
    return maxOf(
        viewport.width / (span * Iso.HALF_W),
        viewport.height / (span * Iso.HALF_H)
    ).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

/** Camera centred on the park and zoomed so the park fills the frame. */
fun framingCamera(viewport: Size, map: ParkMap): Camera {
    val zoom = fillZoom(viewport, map)
    val focus = Iso.toScreen(map.cols / 2f, map.rows / 2f)
    return Camera(
        zoom = zoom,
        panX = viewport.width / 2f - focus.x * zoom,
        panY = viewport.height / 2f - focus.y * zoom
    )
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var x = 0f
    var y = 0f
    changes.forEach {
        x += it.position.x
        y += it.position.y
    }
    return Offset(x / changes.size, y / changes.size)
}
