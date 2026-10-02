package com.example.parkbuilder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.example.parkbuilder.game.Placement
import com.example.parkbuilder.game.placementFor
import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Iso
import com.example.parkbuilder.game.model.ParkMap
import com.example.parkbuilder.game.model.Structure
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.ToolCategory
import com.example.parkbuilder.game.model.Visitor
import com.example.parkbuilder.game.model.VisitorState
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Saturated, high-contrast palette in the spirit of the PS1 original. */
object Palette {
    val GrassA = Color(0xFF62B446)
    val GrassB = Color(0xFF56A63C)
    val GrassC = Color(0xFF6CBF4F)
    val GrassTuft = Color(0xFF3F8A2C)
    val GrassDark = Color(0xFF4A9032)
    val GrassLight = Color(0xFF7FCF5B)

    val PathA = Color(0xFFBCAB92)
    val PathB = Color(0xFFAE9C82)
    val PathSpeck = Color(0xFFCFC0A8)
    val PathEdge = Color(0xFF8A7860)
    val PathDark = Color(0xFF9C8A70)
    val Curb = Color(0xFF82705A)
    val CurbLight = Color(0xFFE2D4B6)

    val WaterDeep = Color(0xFF1B5EA6)
    val WaterMid = Color(0xFF2F82D4)
    val WaterLight = Color(0xFF63B4F0)
    val WaterDark = Color(0xFF144066)
    val WaterFoam = Color(0xFFAEE2FF)

    val BrickRed = Color(0xFFC4553A)
    val BrickRedDark = Color(0xFF8E3A26)
    val Mortar = Color(0xFFE6DCC0)
    val Asphalt = Color(0xFF4E4A52)
    val AsphaltDark = Color(0xFF38353C)
    val Glass = Color(0xFF2E5C8A)
    val GlassLight = Color(0xFF8CC0E8)
    val Hedge = Color(0xFF2E6B24)
    val HedgeLight = Color(0xFF51A038)

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

    // Flat ground first: no object ever needs to be occluded by terrain. Tiles outside the
    // buildable plot are still drawn, as meadow, so the frame is terrain edge to edge.
    for (col in bounds[0]..bounds[1]) {
        for (row in bounds[2]..bounds[3]) {
            if (map.inBounds(col, row)) drawGroundTile(map, col, row, state.gameTime)
            else drawMeadowTile(map, col, row)
        }
    }

