package com.example.parkbuilder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.parkbuilder.game.Placement
import com.example.parkbuilder.game.placementFor
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Iso
import com.example.parkbuilder.game.model.ParkMap
import com.example.parkbuilder.game.model.Structure
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.Visitor
import com.example.parkbuilder.game.model.VisitorState
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Saturated, high-contrast palette in the spirit of the PS1 original. */
object Palette {
    val GrassA = Color(0xFF62B446)
    val GrassB = Color(0xFF56A63C)
    val GrassC = Color(0xFF6CBF4F)
    val GrassTuft = Color(0xFF3F8A2C)

    val PathA = Color(0xFFBCAB92)
    val PathB = Color(0xFFAE9C82)
    val PathSpeck = Color(0xFFCFC0A8)
    val PathEdge = Color(0xFF8A7860)

    val WaterDeep = Color(0xFF1B5EA6)
    val WaterMid = Color(0xFF2F82D4)
    val WaterLight = Color(0xFF63B4F0)

    val Outline = Color(0xFF2B2318)
    val Shadow = Color(0x3A000000)

    val RoofRed = Color(0xFFD8452F)
    val RoofCream = Color(0xFFF6E3B0)
    val RoofBlue = Color(0xFF3A6FD8)
    val RoofTeal = Color(0xFF17A398)
    val WallCream = Color(0xFFF0DCA8)
    val WallPink = Color(0xFFE8A0A8)
    val WallStone = Color(0xFFC9BFA9)
    val Metal = Color(0xFF8892A0)
    val MetalDark = Color(0xFF5B6673)
    val Wood = Color(0xFF9A6B3F)
    val WoodDark = Color(0xFF6E4A28)
    val Trunk = Color(0xFF7A5230)
    val LeafDark = Color(0xFF2F7A22)
    val LeafMid = Color(0xFF47A32F)
    val LeafLight = Color(0xFF6AC44A)
    val Gold = Color(0xFFFFC63D)
    val Skin = Color(0xFFF2C08A)
}

/** Visitor clothing colours, indexed by `paletteIndex`. */
private val VisitorShirts = listOf(
    Color(0xFFE8402F), Color(0xFF2F6FE8), Color(0xFFF2C53D), Color(0xFF43B04A),
    Color(0xFFB44FD8), Color(0xFFEE7B2F), Color(0xFF2FC0C7), Color(0xFFE8459B)
)

private val VisitorHair = listOf(
    Color(0xFF2E2118), Color(0xFF5A3A20), Color(0xFFC9A227), Color(0xFF3A3A3A)
)

/** Pan/zoom state for the park view. Kept in the UI so it never touches the simulation. */
data class Camera(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f
) {
    fun screenToWorldX(screenX: Float): Float = (screenX - panX) / zoom
    fun screenToWorldY(screenY: Float): Float = (screenY - panY) / zoom

    fun screenToWorld(offset: Offset): Offset =
        Offset(screenToWorldX(offset.x), screenToWorldY(offset.y))

    fun worldToScreen(world: Offset): Offset =
        Offset(world.x * zoom + panX, world.y * zoom + panY)
}

/** The translucent preview shown under the finger while a build tool is armed. */
data class BuildGhost(val item: BuildItem, val col: Int, val row: Int)

/**
 * Single reusable path for every polygon we draw.
 *
 * A park of 40x40 tiles plus a few hundred buildings is thousands of polygons a frame, so
 * allocating a fresh [Path] per shape would churn the heap hard enough to show up as jank.
 * Drawing is single-threaded on the UI thread, so one shared, rewound buffer is safe.
 */
private val shapePath = Path()

// ======================================================================
// Entry point
// ======================================================================

/**
 * Draws the whole park inside the current (already camera-transformed) canvas.
 *
 * Callers apply the camera transform (translate by pan, scale by zoom) before calling;
 * every coordinate in here is therefore in world pixels, which is what makes hit-testing
 * a plain inverse of that same transform.
 */
