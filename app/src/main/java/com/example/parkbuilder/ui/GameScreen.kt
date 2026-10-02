package com.example.parkbuilder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.parkbuilder.game.GameEngine
import com.example.parkbuilder.game.GameViewModel
import com.example.parkbuilder.game.model.Attraction
import com.example.parkbuilder.game.model.AttractionType
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Visitor
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    viewModel: GameViewModel = viewModel()
) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()

    // 1. Camera State (held in UI for smooth visual transformations)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Vsync Game Loop
    LaunchedEffect(Unit) {
        var lastFrameTimeNanos = withFrameNanos { it }

        while (isActive) {
            withFrameNanos { currentFrameTimeNanos ->
                val deltaTime = (currentFrameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                lastFrameTimeNanos = currentFrameTimeNanos

                viewModel.onFrameTick(deltaTime)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2E7D32)) // Background color
    ) {
        // 2. Interactive Canvas with Camera and Gesture Transformations
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val size = coordinates.size
                    if (size.width > 0 && size.height > 0) {
                        viewModel.updateScreenBounds(size.width.toFloat(), size.height.toFloat())
                    }
                }
                // Camera Controls: Pinch to zoom, drag to pan
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        // Clamp zoom between 0.5x and 3x
                        scale = (scale * zoomChange).coerceIn(0.5f, 3f)
                        pan += panChange
                    }
                }
                // Tap Controls: Placing and selecting rides
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { screenOffset ->
                            // Coordinate Translation (Screen -> World)
                            val worldX = (screenOffset.x - pan.x) / scale
                            val worldY = (screenOffset.y - pan.y) / scale

                            viewModel.handleTap(worldX, worldY)
                        }
                    )
                }
        ) {
            // 3. Apply Camera Transformation
            withTransform({
                translate(pan.x, pan.y)
                scale(scale, scale, Offset.Zero)
            }) {
                // Ground Grass Layer
                drawParkGrassGround()

                // Park Grid & Paths
                drawParkGrid(scale)

                // Placed Attractions with Selection Ring
                drawParkAttractions(gameState.attractions, scale)

                // Visitors
                drawParkVisitors(gameState.visitors)
            }
        }

        // 4. HUD Top Overlay (Static glass UI layer)
        GameHudTop(
            gameState = gameState,
            onTogglePause = { viewModel.togglePause() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        )

        // 5. Bottom Action Toolbar Overlay (Static glass UI layer)
        GameHudBottom(
            money = gameState.money,
            selectedBuildType = gameState.selectedAttractionType,
            onSelectBuildType = { type -> viewModel.selectBuildType(type) },
            onAddVisitor = { viewModel.addVisitor() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
        )
    }
}

private fun DrawScope.drawParkGrassGround() {
    drawRect(
        color = Color(0xFF4CAF50),
        topLeft = Offset(-5000f, -5000f),
        size = Size(10000f, 10000f)
    )
}

private fun DrawScope.drawParkGrid(scale: Float) {
    val gridSize = GameEngine.GRID_SIZE
    val gridLines = 50 // Draws 50 lines in each direction from center (-5000f to 5000f)

    for (i in -gridLines..gridLines) {
        val coordinate = i * gridSize

        // Vertical lines
        drawLine(
            color = Color.Black.copy(alpha = 0.2f),
            start = Offset(x = coordinate, y = -5000f),
            end = Offset(x = coordinate, y = 5000f),
            strokeWidth = 2f / scale
        )

        // Horizontal lines
        drawLine(
            color = Color.Black.copy(alpha = 0.2f),
            start = Offset(x = -5000f, y = coordinate),
            end = Offset(x = 5000f, y = coordinate),
            strokeWidth = 2f / scale
        )
    }
}

private fun DrawScope.drawParkAttractions(attractions: List<Attraction>, scale: Float) {
    attractions.forEach { attraction ->
        when (attraction.type) {
            AttractionType.FERRIS_WHEEL -> drawFerrisWheel(attraction)
            AttractionType.ROLLER_COASTER -> drawRollerCoaster(attraction)
            AttractionType.CAROUSEL -> drawCarousel(attraction)
        }

        // Draw a highlight ring if the ride is selected
        if (attraction.isSelected) {
            drawCircle(
                color = Color.Yellow,
                radius = attraction.radius + 10f,
                center = Offset(attraction.x, attraction.y),
                style = Stroke(width = 5f / scale) // Visually consistent stroke width across zoom
            )
        }
    }
}