    drawSurroundings(state, state.gameTime, textMeasurer)

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
            0 -> drawStructure(entry.structure!!, map, textMeasurer)
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

/**
 * Ground is drawn by blitting a generated texture, not by filling a polygon.
 *
 * That single change is most of the difference between "solid colours" and the original:
 * the grass becomes a dither of two greens with clumps and flowers in it, the paths become
 * slabs with joints and a raised kerb, the lakes get wave bands and a shoreline.
 */
private fun DrawScope.drawGroundTile(map: ParkMap, col: Int, row: Int, time: Float) {
    val center = Iso.toScreen(col + 0.5f, row + 0.5f)
    val topLeft = Offset(center.x - Iso.HALF_W, center.y - Iso.HALF_H)
    val size = Size(PixelArt.TILE_W.toFloat(), PixelArt.TILE_H.toFloat())
    val detail = map.detailAt(col, row)

    when (map.terrainAt(col, row)) {
        // Three-by-three patches share a texture, so the meadow reads as a field rather
        // than a grid of identical stamps.
        Terrain.GRASS -> blit(
            TextureAtlas.grass(map.detailAt(col - col.mod(3), row - row.mod(3)) % PixelArt.GRASS_VARIANTS),
            topLeft,
            size
        )

        Terrain.PATH -> blit(
            TextureAtlas.path(detail % PixelArt.PATH_VARIANTS, edgeMask(map, col, row, Terrain.PATH)),
            topLeft,
            size
        )

        Terrain.QUEUE -> blit(
            TextureAtlas.queue(queueRunsAlongCol(map, col, row)),
            topLeft,
            size
        )

        Terrain.WATER -> {
            blit(
                TextureAtlas.water(detail % PixelArt.WATER_VARIANTS, edgeMask(map, col, row, Terrain.WATER)),
                topLeft,
                size
            )
            // A travelling glint keeps the lakes alive without regenerating a texture.
            val shimmer = sin(time * 1.6f + (col + row) * 0.6f)
            if (shimmer > 0.72f) {
                drawRect(
                    Palette.WaterFoam.copy(alpha = 0.5f),
                    Offset(center.x - 9f, center.y - 1f),
                    Size(18f, 2f)
                )
            }
        }
    }
}

/** Nearest-neighbour blit: pixel art must not be smoothed. */
private fun DrawScope.blit(
    image: ImageBitmap,
    topLeft: Offset,
    size: Size,
    alpha: Float = 1f
) {
    drawImage(
        image = image,
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.None
    )
}

/** Which sides of a tile have no neighbour of the same surface — the sides that get a kerb. */
private fun edgeMask(map: ParkMap, col: Int, row: Int, same: Terrain): Int {
    var mask = 0
    if (map.terrainAt(col - 1, row) != same) mask = mask or PixelArt.EDGE_NW
    if (map.terrainAt(col, row - 1) != same) mask = mask or PixelArt.EDGE_NE
    if (map.terrainAt(col + 1, row) != same) mask = mask or PixelArt.EDGE_SE
    if (map.terrainAt(col, row + 1) != same) mask = mask or PixelArt.EDGE_SW
    return mask
}

/** A queue runs along columns when it has a queue neighbour to one side of it. */
private fun queueRunsAlongCol(map: ParkMap, col: Int, row: Int): Boolean {
    if (map.terrainAt(col + 1, row) == Terrain.QUEUE) return true
    if (map.terrainAt(col - 1, row) == Terrain.QUEUE) return true
    if (map.terrainAt(col, row + 1) == Terrain.QUEUE) return false
    if (map.terrainAt(col, row - 1) == Terrain.QUEUE) return false
    return true
}

// ======================================================================
// Textured faces
// ======================================================================

/** Scratch affine matrix: reused because a busy frame maps hundreds of faces. */
private val faceMatrix = Matrix()

/**
 * Maps a material [texture] across the parallelogram a→b→c→d.
 *
 * a→b is the texture's x axis and a→d its y axis, so a single affine matrix wraps the
 * bitmap exactly around an isometric face — no shearing tricks, no gaps. The texture is
 * repeated at [pixelScale] world pixels per texture pixel and clipped to the face, which
 * keeps bricks and shingles at a readable size on walls of any dimension.
 */
private fun DrawScope.texturedFace(
    a: Offset,
    b: Offset,
    c: Offset,
    d: Offset,
    texture: ImageBitmap,
    pixelScale: Float = 2f,
    tint: Color = Color.White,
    alpha: Float = 1f
) {
    val ux = b.x - a.x
    val uy = b.y - a.y
    val vx = d.x - a.x
    val vy = d.y - a.y
    val uLen = sqrt(ux * ux + uy * uy)
    val vLen = sqrt(vx * vx + vy * vy)
    // Wide bands — the road, the meadow apron — need a generous repeat count or the
    // texture is stretched into mush.
    val repeatsU = ceil(uLen / (texture.width * pixelScale)).toInt().coerceIn(1, 40)
    val repeatsV = ceil(vLen / (texture.height * pixelScale)).toInt().coerceIn(1, 40)

    faceMatrix.reset()
    faceMatrix[0, 0] = ux / (repeatsU * texture.width)
    faceMatrix[1, 0] = uy / (repeatsU * texture.width)
    faceMatrix[0, 1] = vx / (repeatsV * texture.height)
    faceMatrix[1, 1] = vy / (repeatsV * texture.height)
    faceMatrix[0, 3] = a.x
    faceMatrix[1, 3] = a.y

    shapePath.rewind()
    shapePath.moveTo(a.x, a.y)
    shapePath.lineTo(b.x, b.y)
    shapePath.lineTo(c.x, c.y)
    shapePath.lineTo(d.x, d.y)
    shapePath.close()

    clipPath(shapePath) {
        withTransform({ transform(faceMatrix) }) {
            val tw = texture.width.toFloat()
            val th = texture.height.toFloat()
            for (row in 0 until repeatsV) {
                for (column in 0 until repeatsU) {
                    drawImage(
                        image = texture,
                        dstOffset = IntOffset(column * texture.width, row * texture.height),
                        dstSize = IntSize(texture.width, texture.height),
                        alpha = alpha,
                        colorFilter = if (tint == Color.White) null else {
                            ColorFilter.tint(tint, BlendMode.Modulate)
                        },
                        filterQuality = FilterQuality.None
                    )
                }
            }
        }
    }
}

private fun DrawScope.diamond(center: Offset, expand: Float, fill: Color) {
    val hw = Iso.HALF_W * expand
    val hh = Iso.HALF_H * expand
    shapePath.rewind()
    shapePath.moveTo(center.x - hw, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y + hh)
    shapePath.lineTo(center.x - hw, center.y + hh)
    shapePath.close()
    drawPath(shapePath, fill)
}

private fun DrawScope.diamondStroke(center: Offset, expand: Float, color: Color) {
    val hw = Iso.HALF_W * expand
    val hh = Iso.HALF_H * expand
    shapePath.rewind()
    shapePath.moveTo(center.x - hw, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y - hh)
    shapePath.lineTo(center.x + hw, center.y + hh)
    shapePath.lineTo(center.x - hw, center.y + hh)
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
    outline: Boolean = true,
    topTex: ImageBitmap? = null,
    sideTex: ImageBitmap? = null,
    sideScale: Float = 2f
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

    // The flat fill above is kept underneath: the texture is laid over it, so any seam at
    // the very edge of a face shows a plausible wall tone instead of the sky.
    sideTex?.let { texture ->
        texturedFace(
            groundLeft, groundBottom, groundBottom + up, groundLeft + up,
            texture, sideScale, tint = FACE_DARK
        )
        texturedFace(
            groundBottom, groundRight, groundRight + up, groundBottom + up,
            texture, sideScale, tint = FACE_LIT
        )
    }
    topTex?.let { texture ->
        texturedFace(
            groundTop + up, groundRight + up, groundBottom + up, groundLeft + up,
            texture, sideScale
        )
    }

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

/** Multipliers used to fake the two-light setup: the left face sits in shade. */
private val FACE_DARK = Color(0.68f, 0.68f, 0.68f, 1f)
private val FACE_LIT = Color(0.88f, 0.88f, 0.88f, 1f)

/** Darkens a colour by [amount], for shaded faces and outlines. */
private fun shade(color: Color, amount: Float): Color = Color(
    (color.red * (1f - amount)).coerceIn(0f, 1f),
    (color.green * (1f - amount)).coerceIn(0f, 1f),
    (color.blue * (1f - amount)).coerceIn(0f, 1f),
    color.alpha
)

/** A point [u] of the way along a wall face, raised [up] pixels off the ground. */
private fun facePoint(from: Offset, to: Offset, u: Float, up: Float): Offset =
    Offset(from.x + (to.x - from.x) * u, from.y + (to.y - from.y) * u - up)

/**
 * A window, hatch or panel sitting flush on an isometric wall.
 *
 * Drawing it as a quad between two points on the wall's ground edge keeps the pane
 * following the wall's slant — an axis-aligned rectangle here reads as a sticker.
 */
private fun DrawScope.facePanel(
    from: Offset,
    to: Offset,
    u0: Float,
    u1: Float,
    up0: Float,
    up1: Float,
    fill: Color,
    frame: Color = Palette.Outline
) {
    val a = facePoint(from, to, u0, up1)
    val b = facePoint(from, to, u1, up1)
    val c = facePoint(from, to, u1, up0)
    val d = facePoint(from, to, u0, up0)
    quad(a, b, c, d, fill)
    putPoint(0, a)
    putPoint(1, b)
    putPoint(2, c)
    putPoint(3, d)
    outlineBuffered(4, width = 1f, color = frame)
}

/** A little wooden board with a word painted on it. */
private fun DrawScope.signBoard(
    center: Offset,
    width: Float,
    height: Float,
    text: String,
    measurer: TextMeasurer,
    board: Color = Palette.WoodDark
) {
    drawRect(board, Offset(center.x - width / 2f, center.y - height / 2f), Size(width, height))
    drawRect(
        Palette.Gold,
        Offset(center.x - width / 2f + 1f, center.y - height / 2f + 1f),
        Size(width - 2f, height - 2f)
    )
    val style = TextStyle(color = Palette.Outline, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    val measured = measurer.measure(text, style)
    drawText(
        textMeasurer = measurer,
        text = text,
        topLeft = Offset(
            center.x - measured.size.width / 2f,
            center.y - measured.size.height / 2f
        ),
        style = style
    )
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

private fun DrawScope.drawStructure(structure: Structure, map: ParkMap, measurer: TextMeasurer) {
    // Ground shadow under the whole footprint.
    val shadowCenter = Iso.toScreen(structure.centerCol(), structure.centerRow())
    diamond(shadowCenter, 1.0f, Palette.Shadow)

    when (structure.item) {
        BuildItem.CAROUSEL -> drawCarousel(structure)
        BuildItem.FERRIS_WHEEL -> drawFerrisWheel(structure)
        BuildItem.DODGEMS -> drawDodgems(structure)
        BuildItem.LOG_FLUME -> drawLogFlume(structure)
        BuildItem.ROLLER_COASTER -> drawRollerCoaster(structure)
        BuildItem.BURGER_BAR -> drawShopHut(structure, Palette.RoofRed, measurer)
        BuildItem.SODA_STAND -> drawShopHut(structure, Palette.RoofBlue, measurer)
        BuildItem.ICE_CREAM -> drawShopHut(structure, Palette.RoofTeal, measurer)
        BuildItem.GIFT_SHOP -> drawShopHut(structure, Palette.RoofBlue, measurer)
        BuildItem.RESTROOM -> drawShopHut(structure, Palette.WallStone, measurer)
        BuildItem.FOUNTAIN -> drawFountain(structure)
        BuildItem.TREE -> drawTree(structure, map)
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
        outline = true,
        sideTex = TextureAtlas.siding(Palette.WallCream),
        sideScale = 1.6f
    )

    val deck = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - deckHeight)

    // Riding horses on brass poles, spinning with animAngle.
    rotate(degrees = structure.animAngle, pivot = deck) {
        for (i in 0 until 6) {
            val horseAngle = i * 60.0 * PI / 180.0
            val hx = deck.x + (cos(horseAngle) * 24f).toFloat()
            val hy = deck.y + (sin(horseAngle) * 12f).toFloat()
            drawRect(Palette.Gold, Offset(hx - 1f, hy - 24f), Size(2f, 26f))
            drawRect(Palette.WallCream, Offset(hx - 4f, hy - 13f), Size(9f, 6f))
            drawRect(Palette.WallPink, Offset(hx - 4f, hy - 13f), Size(9f, 2f))
            drawRect(Palette.WallCream, Offset(hx + 3f, hy - 17f), Size(3f, 4f))
            drawRect(Palette.WoodDark, Offset(hx - 4f, hy - 7f), Size(2f, 7f))
            drawRect(Palette.WoodDark, Offset(hx + 3f, hy - 7f), Size(2f, 7f))
        }
    }

    val top = Offset(base[0].x + (base[2].x - base[0].x) / 2f, base[0].y - deckHeight)
    val right = Offset(base[1].x, base[1].y - deckHeight)
    val bottom = Offset(base[2].x, base[2].y - deckHeight)
    val left = Offset(base[3].x, base[3].y - deckHeight)

    // Striped big-top canopy. More slices than a hut roof, because it is a marquee.
    pyramidRoof(
        top, right, bottom, left,
        apexHeight = structure.item.blockHeight,
        colorA = Palette.RoofRed,
        colorB = Palette.RoofCream,
        slices = 8
    )

    // Scalloped valance hanging off the eaves.
    val stripes = TextureAtlas.stripes(Palette.RoofRed, Palette.RoofCream)
    texturedFace(left, bottom, Offset(bottom.x, bottom.y - 4f), Offset(left.x, left.y - 4f), stripes, 2f)
    texturedFace(bottom, right, Offset(right.x, right.y - 4f), Offset(bottom.x, bottom.y - 4f), stripes, 2f)

    // Finial.
    val apexGround = Offset((top.x + bottom.x) / 2f, (top.y + bottom.y) / 2f)
    drawRect(
        Palette.Gold,
        Offset(apexGround.x - 1.5f, apexGround.y - structure.item.blockHeight - 9f),
        Size(3f, 10f)
    )
    drawCircle(Palette.Gold, 3f, Offset(apexGround.x, apexGround.y - structure.item.blockHeight - 11f))
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
        outline = true,
        topTex = TextureAtlas.plates(Palette.MetalDark),
        sideTex = TextureAtlas.plates(Palette.WallStone)
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
            // Two-tone gondola: paint the body every other arm so the wheel reads as it turns.
            val body = if (i % 2 == 0) Palette.RoofRed else Palette.RoofBlue
            drawRect(body, Offset(tip.x - 5f, tip.y - 6f), Size(10f, 11f))
            drawRect(shade(body, 0.35f), Offset(tip.x - 5f, tip.y + 3f), Size(10f, 2f))
            drawRect(Palette.GlassLight, Offset(tip.x - 3f, tip.y - 4f), Size(6f, 4f))
            drawRect(Palette.Outline, Offset(tip.x - 6f, tip.y - 7f), Size(12f, 1.5f))
        }
    }
    // Hub bolts, so the centre is not a plain disc.
    for (i in 0 until 6) {
        val a = i * 60.0 * PI / 180.0
        drawCircle(
            Palette.Gold, 1.6f,
            Offset(hub.x + (cos(a) * 9f).toFloat(), hub.y + (sin(a) * 9f).toFloat())
        )
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
        outline = true,
        sideTex = TextureAtlas.plates(Palette.MetalDark)
    )
    val floor = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 8f)
    diamond(floor, 0.78f, Palette.RoofTeal)
    diamondStroke(floor, 0.78f, Palette.Outline)