fun DrawScope.drawPark(
    state: GameState,
    camera: Camera,
    viewport: Size,
    ghost: BuildGhost?,
    textMeasurer: TextMeasurer
) {
    val map = state.map
    val bounds = visibleTileBounds(camera, viewport, map.cols, map.rows)

    // Flat ground first: no object ever needs to be occluded by terrain.
    for (col in bounds[0]..bounds[1]) {
        for (row in bounds[2]..bounds[3]) {
            if (!map.inBounds(col, row)) continue
            drawGroundTile(map, col, row, state.gameTime)
        }
    }

    ghost?.let { drawGhostTiles(state, it) }

    // Then everything with height, back to front. Visitors are interleaved with buildings
    // so a guest strolling behind the coaster is correctly hidden by it.
    val queue = ArrayList<Sortable>(96)
    state.structures.forEach { structure ->
        if (structure.maxCol < bounds[0] || structure.col > bounds[1]) return@forEach
        if (structure.maxRow < bounds[2] || structure.row > bounds[3]) return@forEach
        queue += Sortable(structure.depth, 0, structure)
    }
    state.visitors.forEach { visitor ->
        val col = visitor.col.toInt()
        val row = visitor.row.toInt()
        if (col < bounds[0] || col > bounds[1] || row < bounds[2] || row > bounds[3]) return@forEach
        queue += Sortable(col + row, 1, visitor = visitor)
    }
    queue.sortBy { it.depth }

    queue.forEach { entry ->
        when (entry.kind) {
            0 -> drawStructure(entry.structure!!, map)
            else -> drawVisitor(entry.visitor!!)
        }
    }

    // Drawn after the depth pass so the highlight reads on top of the building it belongs to.
    state.structures.firstOrNull { it.isSelected }?.let { drawSelection(it) }

    drawEntrance(state, textMeasurer)
    ghost?.let { drawGhostPreview(state, it) }
}

/** Depth-sorted render item. Two typed slots avoid a lambda per entity per frame. */
private class Sortable(
    val depth: Int,
    val kind: Int,
    val structure: Structure? = null,
    val visitor: Visitor? = null
)

// ======================================================================
// Terrain
// ======================================================================

private fun DrawScope.drawGroundTile(map: ParkMap, col: Int, row: Int, time: Float) {
    val center = Iso.toScreen(col + 0.5f, row + 0.5f)
    val detail = map.detailAt(col, row)
    when (map.terrainAt(col, row)) {
        Terrain.GRASS -> {
            val tone = when (detail % 3) {
                0 -> Palette.GrassA
                1 -> Palette.GrassB
                else -> Palette.GrassC
            }
            diamond(center, 1.01f, tone)
            if (detail % 7 == 0) {
                // Tiny flower scatter, stable because it is derived from the tile hash.
                val fx = center.x + (detail % 13) - 6f
                val fy = center.y + (detail % 5) - 2f
                drawRect(Palette.Gold, Offset(fx, fy), Size(2f, 2f))
            } else if (detail % 11 == 0) {
                drawRect(Palette.GrassTuft, Offset(center.x - 1f, center.y - 2f), Size(3f, 4f))
            }
        }

        Terrain.PATH -> {
            // Expanded slightly so neighbouring path tiles overlap instead of leaving
            // grass hairlines between them.
            diamond(center, 1.04f, if (detail % 2 == 0) Palette.PathA else Palette.PathB)
            diamondStroke(center, 1.04f, Palette.PathEdge)
            if (detail % 5 == 0) {
                drawRect(
                    Palette.PathSpeck,
                    Offset(center.x + (detail % 9) - 5f, center.y + (detail % 7) - 4f),
                    Size(3f, 2f)
                )
            }
        }

        Terrain.WATER -> {
            val shimmer = sin(time * 1.6f + (col + row) * 0.6f)
            val tone = when {
                shimmer > 0.55f -> Palette.WaterLight
                shimmer > -0.2f -> Palette.WaterMid
                else -> Palette.WaterDeep
            }
            diamond(center, 1.04f, tone)
            if (shimmer > 0.8f) {
                drawRect(
                    Palette.WaterLight,
                    Offset(center.x - 7f, center.y - 1f),
                    Size(14f, 2f)
                )
            }
        }
    }
}

private fun DrawScope.diamond(center: Offset, expand: Float, fill: Color) {
    val hw = Iso.HALF_W * expand
    val hh = Iso.HALF_H * expand
    shapePath.rewind()
    shapePath.moveTo(center.x, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y)
    shapePath.lineTo(center.x, center.y + hh)
    shapePath.lineTo(center.x - hw, center.y)
    shapePath.close()
    drawPath(shapePath, fill)
}

private fun DrawScope.diamondStroke(center: Offset, expand: Float, color: Color) {
    val hw = Iso.HALF_W * expand
    val hh = Iso.HALF_H * expand
    shapePath.rewind()
    shapePath.moveTo(center.x, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y)
    shapePath.lineTo(center.x, center.y + hh)
    shapePath.lineTo(center.x - hw, center.y)
    shapePath.close()
    drawPath(shapePath, color, style = Stroke(width = 1f))
}