private fun DrawScope.drawFerrisWheel(attraction: Attraction) {
    val center = Offset(attraction.x, attraction.y)
    val radius = attraction.radius

    // Base support A-frame
    drawLine(
        color = Color(0xFF37474F),
        start = Offset(center.x - radius * 0.8f, center.y + radius * 1.2f),
        end = center,
        strokeWidth = 8f
    )
    drawLine(
        color = Color(0xFF37474F),
        start = Offset(center.x + radius * 0.8f, center.y + radius * 1.2f),
        end = center,
        strokeWidth = 8f
    )

    // Outer wheel ring
    drawCircle(
        color = attraction.color,
        radius = radius,
        center = center,
        style = Stroke(width = 6f)
    )

    // Rotating spokes & cabins
    val spokeCount = 8
    val angleStep = 360f / spokeCount

    rotate(degrees = attraction.rotationAngle, pivot = center) {
        for (i in 0 until spokeCount) {
            val angleRad = Math.toRadians((i * angleStep).toDouble())
            val endX = center.x + (radius * cos(angleRad)).toFloat()
            val endY = center.y + (radius * sin(angleRad)).toFloat()

            // Spoke line
            drawLine(
                color = Color.White,
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 3f
            )

            // Passenger cabin
            drawCircle(
                color = Color(0xFFFF4081),
                radius = 12f,
                center = Offset(endX, endY)
            )
        }
    }

    // Center hub
    drawCircle(
        color = Color.White,
        radius = 14f,
        center = center
    )
}

private fun DrawScope.drawRollerCoaster(attraction: Attraction) {
    val center = Offset(attraction.x, attraction.y)
    val r = attraction.radius

    // Coaster track loop
    val path = Path().apply {
        moveTo(center.x - r * 1.5f, center.y + r)
        cubicTo(
            center.x - r, center.y - r * 1.8f,
            center.x + r, center.y - r * 1.8f,
            center.x + r * 1.5f, center.y + r
        )
    }

    drawPath(
        path = path,
        color = attraction.color,
        style = Stroke(width = 10f)
    )

    // Coaster train moving along top
    rotate(degrees = attraction.rotationAngle, pivot = center) {
        val trainX = center.x + (r * cos(Math.toRadians(attraction.rotationAngle.toDouble()))).toFloat()
        val trainY = center.y - r * 0.8f
        drawRect(
            color = Color(0xFFFFD54F),
            topLeft = Offset(trainX - 20f, trainY - 12f),
            size = Size(40f, 24f)
        )
    }
}

private fun DrawScope.drawCarousel(attraction: Attraction) {
    val center = Offset(attraction.x, attraction.y)
    val radius = attraction.radius

    // Base platform
    drawCircle(
        color = attraction.color,
        radius = radius,
        center = center
    )

    // Rotating horses / lights
    val horseCount = 6
    val angleStep = 360f / horseCount

    rotate(degrees = attraction.rotationAngle, pivot = center) {
        for (i in 0 until horseCount) {
            val angleRad = Math.toRadians((i * angleStep).toDouble())
            val hX = center.x + (radius * 0.65f * cos(angleRad)).toFloat()
            val hY = center.y + (radius * 0.65f * sin(angleRad)).toFloat()

            drawCircle(
                color = Color.White,
                radius = 10f,
                center = Offset(hX, hY)
            )
        }
    }

    // Canopy top
    drawCircle(
        color = Color(0xFFFF5252),
        radius = radius * 0.4f,
        center = center
    )
}

private fun DrawScope.drawParkVisitors(visitors: List<Visitor>) {
    visitors.forEach { visitor ->
        val pos = Offset(visitor.x, visitor.y)

        // Shadow
        drawCircle(
            color = Color(0x40000000),
            radius = 12f,
            center = Offset(pos.x + 3f, pos.y + 4f)
        )

        // Visitor Body
        drawCircle(
            color = visitor.color,
            radius = 12f,
            center = pos
        )

        // Inner highlight
        drawCircle(
            color = Color.White,
            radius = 4f,
            center = Offset(pos.x - 3f, pos.y - 3f)
        )
    }
}

@Composable
private fun GameHudTop(
    gameState: GameState,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = gameState.parkName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$${gameState.money}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "👥 ${gameState.visitors.size}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${gameState.fps} FPS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onTogglePause) {
                    Text(
                        text = if (gameState.isPaused) "▶️" else "⏸️",
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GameHudBottom(
    money: Int,
    selectedBuildType: AttractionType?,
    onSelectBuildType: (AttractionType) -> Unit,
    onAddVisitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (selectedBuildType != null) {
                    "Tap grid tile to place ${selectedBuildType.displayName} ($${selectedBuildType.cost})"
                } else {
                    "Build Park Attractions (Select to place on grid)"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selectedBuildType != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttractionType.entries.forEach { type ->
                    val canAfford = money >= type.cost
                    val isSelected = selectedBuildType == type

                    Button(
                        onClick = { onSelectBuildType(type) },
                        enabled = canAfford,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (isSelected) "✓ ${type.displayName}" else "+ ${type.displayName} ($${type.cost})",
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedButton(onClick = onAddVisitor) {
                    Text(text = "+ Visitor", fontSize = 12.sp)
                }
            }
        }
    }
}