    rotate(degrees = structure.animAngle, pivot = floor) {
        for (i in 0 until 5) {
            val a = i * 72.0 * PI / 180.0
            val cx = floor.x + (cos(a) * 18f).toFloat()
            val cy = floor.y + (sin(a) * 9f).toFloat()
            val body = if (i % 2 == 0) Palette.Gold else Palette.RoofBlue
            drawRect(body, Offset(cx - 4f, cy - 11f), Size(8f, 8f))
            drawRect(Palette.GlassLight, Offset(cx - 3f, cy - 10f), Size(6f, 3f))
            drawRect(Palette.Outline, Offset(cx - 4f, cy - 3f), Size(8f, 2f))
        }
    }

    // Pole ring holding up a striped canopy fringe.
    for (i in 0 until 6) {
        val a = i * 60.0 * PI / 180.0
        val px = floor.x + (cos(a) * 28f).toFloat()
        val py = floor.y + (sin(a) * 14f).toFloat()
        drawRect(Palette.Gold, Offset(px - 1.5f, py - 18f), Size(3f, 18f))
        drawRect(Palette.RoofRed, Offset(px - 3f, py - 21f), Size(6f, 3f))
    }
    val fringe = TextureAtlas.stripes(Palette.RoofRed, Palette.RoofCream)
    texturedFace(
        Offset(base[3].x, base[3].y - 8f), Offset(base[2].x, base[2].y - 8f),
        Offset(base[2].x, base[2].y - 12f), Offset(base[3].x, base[3].y - 12f),
        fringe, 2f
    )
}