// ======================================================================
// Primitives
// ======================================================================

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, fill: Color) {
    shapePath.rewind()
    shapePath.moveTo(a.x, a.y)
    shapePath.lineTo(b.x, b.y)
    shapePath.lineTo(c.x, c.y)
    shapePath.lineTo(d.x, d.y)
    shapePath.close()
    drawPath(shapePath, fill)
}

private fun DrawScope.tri(a: Offset, b: Offset, c: Offset, fill: Color) {
    shapePath.rewind()
    shapePath.moveTo(a.x, a.y)
    shapePath.lineTo(b.x, b.y)
    shapePath.lineTo(c.x, c.y)
    shapePath.close()
    drawPath(shapePath, fill)
}

/**
 * Scratch buffer for outlines. `Offset` is a value class, so a `vararg Offset` is rejected
 * outright and a `List<Offset>` would box every point; writing into a shared FloatArray
 * keeps the hot path allocation-free.
 */
private val outlineBuffer = FloatArray(24)

private fun putPoint(index: Int, point: Offset) {
    outlineBuffer[index * 2] = point.x
    outlineBuffer[index * 2 + 1] = point.y
}

private fun DrawScope.outlineBuffered(count: Int, width: Float = 1.4f, color: Color = Palette.Outline) {
    shapePath.rewind()
    shapePath.moveTo(outlineBuffer[0], outlineBuffer[1])
    for (i in 1 until count) shapePath.lineTo(outlineBuffer[i * 2], outlineBuffer[i * 2 + 1])
    shapePath.close()
    drawPath(shapePath, color, style = Stroke(width = width))
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * A shaded isometric box.
 *
 * The camera sits to the south of the diamond, so exactly two vertical faces are ever
 * visible: the one along the `row + h` edge (lit dark) and the one along the `col + w`
 * edge (lit mid). The top face gets the brightest tone, which is what gives the blocky
 * PS1 look its readable volume.
 */
private fun DrawScope.isoBlock(
    col: Float,
    row: Float,
    w: Float,
    h: Float,
    height: Float,
    top: Color,
    left: Color,
    right: Color,
    outline: Boolean = true
) {
    val groundTop = Iso.toScreen(col, row)
    val groundRight = Iso.toScreen(col + w, row)
    val groundBottom = Iso.toScreen(col + w, row + h)
    val groundLeft = Iso.toScreen(col, row + h)
    val up = Offset(0f, -height)

    quad(groundLeft, groundBottom, groundBottom + up, groundLeft + up, left)
    quad(groundBottom, groundRight, groundRight + up, groundBottom + up, right)
    quad(
        groundTop + up, groundRight + up, groundBottom + up, groundLeft + up, top
    )

    if (outline) {
        putPoint(0, groundTop + up)
        putPoint(1, groundRight + up)
        putPoint(2, groundRight)
        putPoint(3, groundBottom)
        putPoint(4, groundLeft)
        putPoint(5, groundLeft + up)
        outlineBuffered(6)
    }
}

/** The four ground corners of a footprint, in screen space. */
private fun corners(col: Float, row: Float, w: Float, h: Float): Array<Offset> = arrayOf(
    Iso.toScreen(col, row),
    Iso.toScreen(col + w, row),
    Iso.toScreen(col + w, row + h),
    Iso.toScreen(col, row + h)
)

/**
 * Striped pyramid roof — the circus-tent shape used for carousels, shops and awnings.
 * Only the two camera-facing faces exist in the silhouette, and each is sliced into
 * alternating stripes.
 */
private fun DrawScope.pyramidRoof(
    baseTop: Offset,
    baseRight: Offset,
    baseBottom: Offset,
    baseLeft: Offset,
    apexHeight: Float,
    colorA: Color,
    colorB: Color,
    slices: Int = 4
) {
    val apexGround = Offset(
        (baseTop.x + baseBottom.x) / 2f,
        (baseTop.y + baseBottom.y) / 2f
    )
    val apex = Offset(apexGround.x, apexGround.y - apexHeight)

    // Face 1 spans the left corner -> bottom corner edge.
    for (i in 0 until slices) {
        val t0 = i.toFloat() / slices
        val t1 = (i + 1).toFloat() / slices
        val edgeA = lerp(baseLeft, baseBottom, t0)
        val edgeB = lerp(baseLeft, baseBottom, t1)
        tri(edgeA, edgeB, apex, if (i % 2 == 0) colorA else colorB)
    }
    // Face 2 spans the bottom corner -> right corner edge.
    for (i in 0 until slices) {
        val t0 = i.toFloat() / slices
        val t1 = (i + 1).toFloat() / slices
        val edgeA = lerp(baseBottom, baseRight, t0)
        val edgeB = lerp(baseBottom, baseRight, t1)
        tri(edgeA, edgeB, apex, if (i % 2 == 0) colorA else colorB)
    }
    putPoint(0, baseLeft)
    putPoint(1, apex)
    putPoint(2, baseRight)
    putPoint(3, baseBottom)
    outlineBuffered(4, width = 1.2f)
}

// ======================================================================
// Structures
// ======================================================================

private fun DrawScope.drawStructure(structure: Structure, map: ParkMap) {
    // Ground shadow under the whole footprint.
    val shadowCenter = Iso.toScreen(structure.centerCol(), structure.centerRow())
    diamond(shadowCenter, 1.0f, Palette.Shadow)

    when (structure.item) {
        BuildItem.CAROUSEL -> drawCarousel(structure)
        BuildItem.FERRIS_WHEEL -> drawFerrisWheel(structure)
        BuildItem.DODGEMS -> drawDodgems(structure)
        BuildItem.LOG_FLUME -> drawLogFlume(structure)
        BuildItem.ROLLER_COASTER -> drawRollerCoaster(structure)
        BuildItem.BURGER_BAR -> drawShopHut(structure, Palette.RoofRed, Palette.RoofCream)
        BuildItem.SODA_STAND -> drawShopHut(structure, Palette.RoofBlue, Palette.RoofCream)
        BuildItem.ICE_CREAM -> drawShopHut(structure, Palette.RoofTeal, Palette.RoofCream)
        BuildItem.GIFT_SHOP -> drawShopHut(structure, Palette.RoofBlue, Palette.RoofRed)
        BuildItem.RESTROOM -> drawShopHut(structure, Palette.WallStone, Palette.RoofCream)
        BuildItem.FOUNTAIN -> drawFountain(structure)
        BuildItem.TREE -> drawTree(structure)
        BuildItem.LAMP -> drawLamp(structure)
        BuildItem.BENCH -> drawBench(structure)
        BuildItem.FLOWERS -> drawFlowers(structure, map)
        else -> isoBlock(
            structure.col.toFloat(), structure.row.toFloat(),
            structure.wTiles.toFloat(), structure.hTiles.toFloat(),
            structure.item.blockHeight,
            Palette.WallCream, Palette.WallPink, Palette.Metal
        )
    }
}

private fun DrawScope.drawCarousel(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)
    val deckHeight = 10f

    isoBlock(
        col, row, w, h, deckHeight,
        Palette.WallCream, Palette.WallPink, Palette.Gold,
        outline = false
    )

    val deck = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - deckHeight)

    // Riding horses: small blocks parked around the deck, spinning with animAngle.
    rotate(degrees = structure.animAngle, pivot = deck) {
        for (i in 0 until 6) {
            val angle = i * 60f
            val horseAngle = angle * PI / 180.0
            val hx = deck.x + (cos(horseAngle) * 22f).toFloat()
            val hy = deck.y + (sin(horseAngle) * 11f).toFloat()
            drawRect(Palette.WallCream, Offset(hx - 3f, hy - 12f), Size(6f, 8f))
            drawRect(Palette.Outline, Offset(hx - 3f, hy - 4f), Size(6f, 2f))
        }
    }

    val top = Offset(base[0].x + (base[2].x - base[0].x) / 2f, base[0].y - deckHeight)
    val right = Offset(base[1].x, base[1].y - deckHeight)
    val bottom = Offset(base[2].x, base[2].y - deckHeight)
    val left = Offset(base[3].x, base[3].y - deckHeight)
    pyramidRoof(
        top, right, bottom, left,
        apexHeight = structure.item.blockHeight,
        colorA = Palette.RoofRed,
        colorB = Palette.RoofCream
    )
    // Finial.
    val apexGround = Offset((top.x + bottom.x) / 2f, (top.y + bottom.y) / 2f)
    drawRect(
        Palette.Gold,
        Offset(apexGround.x - 2f, apexGround.y - structure.item.blockHeight - 8f),
        Size(4f, 8f)
    )
}