private fun DrawScope.drawLogFlume(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(
        col, row, w, h, 6f,
        Palette.Wood, Palette.WoodDark, Palette.Wood,
        outline = true,
        sideTex = TextureAtlas.planks(Palette.Wood)
    )

    val deck = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 6f)
    diamond(deck, 0.86f, Palette.WaterMid)
    diamondStroke(deck, 0.86f, Palette.Outline)
    // Foam along the channel edge.
    diamondStroke(deck, 0.82f, Palette.WaterFoam)

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

    // Log flume drop tower, with cross-bracing so it reads as timber.
    val towerCenter = Iso.toScreen(structure.col + 0.4f, structure.row + 0.4f)
    val towerH = structure.item.blockHeight
    drawRect(Palette.Wood, Offset(towerCenter.x - 7f, towerCenter.y - towerH), Size(14f, towerH))
    drawRect(Palette.WoodDark, Offset(towerCenter.x + 2f, towerCenter.y - towerH), Size(5f, towerH))
    var brace = 0f
    while (brace < towerH - 6f) {
        drawLine(
            Palette.WoodDark,
            Offset(towerCenter.x - 7f, towerCenter.y - brace),
            Offset(towerCenter.x + 6f, towerCenter.y - brace - 7f),
            strokeWidth = 1.4f
        )
        brace += 9f
    }
    drawRect(Palette.RoofRed, Offset(towerCenter.x - 10f, towerCenter.y - towerH - 8f), Size(20f, 8f))
    drawRect(Palette.RoofCream, Offset(towerCenter.x - 10f, towerCenter.y - towerH - 8f), Size(20f, 2.5f))
}

private fun DrawScope.drawRollerCoaster(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(
        col, row, w, h, 5f,
        Palette.PathA, Palette.PathEdge, Palette.PathB,
        outline = true,
        topTex = TextureAtlas.path(1, 0),
        sideTex = TextureAtlas.bricks(Palette.BrickRed, Palette.Mortar),
        sideScale = 1.6f
    )

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
            drawLine(Palette.Wood, track[i], track[i + 1], strokeWidth = 4f)
            drawLine(Palette.Metal, track[i], track[i + 1], strokeWidth = 1.6f)
            // Cross ties marching along the rail — the detail that makes it a track.
            val segments = 4
            for (s in 0 until segments) {
                val tie = lerp(track[i], track[i + 1], (s + 0.5f) / segments)
                drawRect(Palette.WoodDark, Offset(tie.x - 3.5f, tie.y - 1f), Size(7f, 2.5f))
            }
            // Support posts down to the ground.
            val mid = lerp(track[i], track[i + 1], 0.5f)
            drawRect(Palette.WoodDark, Offset(mid.x - 1.2f, mid.y), Size(2.4f, 16f))
        }
    }

    // Train of three cars running the first track.
    val t = (structure.animAngle / 360f)
    for (car in 0 until 3) {
        val legFloat = (t * (trackA.size - 1) - car * 0.16f)
        if (legFloat < 0f) continue
        val leg = legFloat.toInt().coerceIn(0, trackA.size - 2)
        val local = legFloat - leg
        val pos = lerp(trackA[leg], trackA[leg + 1], local)
        val body = if (car == 0) Palette.RoofRed else Palette.RoofBlue
        drawRect(body, Offset(pos.x - 7f, pos.y - 8f), Size(14f, 7f))
        drawRect(Palette.GlassLight, Offset(pos.x - 5f, pos.y - 7f), Size(10f, 3f))
        drawRect(Palette.Gold, Offset(pos.x - 7f, pos.y - 11f), Size(14f, 3f))
        drawRect(Palette.Outline, Offset(pos.x - 7f, pos.y - 1f), Size(14f, 2f))
    }

    // Pennants on the lift hill.
    val flag = TextureAtlas.flag(Palette.RoofRed)
    blit(
        flag,
        Offset(station.x - halfW * 0.35f, station.y - peak - flag.height.toFloat()),
        Size(flag.width.toFloat(), flag.height.toFloat())
    )
}

/**
 * A shop: stone plinth, boarded walls with windows, a serving hatch under a striped
 * awning, a tiled roof and — the detail that sells it — the thing it sells sat on top.
 */
private fun DrawScope.drawShopHut(structure: Structure, roof: Color, measurer: TextMeasurer) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)
    val item = structure.item
    val walls = item.blockHeight * 0.5f
    val plinth = 4f
    val wallTop = plinth + walls

    // 1. Stone plinth, so the hut does not look glued to the grass.
    isoBlock(
        col, row, w, h, plinth,
        Palette.WallStone, Palette.WallStone, Palette.WallStone,
        outline = true,
        sideTex = TextureAtlas.plates(Palette.WallStone)
    )

    // 2. Boarded walls.
    isoBlock(
        col, row, w, h, wallTop,
        Palette.WallCream, Palette.WallCream, Palette.WallCream,
        outline = false,
        sideTex = TextureAtlas.siding(Palette.WallCream)
    )

    // 3. Serving hatch on the shaded face, with a counter to lean on.
    facePanel(
        base[3], base[2], 0.18f, 0.82f,
        up0 = 12f, up1 = 22f,
        fill = Palette.Outline,
        frame = Palette.WoodDark
    )
    facePanel(
        base[3], base[2], 0.16f, 0.84f,
        up0 = 10f, up1 = 12f,
        fill = Palette.Wood, frame = Palette.WoodDark
    )
    // Striped awning over the counter, in the shop's own colours.
    val awning = TextureAtlas.stripes(roof, Palette.RoofCream)
    texturedFace(
        base[3], base[2],
        Offset(base[2].x, base[2].y - wallTop - 4f),
        Offset(base[3].x, base[3].y - wallTop - 4f),
        awning, pixelScale = 2.5f
    )
    quad(
        facePoint(base[3], base[2], 0f, wallTop + 4f),
        facePoint(base[3], base[2], 1f, wallTop + 4f),
        facePoint(base[3], base[2], 1f, wallTop + 6f),
        facePoint(base[3], base[2], 0f, wallTop + 6f),
        shade(roof, 0.3f)
    )

    // 4. Window on the lit face.
    facePanel(
        base[2], base[1], 0.22f, 0.5f,
        up0 = 14f, up1 = 26f,
        fill = Palette.GlassLight, frame = Palette.WoodDark
    )
    facePanel(
        base[2], base[1], 0.56f, 0.84f,
        up0 = 14f, up1 = 26f,
        fill = Palette.Glass, frame = Palette.WoodDark
    )

    // 5. Tiled hip roof with a fascia board under the eaves.
    val dy = wallTop
    val roofTop = Offset(base[0].x, base[0].y - dy)
    val roofRight = Offset(base[1].x, base[1].y - dy)
    val roofBottom = Offset(base[2].x, base[2].y - dy)
    val roofLeft = Offset(base[3].x, base[3].y - dy)
    val apexHeight = item.blockHeight * 0.7f
    pyramidRoof(
        roofTop, roofRight, roofBottom, roofLeft,
        apexHeight = apexHeight,
        colorA = roof,
        colorB = shade(roof, 0.22f),
        slices = 6
    )
    val apexGround = Offset(
        (roofTop.x + roofBottom.x) / 2f,
        (roofTop.y + roofBottom.y) / 2f
    )
    // Shingle eaves: one textured band along each visible edge.
    texturedFace(
        roofLeft, roofBottom,
        Offset(roofBottom.x, roofBottom.y - 5f),
        Offset(roofLeft.x, roofLeft.y - 5f),
        TextureAtlas.shingles(roof), pixelScale = 1.5f
    )
    texturedFace(
        roofBottom, roofRight,
        Offset(roofRight.x, roofRight.y - 5f),
        Offset(roofBottom.x, roofBottom.y - 5f),
        TextureAtlas.shingles(roof), pixelScale = 1.5f
    )

    // 6. Name board across the front of the roof.
    signBoard(
        Offset(apexGround.x, roofBottom.y - 3f),
        width = w * Iso.TILE_W * 0.42f,
        height = 11f,
        text = item.displayName.uppercase().take(10),
        measurer = measurer
    )

    // 7. The product, hoisted onto the ridge and bobbing gently.
    val prop = TextureAtlas.roofProp(item)
    val bob = sin(structure.ageSeconds * 1.3f) * 1.5f
    blit(
        prop,
        Offset(
            apexGround.x - prop.width / 2f,
            apexGround.y - apexHeight - prop.height + 2f - bob
        ),
        Size(prop.width.toFloat(), prop.height.toFloat())
    )
}

private fun DrawScope.drawFountain(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)

    isoBlock(
        col, row, w, h, 8f,
        Palette.WallStone, Palette.WallStone, Palette.Metal,
        outline = true,
        sideTex = TextureAtlas.bricks(Palette.WallStone, Palette.Mortar),
        sideScale = 1.4f
    )
    val pool = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - 8f)
    diamond(pool, 0.8f, Palette.WaterMid)
    diamondStroke(pool, 0.8f, Palette.Outline)
    // Ripples, then the pedestal.
    diamondStroke(pool, 0.6f, Palette.WaterLight)
    drawCircle(Palette.WallStone, 7f, pool)
    drawCircle(shade(Palette.WallStone, 0.3f), 7f, pool, style = Stroke(width = 1.4f))
    drawCircle(Palette.WaterLight, 3.5f, pool)

    // Spray, rising and falling with the shared animation phase.
    val jet = 16f + sin(structure.animAngle * PI.toFloat() / 90f) * 6f
    drawLine(Palette.WaterLight, pool, Offset(pool.x, pool.y - jet), strokeWidth = 2f)
    drawCircle(Palette.WaterFoam, 3f, Offset(pool.x, pool.y - jet))
    drawCircle(Palette.WaterLight, 2.5f, Offset(pool.x - 6f, pool.y - jet * 0.8f))
    drawCircle(Palette.WaterLight, 2.5f, Offset(pool.x + 6f, pool.y - jet * 0.8f))
    drawCircle(Palette.WaterFoam, 1.5f, Offset(pool.x - 9f, pool.y - jet * 0.5f))
    drawCircle(Palette.WaterFoam, 1.5f, Offset(pool.x + 9f, pool.y - jet * 0.5f))
}

/**
 * A tree, with dithered foliage rather than three clean circles: the speckle is what makes
 * a canopy read as leaves at this size.
 */
private fun DrawScope.drawTree(structure: Structure, map: ParkMap) {
    treeArt(
        Iso.toScreen(structure.centerCol(), structure.centerRow()),
        map.detailAt(structure.col, structure.row)
    )
}

/** Shared tree art, so the countryside and the park use exactly the same trees. */
private fun DrawScope.treeArt(center: Offset, seedValue: Int) {
    val detail = seedValue
    val lean = (detail % 5) - 2f
    val crown = 10f + (detail % 3)
    val cx = center.x + lean
    val cy = center.y - 20f

    drawRect(Palette.Trunk, Offset(center.x - 2.5f, center.y - 16f), Size(5f, 16f))
    drawRect(shade(Palette.Trunk, 0.35f), Offset(center.x + 1f, center.y - 16f), Size(2f, 16f))

    drawCircle(Palette.LeafDark, crown, Offset(cx, cy))
    drawCircle(Palette.LeafMid, crown * 0.86f, Offset(cx - 1.5f, cy - 2f))
    drawCircle(Palette.LeafLight, crown * 0.44f, Offset(cx - 3.5f, cy - 5f))
    for (i in 0 until 8) {
        val angle = (detail + i * 53) % 360 * PI / 180.0
        val r = crown * (0.25f + (i % 3) * 0.24f)
        val sx = cx + (cos(angle) * r).toFloat()
        val sy = cy + (sin(angle) * r * 0.8f).toFloat()
        drawRect(
            if (i % 2 == 0) Palette.LeafDark.copy(alpha = 0.85f) else Palette.LeafLight.copy(alpha = 0.7f),
            Offset(sx, sy),
            Size(2.5f, 2.5f)
        )
    }
    drawCircle(Palette.Outline.copy(alpha = 0.4f), crown, Offset(cx, cy), style = Stroke(width = 1f))
}