private fun DrawScope.drawFerrisWheel(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val plat = structure.item.blockHeight

    isoBlock(
        col, row, w, h, 12f,
        Palette.GrassB, Palette.WallStone, Palette.MetalDark,
        outline = true
    )

    val center = Iso.toScreen(structure.centerCol(), structure.centerRow() - 0.3f)
    val hub = Offset(center.x, center.y - plat)

    // Support legs down to the platform.
    val groundCenter = Iso.toScreen(structure.centerCol(), structure.centerRow())
    drawLine(Palette.MetalDark, hub, Offset(groundCenter.x - 26f, groundCenter.y + 6f), strokeWidth = 4f)
    drawLine(Palette.MetalDark, hub, Offset(groundCenter.x + 26f, groundCenter.y + 6f), strokeWidth = 4f)

    val radius = 34f
    drawCircle(Palette.Metal, radius, hub, style = Stroke(width = 3f))
    drawCircle(Palette.Outline, radius, hub, style = Stroke(width = 1.4f))
    drawCircle(Palette.MetalDark, 5f, hub)

    rotate(degrees = structure.animAngle, pivot = hub) {
        for (i in 0 until 8) {
            val a = i * 45.0 * PI / 180.0
            val tip = Offset(hub.x + (cos(a) * radius).toFloat(), hub.y + (sin(a) * radius).toFloat())
            drawLine(Palette.Metal, hub, tip, strokeWidth = 1.4f)
            drawRect(
                if (i % 2 == 0) Palette.RoofRed else Palette.RoofBlue,
                Offset(tip.x - 5f, tip.y - 5f),
                Size(10f, 10f)
            )
            drawRect(Palette.Outline, Offset(tip.x - 5f, tip.y + 3f), Size(10f, 2f))
        }
    }
}

private fun DrawScope.drawDodgems(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(
        col, row, w, h, 8f,
        Palette.MetalDark, Palette.MetalDark, Palette.Metal,
        outline = true
    )
    val floor = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 8f)
    diamond(floor, 0.78f, Palette.RoofTeal)
    diamondStroke(floor, 0.78f, Palette.Outline)

    rotate(degrees = structure.animAngle, pivot = floor) {
        for (i in 0 until 5) {
            val a = i * 72.0 * PI / 180.0
            val cx = floor.x + (cos(a) * 18f).toFloat()
            val cy = floor.y + (sin(a) * 9f).toFloat()
            drawRect(Palette.Gold, Offset(cx - 4f, cy - 10f), Size(8f, 7f))
            drawRect(Palette.Outline, Offset(cx - 4f, cy - 3f), Size(8f, 2f))
        }
    }

    // Pole ring around the arena.
    for (i in 0 until 6) {
        val a = i * 60.0 * PI / 180.0
        val px = floor.x + (cos(a) * 28f).toFloat()
        val py = floor.y + (sin(a) * 14f).toFloat()
        drawRect(Palette.Gold, Offset(px - 1.5f, py - 16f), Size(3f, 16f))
    }
}

private fun DrawScope.drawLogFlume(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(col, row, w, h, 6f, Palette.Wood, Palette.WoodDark, Palette.Wood, outline = true)

    val deck = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 6f)
    diamond(deck, 0.86f, Palette.WaterMid)
    diamondStroke(deck, 0.86f, Palette.Outline)

    // Log boats bobbing along the channel.
    rotate(degrees = structure.animAngle, pivot = deck) {
        for (i in 0 until 3) {
            val a = i * 120.0 * PI / 180.0
            val bx = deck.x + (cos(a) * 30f).toFloat()
            val by = deck.y + (sin(a) * 15f).toFloat()
            drawRect(Palette.Wood, Offset(bx - 8f, by - 6f), Size(16f, 7f))
            drawRect(Palette.WoodDark, Offset(bx - 8f, by), Size(16f, 2f))
            drawRect(Palette.Skin, Offset(bx - 3f, by - 11f), Size(5f, 5f))
        }
    }

    // Log flume drop tower.
    val towerCenter = Iso.toScreen(structure.col + 0.4f, structure.row + 0.4f)
    val towerH = structure.item.blockHeight
    drawRect(Palette.Wood, Offset(towerCenter.x - 7f, towerCenter.y - towerH), Size(14f, towerH))
    drawRect(Palette.WoodDark, Offset(towerCenter.x + 2f, towerCenter.y - towerH), Size(5f, towerH))
    drawRect(Palette.RoofRed, Offset(towerCenter.x - 10f, towerCenter.y - towerH - 8f), Size(20f, 8f))
}