private fun DrawScope.drawLamp(structure: Structure) {
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    val shade = shade(Palette.MetalDark, 0.3f)
    drawRect(Palette.MetalDark, Offset(center.x - 1.5f, center.y - 38f), Size(3f, 38f))
    drawRect(shade, Offset(center.x + 0.5f, center.y - 38f), Size(1f, 38f))
    drawRect(Palette.MetalDark, Offset(center.x - 5f, center.y - 40f), Size(10f, 2.5f))
    // Lantern: brass frame with a warm pane.
    drawRect(Palette.Gold, Offset(center.x - 5f, center.y - 52f), Size(10f, 12f))
    drawRect(Color(0xFFFFE9A8), Offset(center.x - 3.5f, center.y - 50.5f), Size(7f, 9f))
    drawRect(Palette.Outline, Offset(center.x - 3.5f, center.y - 44.5f), Size(7f, 1.5f))
    drawRect(Palette.Gold, Offset(center.x - 3f, center.y - 56f), Size(6f, 4f))
    drawCircle(Color(0x44FFE9A8), 9f, Offset(center.x, center.y - 46f))
}

private fun DrawScope.drawBench(structure: Structure) {
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    // Iron ends first, timber slats over the top.
    drawRect(Palette.MetalDark, Offset(center.x - 10f, center.y - 4f), Size(3f, 6f))
    drawRect(Palette.MetalDark, Offset(center.x + 7f, center.y - 4f), Size(3f, 6f))
    drawRect(Palette.WoodDark, Offset(center.x - 11f, center.y - 8f), Size(22f, 4f))
    drawRect(Palette.Wood, Offset(center.x - 11f, center.y - 12f), Size(22f, 3f))
    drawRect(shade(Palette.Wood, 0.25f), Offset(center.x - 11f, center.y - 9.5f), Size(22f, 1.5f))
    drawRect(Palette.Wood, Offset(center.x - 10f, center.y - 15f), Size(20f, 3f))
    drawRect(Palette.Outline, Offset(center.x - 11f, center.y - 8f), Size(22f, 1f), alpha = 0.35f)
}