private fun DrawScope.drawRollerCoaster(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(col, row, w, h, 5f, Palette.PathA, Palette.PathEdge, Palette.PathB, outline = true)

    val station = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 5f)
    val halfW = Iso.HALF_W * w * 0.62f
    val halfH = Iso.HALF_H * h * 0.62f
    val peak = structure.item.blockHeight

    // Two rails crossing the plot in an X, peaking at the centre.
    val trackA = listOf(
        Offset(station.x - halfW, station.y + halfH),
        Offset(station.x - halfW * 0.35f, station.y - peak),
        Offset(station.x + halfW * 0.35f, station.y - peak * 0.55f),
        Offset(station.x + halfW, station.y - halfH * 0.2f)
    )
    val trackB = listOf(
        Offset(station.x + halfW, station.y + halfH),
        Offset(station.x + halfW * 0.3f, station.y - peak * 0.8f),
        Offset(station.x - halfW * 0.4f, station.y - peak * 0.4f),
        Offset(station.x - halfW, station.y - halfH * 0.4f)
    )
    listOf(trackA, trackB).forEach { track ->
        for (i in 0 until track.size - 1) {
            drawLine(Palette.Metal, track[i], track[i + 1], strokeWidth = 3f)
            drawLine(Palette.Outline, track[i], track[i + 1], strokeWidth = 1f, alpha = 0.35f)
            // Support ties down to the ground.
            val mid = lerp(track[i], track[i + 1], 0.5f)
            drawLine(Palette.Wood, mid, Offset(mid.x, mid.y + 16f), strokeWidth = 2f)
        }
    }

    // Train running the first track.
    val t = (structure.animAngle / 360f)
    val legFloat = t * (trackA.size - 1)
    val leg = legFloat.toInt().coerceIn(0, trackA.size - 2)
    val local = legFloat - leg
    val pos = lerp(trackA[leg], trackA[leg + 1], local)
    drawRect(Palette.RoofRed, Offset(pos.x - 7f, pos.y - 8f), Size(14f, 7f))
    drawRect(Palette.Gold, Offset(pos.x - 7f, pos.y - 11f), Size(14f, 3f))
    drawRect(Palette.Outline, Offset(pos.x - 7f, pos.y - 1f), Size(14f, 2f))
}

private fun DrawScope.drawShopHut(structure: Structure, roof: Color, trim: Color) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)
    val walls = structure.item.blockHeight * 0.55f

    isoBlock(col, row, w, h, walls, Palette.WallCream, Palette.WallCream, Palette.WallCream)

    // Serving hatch on the front-left face.
    val hatchY = (base[3].y + base[2].y) / 2f - walls * 0.45f
    drawRect(Palette.Outline, Offset((base[3].x + base[2].x) / 2f - 9f, hatchY - 5f), Size(18f, 10f))

    val dy = walls
    pyramidRoof(
        Offset(base[0].x, base[0].y - dy),
        Offset(base[1].x, base[1].y - dy),
        Offset(base[2].x, base[2].y - dy),
        Offset(base[3].x, base[3].y - dy),
        apexHeight = structure.item.blockHeight * 0.75f,
        colorA = roof,
        colorB = trim
    )

    // Sign board over the hatch.
    val signY = base[3].y - walls - 4f
    drawRect(Palette.WoodDark, Offset((base[0].x + base[1].x) / 2f - 12f, signY - 12f), Size(24f, 10f))
    drawRect(Palette.Gold, Offset((base[0].x + base[1].x) / 2f - 10f, signY - 10f), Size(20f, 6f))
}

private fun DrawScope.drawFountain(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(col, row, w, h, 8f, Palette.WallStone, Palette.WallStone, Palette.Metal, outline = true)
    val pool = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 8f)
    diamond(pool, 0.8f, Palette.WaterMid)
    diamondStroke(pool, 0.8f, Palette.Outline)
    drawCircle(Palette.WallStone, 7f, pool)
    drawCircle(Palette.WaterLight, 3f, pool)

    // Spray, rising and falling with the shared animation phase.
    val jet = 14f + sin(structure.animAngle * PI.toFloat() / 90f) * 6f
    drawLine(Palette.WaterLight, pool, Offset(pool.x, pool.y - jet), strokeWidth = 2f)
    drawCircle(Palette.WaterLight, 2.5f, Offset(pool.x - 5f, pool.y - jet * 0.8f))
    drawCircle(Palette.WaterLight, 2.5f, Offset(pool.x + 5f, pool.y - jet * 0.8f))
}