private fun DrawScope.drawFlowers(structure: Structure, map: ParkMap) {
    val detail = map.detailAt(structure.col, structure.row)
    val center = Iso.toScreen(structure.centerCol(), structure.centerRow())
    val petals = listOf(Palette.RoofRed, Palette.Gold, Palette.RoofBlue, Color(0xFFE8459B), Color(0xFFF6E3B0))

    // A turned earth bed with a stone lip.
    diamond(center, 0.72f, Color(0xFF6B4A2C))
    diamondStroke(center, 0.72f, Palette.WallStone)
    for (i in 0 until 7) {
        val angle = (detail + i * 47) % 360 * PI / 180.0
        val radius = 6f + (i % 3) * 5f
        val fx = center.x + (cos(angle) * radius).toFloat()
        val fy = center.y + (sin(angle) * radius * 0.6f).toFloat()
        drawRect(Palette.LeafMid, Offset(fx - 0.5f, fy), Size(1.5f, 3f))
        drawRect(petals[(detail + i) % petals.size], Offset(fx - 1.5f, fy - 3f), Size(3.5f, 3.5f))
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

    // Torso with a collar and swinging arms.
    drawRect(shirt, Offset(base.x - 4.5f, hipY - 8f), Size(9f, 9f))
    drawRect(shade(shirt, 0.28f), Offset(base.x - 4.5f, hipY - 1f), Size(9f, 1.5f))
    drawRect(Palette.RoofCream, Offset(base.x - 2f, hipY - 8f), Size(4f, 1.5f))
    drawRect(
        shade(shirt, 0.2f),
        Offset(base.x - 6.5f, hipY - 7f + stride * 0.5f),
        Size(2.5f, 6f)
    )
    drawRect(
        shade(shirt, 0.2f),
        Offset(base.x + 4f, hipY - 7f - stride * 0.5f),
        Size(2.5f, 6f)
    )

    // Head: hair, then a couple of pixels of face so guests read as people.
    drawRect(Palette.Skin, Offset(base.x - 3.5f, hipY - 15f), Size(7f, 7f))
    drawRect(hair, Offset(base.x - 4f, hipY - 17f), Size(8f, 4f))
    drawRect(Palette.Outline, Offset(base.x - 2.5f, hipY - 12f), Size(1.5f, 1.5f))
    drawRect(Palette.Outline, Offset(base.x + 1f, hipY - 12f), Size(1.5f, 1.5f))

    // Unhappy guests get a little storm cloud so the park rating is readable visually.
    if (visitor.happiness < 0.3f) {
        drawRect(Palette.Outline, Offset(base.x - 2f, hipY - 23f), Size(4f, 4f))
        drawRect(Palette.RoofRed, Offset(base.x - 1f, hipY - 22f), Size(2f, 2f))
    }
}

// ======================================================================
// Entrance and build preview
// ======================================================================

/**
 * The park gate: brick piers, a banner reading the park's name, bunting, ticket windows
 * and turnstiles. It is the first thing the player sees and it sets the tone.
 */
private fun DrawScope.drawEntrance(state: GameState, textMeasurer: TextMeasurer) {
    val row = state.entrance.row.toFloat()
    val leftCol = state.entrance.col - 3f
    val rightCol = state.entrance.col + 2f
    val pillarHeight = 46f
    val brick = TextureAtlas.bricks(Palette.BrickRed, Palette.Mortar)

    listOf(leftCol, rightCol).forEach { col ->
        isoBlock(
            col, row, 1f, 1f, pillarHeight,
            Palette.RoofCream, Palette.BrickRedDark, Palette.BrickRed,
            outline = true,
            sideTex = brick
        )
        val top = Iso.toScreen(col + 0.5f, row + 0.5f)
        // Stone cap and a lantern.
        drawRect(Palette.WallStone, Offset(top.x - 12f, top.y - pillarHeight - 5f), Size(24f, 5f))
        drawRect(Palette.Gold, Offset(top.x - 8f, top.y - pillarHeight - 10f), Size(16f, 5f))
        drawRect(Color(0xFFFFE9A8), Offset(top.x - 5f, top.y - pillarHeight - 9f), Size(10f, 4f))
    }

    val leftTop = Iso.toScreen(leftCol + 0.5f, row + 0.5f)
    val rightTop = Iso.toScreen(rightCol + 0.5f, row + 0.5f)
    val barY = (leftTop.y + rightTop.y) / 2f - pillarHeight - 6f
    val barLeft = leftTop.x - 9f
    val barWidth = rightTop.x - leftTop.x + 18f

    // Banner: royal blue board, gold inner panel, dark trim underneath.
    val bannerBlue = Color(0xFF1B3882)
    drawRect(bannerBlue, Offset(barLeft, barY - 21f), Size(barWidth, 21f))
    drawRect(shade(bannerBlue, 0.35f), Offset(barLeft, barY - 3f), Size(barWidth, 3f))
    drawRect(Palette.Gold, Offset(barLeft + 3f, barY - 18f), Size(barWidth - 6f, 15f))
    drawRect(bannerBlue, Offset(barLeft + 4f, barY - 17f), Size(barWidth - 8f, 13f))
    putPoint(0, Offset(barLeft, barY - 21f))
    putPoint(1, Offset(barLeft + barWidth, barY - 21f))
    putPoint(2, Offset(barLeft + barWidth, barY))
    putPoint(3, Offset(barLeft, barY))
    outlineBuffered(4)

    val style = TextStyle(color = Palette.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val label = state.parkName.uppercase()
    val measured = textMeasurer.measure(label, style)
    drawText(
        textMeasurer = textMeasurer,
        text = label,
        topLeft = Offset((leftTop.x + rightTop.x) / 2f - measured.size.width / 2f, barY - 16f),
        style = style
    )

    // Bunting strung between the piers, plus pennants on the pier caps.
    val flagA = TextureAtlas.flag(Palette.RoofRed)
    val flagB = TextureAtlas.flag(Palette.RoofBlue)
    for (i in 0 until 7) {
        val t = (i + 0.5f) / 7f
        val x = barLeft + barWidth * t
        val y = barY - 23f - sin(t * PI.toFloat()) * 5f
        val flag = if (i % 2 == 0) flagA else flagB
        blit(
            flag,
            Offset(x - flag.width / 2f, y),
            Size(flag.width.toFloat(), flag.height.toFloat())
        )
    }
    listOf(leftTop, rightTop).forEach { top ->
        blit(
            flagA,
            Offset(top.x - flagA.width / 2f, top.y - pillarHeight - 22f),
            Size(flagA.width.toFloat(), flagA.height.toFloat())
        )
    }

    // Ticket booths with serving windows, either side of the turnstiles.
    listOf(leftCol + 1f, rightCol - 1f).forEach { col ->
        isoBlock(
            col, row, 1f, 1f, 20f,
            Palette.RoofCream, Palette.WallCream, Palette.WallCream,
            outline = true,
            sideTex = TextureAtlas.siding(Palette.WallCream)
        )
        val front = Iso.toScreen(col + 1f, row + 1f)
        facePanel(front, Offset(front.x + 30f, front.y - 16f), 0.25f, 0.75f, 8f, 16f, Palette.GlassLight, Palette.WoodDark)
        drawRect(Palette.RoofRed, Offset(front.x - 4f, front.y - 28f), Size(34f, 9f))
    }

    // Turnstiles: little posts the guests file through.
    val gateCentre = Iso.toScreen(state.entrance.col + 0.5f, row + 1f)
    for (i in 0 until 3) {
        val x = gateCentre.x - 18f + i * 18f
        drawRect(Palette.Metal, Offset(x - 1.5f, gateCentre.y - 16f), Size(3f, 16f))
        drawRect(Palette.MetalDark, Offset(x - 5f, gateCentre.y - 19f), Size(10f, 4f))
    }
}

/**
 * Everything south of the gate: verge, hedge, tarmac road with a bus doing laps, and a
 * couple of parked cars. This is the strip along the bottom of the reference screenshot.
 */
private fun DrawScope.drawSurroundings(state: GameState, time: Float, textMeasurer: TextMeasurer) {
    val map = state.map
    val span = (map.cols + map.rows).toFloat()
    val tFrom = -span * 0.95f
    val tTo = span * 0.95f
    val parkDepth = (map.cols - 1 + map.rows - 1).toFloat()
    val hedgeDepth = parkDepth + 2.5f
    val roadDepth = hedgeDepth + 2f
    val roadDeep = roadDepth + 4.5f

    // The road runs along a line of constant *depth* (col + row). That is the direction
    // that comes out horizontal on screen; a band of tile rows comes out as a diagonal
    // streak, which is what the first attempt at this looked like.
    fun at(t: Float, depth: Float) = Offset(t * Iso.HALF_W, depth * Iso.HALF_H)

    // Clipped hedge behind the road, the way the reference borders its park.
    var t = tFrom
    while (t < tTo) {
        val p = at(t, hedgeDepth)
        drawCircle(Palette.Hedge, 9f, Offset(p.x, p.y - 6f))
        drawCircle(Palette.HedgeLight, 5f, Offset(p.x - 3f, p.y - 9f))
        drawRect(Palette.Outline.copy(alpha = 0.22f), Offset(p.x - 9f, p.y - 1f), Size(18f, 2f))
        t += 3f
    }

    // Tarmac, with a kerb down each side and a dashed centre line.
    texturedFace(
        at(tFrom, roadDepth), at(tTo, roadDepth),
        at(tTo, roadDeep), at(tFrom, roadDeep),
        TextureAtlas.asphalt(), pixelScale = 4f
    )
    drawLine(Palette.CurbLight, at(tFrom, roadDepth), at(tTo, roadDepth), strokeWidth = 1.5f)
    drawLine(Palette.CurbLight, at(tFrom, roadDeep), at(tTo, roadDeep), strokeWidth = 1.5f)

    val centreDepth = (roadDepth + roadDeep) / 2f
    var dash = tFrom
    while (dash < tTo) {
        drawLine(
            Color(0xFFE9E4D0),
            at(dash, centreDepth),
            at(dash + 2.2f, centreDepth),
            strokeWidth = 2.5f
        )
        dash += 5f
    }

    // "BUS ➔" road marking on tarmac
    val busSignStyle = TextStyle(color = Color(0xFFE9E4D0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val busText = "BUS ➔"
    val busMeasured = textMeasurer.measure(busText, busSignStyle)
    val busMarkPos = at(0.12f, centreDepth)
    drawText(
        textMeasurer = textMeasurer,
        text = busText,
        topLeft = Offset(busMarkPos.x - busMeasured.size.width / 2f, busMarkPos.y - busMeasured.size.height / 2f),
        style = busSignStyle
    )

    // The bus, doing laps past the gate, and a couple of parked cars behind it.
    val bus = TextureAtlas.bus(Palette.RoofRed, Palette.RoofCream)
    val busT = tFrom + (tTo - tFrom) * ((time * 0.035f) % 1f)
    val busBase = at(busT, roadDeep - 1.4f)
    blit(
        bus,
        Offset(busBase.x - bus.width / 2f, busBase.y - bus.height + 5f),
        Size(bus.width.toFloat(), bus.height.toFloat())
    )

    val car = TextureAtlas.car(Palette.RoofBlue)
    listOf(-10f, 6f, 22f).forEach { offset ->
        val parked = at(offset, roadDepth + 1.2f)
        blit(
            car,
            Offset(parked.x - car.width / 2f, parked.y - car.height + 4f),
            Size(car.width.toFloat(), car.height.toFloat())
        )
    }
}

/**
 * Ground outside the buildable plot: meadow, with trees and bushes thinning out with
 * distance. The reference's frame is terrain from edge to edge, and this is what gets us
 * there — the park stops being a diamond floating in a void.
 */
private fun DrawScope.drawMeadowTile(map: ParkMap, col: Int, row: Int) {
    val center = Iso.toScreen(col + 0.5f, row + 0.5f)
    val topLeft = Offset(center.x - Iso.HALF_W, center.y - Iso.HALF_H)
    val size = Size(PixelArt.TILE_W.toFloat(), PixelArt.TILE_H.toFloat())
    blit(
        TextureAtlas.grass(PixelArt.hash(col - col.mod(3), row - row.mod(3), 17) % PixelArt.GRASS_VARIANTS),
        topLeft,
        size
    )

    val dx = when {
        col < 0 -> -col
        col >= map.cols -> col - map.cols + 1
        else -> 0
    }
    val dy = when {
        row < 0 -> -row
        row >= map.rows -> row - map.rows + 1
        else -> 0
    }
    val outside = max(dx, dy)
    if (outside > MEADOW_REACH - 4) return

    val detail = PixelArt.hash(col, row, 53)
    when {
        // A wood just beyond the fence, then bushes, then the odd flower bed.
        outside <= 10 && detail % 6 == 0 -> treeArt(center, detail)
        outside <= 10 && detail % 11 == 0 -> {
            drawCircle(Palette.Hedge, 7f, Offset(center.x, center.y - 5f))
            drawCircle(Palette.HedgeLight, 4f, Offset(center.x - 2f, center.y - 7f))
        }

        detail % 29 == 0 -> {
            drawRect(Palette.LeafMid, Offset(center.x - 1f, center.y - 2f), Size(2f, 3f))
            drawRect(Palette.RoofRed, Offset(center.x - 2f, center.y - 5f), Size(4f, 4f))
        }

        detail % 37 == 0 -> drawRect(Palette.Gold, Offset(center.x, center.y - 4f), Size(3f, 3f))
    }
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
// Toolbar icons
// ======================================================================

/**
 * Paints one catalogue entry into a small square canvas — using the very same drawing code
 * the park uses, so the toolbar shows the actual ride rather than an emoji stand-in.
 */
internal fun DrawScope.drawItemIcon(item: BuildItem, map: ParkMap, measurer: TextMeasurer) {
    val scale = when {
        item.isTerrainBrush -> 0.62f
        item.category == ToolCategory.SCENERY -> 0.95f
        item.wTiles >= 4 -> 0.5f
        item.wTiles >= 3 -> 0.6f
        else -> 0.78f
    }
    val base = Iso.toScreen(item.wTiles / 2f, item.hTiles / 2f)

    withTransform({
        translate(size.width / 2f - base.x * scale, size.height * 0.84f - base.y * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        if (item.isTerrainBrush) {
            drawIconTerrain(item, map)
        } else {
            drawStructure(Structure(id = "icon", item = item, col = 0, row = 0), map, measurer)
        }
    }

    if (item == BuildItem.BULLDOZE) {
        // A crossed-out swatch reads instantly as "get rid of this".
        val cx = size.width / 2f
        val cy = size.height * 0.5f
        val arm = 15f
        drawLine(Palette.RoofRed, Offset(cx - arm, cy - arm), Offset(cx + arm, cy + arm), strokeWidth = 5f)
        drawLine(Palette.RoofRed, Offset(cx + arm, cy - arm), Offset(cx - arm, cy + arm), strokeWidth = 5f)
        drawLine(Palette.Outline, Offset(cx - arm, cy - arm), Offset(cx + arm, cy + arm), strokeWidth = 1.5f)
        drawLine(Palette.Outline, Offset(cx + arm, cy - arm), Offset(cx - arm, cy + arm), strokeWidth = 1.5f)
    }
}

/** Two-by-two swatch of a land brush — what dragging it across the ground gives you. */
private fun DrawScope.drawIconTerrain(item: BuildItem, map: ParkMap) {
    val allEdges = PixelArt.EDGE_NW or PixelArt.EDGE_NE or PixelArt.EDGE_SE or PixelArt.EDGE_SW
    for (col in 0..1) {
        for (row in 0..1) {
            val center = Iso.toScreen(col + 0.5f, row + 0.5f)
            val topLeft = Offset(center.x - Iso.HALF_W, center.y - Iso.HALF_H)
            val tileSize = Size(PixelArt.TILE_W.toFloat(), PixelArt.TILE_H.toFloat())
            val outer = col == 0 || row == 0
            val texture = when (item.terrain) {
                Terrain.PATH -> TextureAtlas.path(col + row, if (outer) allEdges else 0)
                Terrain.WATER -> TextureAtlas.water(col + row, if (outer) allEdges else 0)
                Terrain.QUEUE -> TextureAtlas.queue(true)
                else -> TextureAtlas.grass(map.detailAt(col, row) % PixelArt.GRASS_VARIANTS)
            }
            blit(texture, topLeft, tileSize)
        }
    }
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

    // Four tiles of slack absorbs the tallest ride and any rounding at the edges, and the
    // range is deliberately *not* clamped to the map: the ring of meadow beyond it is
    // drawn too, which is what stops the park floating in a void.
    val margin = 4
    return intArrayOf(
        max(-MEADOW_REACH, floor(minCol).toInt() - margin),
        min(cols - 1 + MEADOW_REACH, ceil(maxCol).toInt() + margin),
        max(-MEADOW_REACH, floor(minRow).toInt() - margin),
        min(rows - 1 + MEADOW_REACH, ceil(maxRow).toInt() + margin)
    )
}

/** How many tiles of countryside to draw beyond the buildable plot. */
private const val MEADOW_REACH = 24

/** Screen-space size of the park, used to clamp the camera. */
fun parkPixelSize(map: ParkMap): Size {
    val spanX = (map.cols + map.rows) * Iso.HALF_W
    val spanY = (map.cols + map.rows) * Iso.HALF_H
    return Size(spanX, spanY)
}