private fun DrawScope.drawTree(structure: Structure) {
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    drawRect(Palette.Trunk, Offset(center.x - 2.5f, center.y - 16f), Size(5f, 15f))
    drawCircle(Palette.LeafDark, 11f, Offset(center.x, center.y - 18f))
    drawCircle(Palette.LeafMid, 10f, Offset(center.x - 3f, center.y - 21f))
    drawCircle(Palette.LeafLight, 6f, Offset(center.x - 5f, center.y - 24f))
}

private fun DrawScope.drawLamp(structure: Structure) {
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    drawRect(Palette.MetalDark, Offset(center.x - 1.5f, center.y - 40f), Size(3f, 40f))
    drawRect(Palette.Gold, Offset(center.x - 6f, center.y - 50f), Size(12f, 10f))
    drawRect(Palette.RoofCream, Offset(center.x - 4f, center.y - 48f), Size(8f, 6f))
}

private fun DrawScope.drawBench(structure: Structure) {
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    drawRect(Palette.WoodDark, Offset(center.x - 11f, center.y - 7f), Size(22f, 5f))
    drawRect(Palette.Wood, Offset(center.x - 11f, center.y - 12f), Size(22f, 4f))
    drawRect(Palette.MetalDark, Offset(center.x - 9f, center.y - 3f), Size(3f, 4f))
    drawRect(Palette.MetalDark, Offset(center.x + 6f, center.y - 3f), Size(3f, 4f))
}

private fun DrawScope.drawFlowers(structure: Structure, map: ParkMap) {
    val detail = map.detailAt(structure.col, structure.row)
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    val colors = listOf(Palette.RoofRed, Palette.Gold, Palette.RoofBlue, Color(0xFFE8459B))
    for (i in 0 until 5) {
        val fx = center.x + ((detail + i * 37) % 22) - 11f
        val fy = center.y + ((detail + i * 19) % 12) - 6f
        drawRect(colors[(detail + i) % colors.size], Offset(fx, fy), Size(3f, 3f))
    }
}

private fun DrawScope.drawSelection(structure: Structure) {
    for (c in structure.col..structure.maxCol) {
        for (r in structure.row..structure.maxRow) {
            diamondStroke(Iso.toScreen(c + 0.5f, r + 0.5f), 1.03f, Palette.Gold)
        }
    }
}

// ======================================================================
// Visitors
// ======================================================================

private fun DrawScope.drawVisitor(visitor: Visitor) {
    val base = Iso.toScreen(visitor.col, visitor.row)
    val bounce = sin(visitor.bob * PI.toFloat() * 0.9f) * 1.2f
    val footY = base.y + bounce

    drawOval(
        Palette.Shadow,
        topLeft = Offset(base.x - 6f, base.y - 3f),
        size = Size(12f, 5f)
    )

    val shirt = VisitorShirts[visitor.paletteIndex % VisitorShirts.size]
    val hair = VisitorHair[visitor.paletteIndex % VisitorHair.size]
    val hipY = footY - 11f

    // Legs, stepping in time with the walk cycle.
    val stride = if (visitor.state == VisitorState.WALKING) {
        sin(visitor.bob * 3f) * 2.5f
    } else {
        0f
    }
    drawRect(Palette.Outline, Offset(base.x - 3.5f + stride, hipY), Size(3f, 5f))
    drawRect(Palette.Outline, Offset(base.x + 0.5f - stride, hipY), Size(3f, 5f))

    // Torso.
    drawRect(shirt, Offset(base.x - 4.5f, hipY - 8f), Size(9f, 9f))
    drawRect(Palette.Outline, Offset(base.x - 4.5f, hipY - 1f), Size(9f, 1f))

    // Head.
    drawRect(Palette.Skin, Offset(base.x - 3.5f, hipY - 15f), Size(7f, 7f))
    drawRect(hair, Offset(base.x - 4f, hipY - 17f), Size(8f, 3f))

    // Unhappy guests get a little storm cloud so the park rating is readable visually.
    if (visitor.happiness < 0.3f) {
        drawRect(Palette.Outline, Offset(base.x - 2f, hipY - 23f), Size(4f, 4f))
        drawRect(Palette.RoofRed, Offset(base.x - 1f, hipY - 22f), Size(2f, 2f))
    }
}

// ======================================================================
// Entrance and build preview
// ======================================================================

private fun DrawScope.drawEntrance(state: GameState, textMeasurer: TextMeasurer) {
    val row = state.entrance.row.toFloat()
    val leftCol = state.entrance.col - 3f
    val rightCol = state.entrance.col + 2f

    val pillarHeight = 44f
    isoBlock(leftCol, row, 1f, 1f, pillarHeight, Palette.RoofCream, Palette.WallStone, Palette.RoofRed)
    isoBlock(rightCol, row, 1f, 1f, pillarHeight, Palette.RoofCream, Palette.WallStone, Palette.RoofRed)

    val leftTop = Iso.toScreen(leftCol + 0.5f, row + 0.5f)
    val rightTop = Iso.toScreen(rightCol + 0.5f, row + 0.5f)
    val barY = (leftTop.y + rightTop.y) / 2f - pillarHeight - 6f

    // Spanning arch with a gold sign board.
    drawRect(
        Palette.RoofRed,
        Offset(leftTop.x - 8f, barY - 16f),
        Size(rightTop.x - leftTop.x + 16f, 16f)
    )
    drawRect(
        Palette.Gold,
        Offset(leftTop.x - 4f, barY - 13f),
        Size(rightTop.x - leftTop.x + 8f, 10f)
    )
    putPoint(0, Offset(leftTop.x - 8f, barY - 16f))
    putPoint(1, Offset(rightTop.x + 8f, barY - 16f))
    putPoint(2, Offset(rightTop.x + 8f, barY))
    putPoint(3, Offset(leftTop.x - 8f, barY))
    outlineBuffered(4)

    val style = TextStyle(
        color = Palette.Outline,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold
    )
    val label = "WELCOME"
    val measured = textMeasurer.measure(label, style)
    drawText(
        textMeasurer = textMeasurer,
        text = label,
        topLeft = Offset(
            (leftTop.x + rightTop.x) / 2f - measured.size.width / 2f,
            barY - 13f
        ),
        style = style
    )
}

/** Tinted footprint squares so the player can see exactly which tiles will be affected. */
private fun DrawScope.drawGhostTiles(state: GameState, ghost: BuildGhost) {
    val verdict = placementFor(state, ghost.item, ghost.col, ghost.row)
    val valid = verdict == Placement.OK
    val tint = if (valid) Color(0x8033E05A) else Color(0x80E83333)

    val w = if (ghost.item.isTerrainBrush) 1 else ghost.item.wTiles
    val h = if (ghost.item.isTerrainBrush) 1 else ghost.item.hTiles

    for (c in ghost.col until ghost.col + w) {
        for (r in ghost.row until ghost.row + h) {
            if (!state.map.inBounds(c, r)) continue
            diamond(Iso.toScreen(c + 0.5f, r + 0.5f), 1.04f, tint)
        }
    }
}

private fun DrawScope.drawGhostPreview(state: GameState, ghost: BuildGhost) {
    if (ghost.item.isTerrainBrush) return
    isoBlock(
        ghost.col.toFloat(),
        ghost.row.toFloat(),
        ghost.item.wTiles.toFloat(),
        ghost.item.hTiles.toFloat(),
        ghost.item.blockHeight,
        Color(0x66FFFFFF),
        Color(0x33FFFFFF),
        Color(0x44FFFFFF),
        outline = true
    )
}

// ======================================================================
// Culling
// ======================================================================

/** Tile range that can touch the viewport, padded for tall structures. */
private fun visibleTileBounds(camera: Camera, viewport: Size, cols: Int, rows: Int): IntArray {
    var minCol = Float.MAX_VALUE
    var maxCol = -Float.MAX_VALUE
    var minRow = Float.MAX_VALUE
    var maxRow = -Float.MAX_VALUE

    val xs = floatArrayOf(0f, viewport.width, 0f, viewport.width)
    val ys = floatArrayOf(0f, 0f, viewport.height, viewport.height)
    for (i in 0 until 4) {
        val wx = camera.screenToWorldX(xs[i])
        val wy = camera.screenToWorldY(ys[i])
        val c = Iso.toCol(wx, wy)
        val r = Iso.toRow(wx, wy)
        minCol = min(minCol, c)
        maxCol = max(maxCol, c)
        minRow = min(minRow, r)
        maxRow = max(maxRow, r)
    }

    // Four tiles of slack absorbs the tallest ride and any rounding at the edges.
    val margin = 4
    return intArrayOf(
        max(0, floor(minCol).toInt() - margin),
        min(cols - 1, ceil(maxCol).toInt() + margin),
        max(0, floor(minRow).toInt() - margin),
        min(rows - 1, ceil(maxRow).toInt() + margin)
    )
}

/** Screen-space size of the park, used to clamp the camera. */
fun parkPixelSize(map: ParkMap): Size {
    val spanX = (map.cols + map.rows) * Iso.HALF_W
    val spanY = (map.cols + map.rows) * Iso.HALF_H
    return Size(spanX, spanY)
}
