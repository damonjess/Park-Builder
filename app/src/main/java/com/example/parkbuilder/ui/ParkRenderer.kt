package com.example.parkbuilder.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlin.math.atan2
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

/**
 * A rhombus in world space — the footprint shape of a tile, raised or lowered by [expand].
 *
 * The four corners used to be written as (left-top, right-top, right-bottom, left-bottom),
 * which is an axis-aligned *rectangle*: flower beds came out as framed boxes, ride floors
 * as planks and the build ghost as a grid of squares instead of the iso footprint.
 */
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

/** Base size used to probe a label before scaling it down to fit. */
private const val SIGN_PROBE_SP = 12

private val signTextStyle = TextStyle(color = Palette.Outline, fontWeight = FontWeight.Bold)

/**
 * Draws [text] centred at [center], scaled so it never grows past [maxWidth] x [maxHeight]
 * *world pixels*.
 *
 * Text drawn in world space has to be sized in pixels, not in `sp`. A fixed `sp` label is
 * density-dependent: on a 3x-density phone an 8sp shop name came out roughly 130px wide
 * against a 27px signboard, so every building wore a caption floating over the park.
 * Probing once at a known size and scaling the ratio makes the result density-independent.
 */
private fun DrawScope.fitText(
    center: Offset,
    text: String,
    maxWidth: Float,
    maxHeight: Float,
    colour: Color,
    measurer: TextMeasurer,
    baseSp: Int = SIGN_PROBE_SP,
    fontWeight: FontWeight = FontWeight.Bold
) {
    val probeStyle = TextStyle(color = colour, fontWeight = fontWeight, fontSize = baseSp.sp)
    val probe = measurer.measure(text, probeStyle)
    if (probe.size.width <= 0 || probe.size.height <= 0) return
    // Fit to whichever dimension binds first; a sliver of padding keeps it off the frame.
    val scale = min(maxWidth / probe.size.width, maxHeight / probe.size.height)
    if (scale <= 0f) return

    val style = probeStyle.copy(fontSize = (baseSp * scale).sp)
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

/**
 * A little wooden board with a word painted on it.
 *
 * The lettering is fitted to the board — see [fitText] for why a fixed size does not work.
 */
private fun DrawScope.signBoard(
    center: Offset,
    width: Float,
    height: Float,
    text: String,
    measurer: TextMeasurer,
    board: Color = Palette.WoodDark
) {
    // Timber frame, gold panel, painted field inside it.
    drawRect(board, Offset(center.x - width / 2f, center.y - height / 2f), Size(width, height))
    drawRect(
        Palette.Gold,
        Offset(center.x - width / 2f + 1f, center.y - height / 2f + 1f),
        Size(width - 2f, height - 2f)
    )
    drawRect(
        Palette.WallCream,
        Offset(center.x - width / 2f + 2.5f, center.y - height / 2f + 2.5f),
        Size(width - 5f, height - 5f)
    )
    fitText(center, text, width - 7f, height - 4f, Palette.Outline, measurer)
}

/**
 * Signboards in the reference carry one short word, which is what lets the letters stay
 * readable while the board itself is a fraction of the building's width.
 */
private fun shortLabel(item: BuildItem): String = when (item) {
    BuildItem.BURGER_BAR -> "BURGER"
    BuildItem.SODA_STAND -> "SODA"
    BuildItem.ICE_CREAM -> "ICE CREAM"
    BuildItem.GIFT_SHOP -> "GIFTS"
    BuildItem.RESTROOM -> "TOILETS"
    else -> item.displayName.uppercase().take(10)
}

/**
 * The `expand` factor [diamond] needs to cover a [w] x [h] tile footprint.
 *
 * `diamond` measures in half a *tile*, so a plain factor of 1 is one tile across. A 2x2
 * fountain asking for 0.94 therefore drew a single-tile puddle floating in the middle of
 * its marble, and a 3x3 dodgems floor came out as a pool-table baize on a dance floor.
 * The half-extent of a w x h footprint is (w + h) / 2 tiles.
 */
private fun footprintExpand(w: Float, h: Float, fraction: Float = 1f): Float =
    (w + h) / 2f * fraction

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
    // Ground shadow under the whole footprint. Kept light and a touch smaller than the
    // plot so it grounds a building without reading as a grey slab of its own.
    val shadowCenter = Iso.toScreen(structure.centerCol(), structure.centerRow())
    diamond(
        shadowCenter,
        footprintExpand(structure.wTiles.toFloat(), structure.hTiles.toFloat(), 0.94f),
        Palette.Shadow
    )

    when (structure.item) {
        BuildItem.CAROUSEL -> drawCarousel(structure)
        BuildItem.FERRIS_WHEEL -> drawFerrisWheel(structure)
        BuildItem.DODGEMS -> drawDodgems(structure)
        BuildItem.LOG_FLUME -> drawLogFlume(structure)
        BuildItem.ROLLER_COASTER -> drawRollerCoaster(structure)
        BuildItem.BURGER_BAR -> drawShopHut(structure, Palette.RoofRed, measurer)
        BuildItem.SODA_STAND -> drawShopHut(structure, Palette.RoofBlue, measurer)
        BuildItem.ICE_CREAM -> drawShopHut(structure, Palette.RoofTeal, measurer)
        BuildItem.GIFT_SHOP -> drawShopHut(structure, Color(0xFFA94FD4), measurer)
        BuildItem.RESTROOM -> drawShopHut(structure, Color(0xFF3E9B52), measurer)
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

/**
 * A carousel: a round platform under a striped cone roof, with horses on brass poles
 * riding the rim.
 *
 * The old version was a chequered square box with a tent on it and eight little crates
 * orbited inside, which is why it read as a gazebo. A carousel is a *circle*, and a circle
 * in this projection is a 2:1 ellipse — so the platform, the platform's skirt, the roof
 * rim and the ring of horses are all ellipses measured off the footprint. The horses are
 * drawn as horses: barrel, neck, head, tail, legs and a saddle, turned to run along the
 * ring so they travel the way the ride turns.
 */
private fun DrawScope.drawCarousel(structure: Structure) {
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val centre = Iso.toScreen(structure.centerCol(), structure.centerRow())
    val rx = Iso.HALF_W * footprintExpand(w, h) * 0.92f
    val ry = rx / 2f                       // 2:1 dimetric keeps circles honest

    val deckRise = 13f
    val deckY = centre.y - deckRise
    val apex = Offset(centre.x, deckY - structure.item.blockHeight)

    // ---- Round platform ---------------------------------------------------
    // One tall ellipse covers the side wall and the bottom both; the deck is painted over
    // the top of it, which is what reads as a raised circular floor.
    drawOval(Palette.Shadow, Offset(centre.x - rx * 1.05f, centre.y - ry * 1.05f + 3f), Size(rx * 2.1f, ry * 2.1f))
    drawOval(Palette.RoofRed, Offset(centre.x - rx, centre.y - deckRise - ry), Size(rx * 2f, ry * 2f + deckRise))
    drawOval(
        Palette.Outline,
        Offset(centre.x - rx, centre.y - deckRise - ry),
        Size(rx * 2f, ry * 2f + deckRise),
        style = Stroke(width = 1.4f)
    )
    // Painted skirt band, then the deck itself with a brass ring inlaid in it.
    drawOval(Palette.RoofCream, Offset(centre.x - rx, centre.y - deckRise * 0.5f - ry), Size(rx * 2f, ry * 2f + deckRise * 0.5f))
    drawOval(Palette.RoofCream, Offset(centre.x - rx, deckY - ry), Size(rx * 2f, ry * 2f))
    drawOval(
        Palette.Gold,
        Offset(centre.x - rx * 0.94f, deckY - ry * 0.94f),
        Size(rx * 1.88f, ry * 1.88f),
        style = Stroke(width = 2.2f)
    )
    drawOval(
        shade(Palette.RoofCream, 0.12f),
        Offset(centre.x - rx * 0.80f, deckY - ry * 0.80f),
        Size(rx * 1.6f, ry * 1.6f)
    )

    // ---- Central column ---------------------------------------------------
    drawRect(Palette.Gold, Offset(centre.x - 4f, apex.y), Size(8f, deckY - apex.y))
    drawRect(Palette.RoofCream, Offset(centre.x - 4f, apex.y), Size(2.6f, deckY - apex.y))
    drawOval(Palette.Gold, Offset(centre.x - 10f, deckY - 7f), Size(20f, 10f))
    drawOval(
        shade(Palette.Gold, 0.35f),
        Offset(centre.x - 10f, deckY - 7f),
        Size(20f, 10f),
        style = Stroke(width = 1.2f)
    )

    // The roof is kept well inside the ring so there is somewhere for the horses to be.
    val canopyR = 0.66f
    val ring = 0.92f
    // Six, not eight: eight horses on a 2x2 plot shoulder-to-shoulder each other into a
    // tangle, and a carousel reads better with daylight between the animals.
    val horses = 6
    val spin = structure.animAngle * PI.toFloat() / 180f

    // Back half of the ring first — the roof is meant to hide it. The front half is drawn
    // after the canopy so those horses stay in front of the marquee rather than vanishing
    // underneath it.
    for (pass in 0..1) {
        if (pass == 1) drawCarouselCanopy(centre, deckY, rx, ry, apex, canopyR)
        for (i in 0 until horses) {
            val a = i * 2.0 * PI / horses + spin
            val depth = sin(a)
            if (if (pass == 0) depth >= 0.0 else depth < 0.0) continue
            drawCarouselHorse(
                x = centre.x + (cos(a) * rx * ring).toFloat(),
                foot = deckY + (depth * ry * ring).toFloat(),
                // Horses face the way they are travelling along the ring. Rotating them to
                // the tangent instead is what sent them upside down on the far side.
                facingRight = -sin(a) > 0.0,
                coat = carouselCoats[i % carouselCoats.size],
                shirt = VisitorShirts[i % VisitorShirts.size],
                hair = VisitorHair[i % VisitorHair.size],
                bob = sin((structure.animAngle + i * 60f) * PI.toFloat() / 90f) * 2.2f
            )
        }
    }

    // ---- Finial and pennant ------------------------------------------------
    drawRect(Palette.Gold, Offset(apex.x - 1.5f, apex.y - 9f), Size(3f, 10f))
    drawCircle(Palette.Gold, 3.5f, Offset(apex.x, apex.y - 11f))
    val flag = TextureAtlas.flag(Palette.RoofRed)
    blit(
        flag,
        Offset(apex.x - flag.width / 2f, apex.y - flag.height - 4f),
        Size(flag.width.toFloat(), flag.height.toFloat())
    )
}

/**
 * The carousel roof: a cone over an ellipse, built as triangles from the rim to the apex.
 *
 * Slices are drawn back to front, so the near stripes paint over the far ones instead of
 * the roof showing through itself.
 */
private fun DrawScope.drawCarouselCanopy(
    centre: Offset,
    deckY: Float,
    rx: Float,
    ry: Float,
    apex: Offset,
    radius: Float
) {
    val rimX = rx * radius
    val rimY = ry * radius
    val slices = 14
    for (pass in 0..1) {
        for (i in 0 until slices) {
            val a0 = i * 2.0 * PI / slices
            val a1 = (i + 1) * 2.0 * PI / slices
            val isBack = sin((a0 + a1) / 2.0) < 0.0
            if (if (pass == 0) !isBack else isBack) continue
            tri(
                Offset(centre.x + (cos(a0) * rimX).toFloat(), deckY + (sin(a0) * rimY).toFloat()),
                Offset(centre.x + (cos(a1) * rimX).toFloat(), deckY + (sin(a1) * rimY).toFloat()),
                apex,
                if (i % 2 == 0) Palette.RoofRed else Palette.RoofCream
            )
        }
    }
    // The rim of the roof: a dark red bead rather than a black hoop, which read as a tyre
    // lying across the platform.
    drawOval(
        shade(Palette.RoofRed, 0.4f),
        Offset(centre.x - rimX, deckY - rimY),
        Size(rimX * 2f, rimY * 2f),
        style = Stroke(width = 1.6f)
    )
    // Scalloped valance round the front of the rim.
    for (i in 0 until slices) {
        val a0 = i * 2.0 * PI / slices
        val a1 = (i + 1) * 2.0 * PI / slices
        if (sin((a0 + a1) / 2.0) < 0.0) continue
        val mid = lerp(
            Offset(centre.x + (cos(a0) * rimX).toFloat(), deckY + (sin(a0) * rimY).toFloat()),
            Offset(centre.x + (cos(a1) * rimX).toFloat(), deckY + (sin(a1) * rimY).toFloat()),
            0.5f
        )
        drawCircle(Palette.RoofCream, 2.6f, Offset(mid.x, mid.y + 1.2f))
        drawCircle(Palette.Outline, 2.6f, Offset(mid.x, mid.y + 1.2f), style = Stroke(width = 0.7f))
    }
}

/** Coat colours, so the ring reads as a herd of painted horses rather than one mould. */
private val carouselCoats = listOf(
    Color(0xFFF6F1E4), Color(0xFFC4834A), Color(0xFF6E5442),
    Color(0xFFEFE4CC), Color(0xFFD9A05B), Color(0xFFB7B2AC)
)

/**
 * One wooden horse on its brass pole, always the right way up.
 *
 * It faces its direction of travel by *mirroring* rather than rotating, so a horse on the
 * left of the ring is a horse looking left — not a horse standing on its head.
 */
private fun DrawScope.drawCarouselHorse(
    x: Float,
    foot: Float,
    facingRight: Boolean,
    coat: Color,
    shirt: Color,
    hair: Color,
    bob: Float
) {
    val hip = foot - 9f - bob

    // The brass pole is drawn as a stub rising through the horse from the deck. Running it
    // all the way up to the roof instead left six poles jutting over the canopy.
    drawRect(Palette.Gold, Offset(x - 1.4f, hip - 6f), Size(2.8f, foot - hip + 7f))
    drawRect(shade(Palette.Gold, 0.35f), Offset(x + 0.6f, hip - 6f), Size(1f, foot - hip + 7f))

    for (leg in 0 until 4) {
        val lx = x - 5.2f + leg * 3.4f
        drawRect(Palette.Outline, Offset(lx, hip), Size(1.9f, 9f))
        drawRect(Palette.WoodDark, Offset(lx - 0.2f, hip + 7.6f), Size(2.3f, 1.6f))
    }

    withTransform({ scale(if (facingRight) 1f else -1f, 1f, pivot = Offset(x, hip)) }) {
        // Barrel, with a shaded belly.
        drawRect(Palette.Outline, Offset(x - 7f, hip - 4.5f), Size(14f, 9f))
        drawRect(coat, Offset(x - 6f, hip - 3.5f), Size(12f, 7f))
        drawRect(shade(coat, 0.3f), Offset(x - 6f, hip + 1.8f), Size(12f, 1.8f))
        // Neck rising to the head, with ears and a mane down the back of it.
        drawRect(Palette.Outline, Offset(x + 4.4f, hip - 11.5f), Size(5f, 9f))
        drawRect(coat, Offset(x + 5f, hip - 10.6f), Size(3.8f, 8f))
        drawRect(Palette.Outline, Offset(x + 4.4f, hip - 15.5f), Size(7.6f, 5f))
        drawRect(coat, Offset(x + 5f, hip - 14.9f), Size(6.4f, 4f))
        tri(Offset(x + 4.8f, hip - 15.5f), Offset(x + 5.9f, hip - 18.2f), Offset(x + 7.4f, hip - 15.5f), Palette.Outline)
        drawRect(Palette.WoodDark, Offset(x + 4.4f, hip - 11.5f), Size(2f, 8f))
        // Tail.
        drawRect(Palette.WoodDark, Offset(x - 10f, hip - 4.5f), Size(3.6f, 7f))
        // Painted harness and saddle.
        drawRect(Palette.RoofRed, Offset(x - 3f, hip - 5.8f), Size(7f, 3.2f))
        drawRect(Palette.Gold, Offset(x - 3f, hip - 6.1f), Size(7f, 1.1f))
        drawRect(Palette.Gold, Offset(x + 1.3f, hip - 2.6f), Size(1.4f, 5.2f))

        // A rider in the saddle: the carousel looks deserted without somebody on it.
        val seatY = hip - 5.4f
        drawRect(Palette.Outline, Offset(x - 2.9f, seatY - 7f), Size(5.8f, 7.6f))
        drawRect(shirt, Offset(x - 2.3f, seatY - 6.4f), Size(4.6f, 6.6f))
        // Leg down the near flank, boot under it.
        drawRect(Palette.Outline, Offset(x - 1.2f, seatY + 0.4f), Size(2.6f, 6.4f))
        drawRect(shade(shirt, 0.25f), Offset(x - 0.8f, seatY + 0.8f), Size(1.9f, 5.6f))
        drawRect(Palette.WoodDark, Offset(x - 1.4f, seatY + 5.8f), Size(2.8f, 1.6f))
        // Arm reaching for the pole.
        drawRect(Palette.Outline, Offset(x + 1.4f, seatY - 6.2f), Size(2.4f, 4.6f))
        drawRect(Palette.Skin, Offset(x + 1.7f, seatY - 5.8f), Size(1.8f, 4f))
        // Head under a mop of hair.
        drawCircle(Palette.Outline, 3.1f, Offset(x - 0.4f, seatY - 9.6f))
        drawCircle(Palette.Skin, 2.7f, Offset(x - 0.4f, seatY - 9.6f))
        drawRect(hair, Offset(x - 3.1f, seatY - 12.6f), Size(5.4f, 2.6f))
    }
}

private fun DrawScope.drawFerrisWheel(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val plat = structure.item.blockHeight
    val groundCenter = Iso.toScreen(structure.centerCol(), structure.centerRow())

    // A chequered apron under the legs rather than a slab under the whole plot: the
    // reference stands its rides on the lawn and lets the grass run right up to them.
    isoBlock(
        col + w * 0.2f, row + h * 0.2f, w * 0.6f, h * 0.6f, 4f,
        Palette.RoofCream, Palette.RoofRed, shade(Palette.RoofRed, 0.25f),
        outline = true,
        topTex = TextureAtlas.checker(Palette.RoofCream, Palette.RoofRed),
        sideTex = TextureAtlas.siding(Palette.RoofRed)
    )

    val hub = Offset(groundCenter.x, groundCenter.y - plat)
    // Sized to the plot: a 3x3 wheel used to be a 34px toy on a 190px platform.
    val radius = min(Iso.HALF_W * w * 0.72f, 66f)

    // A-frame legs down to the apron, drawn first so the wheel sits in front of them.
    drawLine(Palette.MetalDark, hub, Offset(groundCenter.x - 22f, groundCenter.y - 4f), strokeWidth = 5f)
    drawLine(Palette.MetalDark, hub, Offset(groundCenter.x + 22f, groundCenter.y - 4f), strokeWidth = 5f)
    drawLine(Palette.Metal, hub, Offset(groundCenter.x - 30f, groundCenter.y + 6f), strokeWidth = 5f)
    drawLine(Palette.Metal, hub, Offset(groundCenter.x + 30f, groundCenter.y + 6f), strokeWidth = 5f)
    drawLine(
        Palette.MetalDark,
        Offset(groundCenter.x - 30f, groundCenter.y + 6f),
        Offset(groundCenter.x + 30f, groundCenter.y + 6f),
        strokeWidth = 3f
    )

    // Twin rims with a truss between them.
    drawCircle(Palette.MetalDark, radius, hub, style = Stroke(width = 5f))
    drawCircle(Palette.Metal, radius - 3.5f, hub, style = Stroke(width = 3f))
    drawCircle(Palette.Outline, radius, hub, style = Stroke(width = 1.4f))
    drawCircle(Palette.MetalDark, 6f, hub)

    rotate(degrees = structure.animAngle, pivot = hub) {
        for (i in 0 until 8) {
            val a = i * 45.0 * PI / 180.0
            val tip = Offset(hub.x + (cos(a) * radius).toFloat(), hub.y + (sin(a) * radius).toFloat())
            drawLine(Palette.Metal, hub, tip, strokeWidth = 2f)
            // Two-tone gondola: paint the body every other arm so the wheel reads as it turns.
            val body = if (i % 2 == 0) Palette.RoofRed else Palette.RoofBlue
            drawRect(Palette.Outline, Offset(tip.x - 7f, tip.y - 9f), Size(14f, 16f))
            drawRect(body, Offset(tip.x - 6f, tip.y - 8f), Size(12f, 14f))
            drawRect(shade(body, 0.35f), Offset(tip.x - 6f, tip.y + 3f), Size(12f, 3f))
            drawRect(Palette.GlassLight, Offset(tip.x - 4f, tip.y - 6f), Size(8f, 5f))
            drawRect(Palette.Gold, Offset(tip.x - 6f, tip.y - 9f), Size(12f, 1.5f))
        }
    }
    // Hub bolts, so the centre is not a plain disc.
    for (i in 0 until 6) {
        val a = i * 60.0 * PI / 180.0
        drawCircle(
            Palette.Gold, 1.8f,
            Offset(hub.x + (cos(a) * 10f).toFloat(), hub.y + (sin(a) * 10f).toFloat())
        )
    }
}

/** How many bumper cars run on a dodgems floor. */
internal const val dodgemCars = 6

/** Length, in seconds, of the precomputed bumper-car loop, and how finely it is sampled. */
private const val DODGEM_LOOP_SECONDS = 36f
private const val DODGEM_SAMPLE_HZ = 20
internal const val DODGEM_FRAMES = (DODGEM_LOOP_SECONDS * DODGEM_SAMPLE_HZ).toInt()

/** Scratch for one frame of dodgems: no per-frame allocation, no sorting list. */
private val dodgemX = FloatArray(dodgemCars)
private val dodgemY = FloatArray(dodgemCars)
private val dodgemAngle = FloatArray(dodgemCars)
private val dodgemFlash = FloatArray(dodgemCars)

/**
 * The bumper-car choreography: positions, headings and impact flashes, simulated once and
 * then sampled every frame.
 *
 * The cars have to actually collide — bouncing off each other and off the rink wall — or
 * they are just six things gliding about. But a collision is a function of everything that
 * happened before it, and the renderer has no memory of previous frames, so instead of
 * simulating per frame the whole loop is worked out once, up front, and then played back.
 *
 * Simulated in rink units: the floor diamond is |x| + 2|y| <= 1, and one unit is the same
 * number of screen pixels on both axes, so distances here are honest distances.
 */
internal val dodgemTrack: FloatArray by lazy { simulateDodgems() }

private fun simulateDodgems(): FloatArray {
    val data = FloatArray(DODGEM_FRAMES * dodgemCars * 4)
    val px = FloatArray(dodgemCars)
    val py = FloatArray(dodgemCars)
    val vx = FloatArray(dodgemCars)
    val vy = FloatArray(dodgemCars)
    val flash = FloatArray(dodgemCars)

    // A fixed seed: the same dance every time, which keeps screenshots and tests honest.
    var seed = 20261002
    fun rand(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 16) and 0x7FFF) / 32767f
    }

    val carR = 0.13f          // half a car, in rink units
    val dt = 1f / DODGEM_SAMPLE_HZ
    for (i in 0 until dodgemCars) {
        val a = i * 2f * PI.toFloat() / dodgemCars + 0.4f
        px[i] = cos(a) * 0.55f
        py[i] = sin(a) * 0.24f
        val speed = 0.52f + rand() * 0.34f
        val dir = rand() * 2f * PI.toFloat()
        vx[i] = cos(dir) * speed
        vy[i] = sin(dir) * speed
    }

    for (f in 0 until DODGEM_FRAMES) {
        // Drive: each car steers a little of its own accord, so they never settle into
        // tidy orbits, and drift for a moment after a hit.
        for (i in 0 until dodgemCars) {
            val turn = (rand() - 0.5f) * 1.7f * dt
            val c = cos(turn)
            val s = sin(turn)
            val turnedX = vx[i] * c - vy[i] * s
            vy[i] = vx[i] * s + vy[i] * c
            vx[i] = turnedX
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            flash[i] = (flash[i] - dt * 3.2f).coerceAtLeast(0f)
        }

        // The rink wall: the diamond's edges, inset by half a car, with a real rebound.
        for (i in 0 until dodgemCars) {
            val reach = kotlin.math.abs(px[i]) + 2f * kotlin.math.abs(py[i])
            val limit = 1f - carR * 1.9f
            if (reach <= limit || reach < 1e-5f) continue
            val shrink = limit / reach
            px[i] *= shrink
            py[i] *= shrink
            var nx = if (px[i] >= 0f) 1f else -1f
            var ny = if (py[i] >= 0f) 2f else -2f
            val nl = sqrt(nx * nx + ny * ny)
            nx /= nl
            ny /= nl
            val vn = vx[i] * nx + vy[i] * ny
            if (vn > 0f) {
                vx[i] -= 2f * vn * nx
                vy[i] -= 2f * vn * ny
                flash[i] = maxOf(flash[i], 0.6f)
            }
        }

        // Car against car: equal masses, so they swap the speed along the line of impact.
        for (i in 0 until dodgemCars) {
            for (j in i + 1 until dodgemCars) {
                val dx = px[j] - px[i]
                val dy = py[j] - py[i]
                val d = sqrt(dx * dx + dy * dy)
                if (d >= carR * 2f || d < 1e-5f) continue
                val nx = dx / d
                val ny = dy / d
                val push = (carR * 2f - d) * 0.5f
                px[i] -= nx * push
                py[i] -= ny * push
                px[j] += nx * push
                py[j] += ny * push
                val closing = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                if (closing < 0f) {
                    val impulse = -closing * 0.92f
                    vx[i] -= nx * impulse
                    vy[i] -= ny * impulse
                    vx[j] += nx * impulse
                    vy[j] += ny * impulse
                    flash[i] = 1f
                    flash[j] = 1f
                }
            }
        }

        for (i in 0 until dodgemCars) {
            val o = (f * dodgemCars + i) * 4
            data[o] = px[i]
            data[o + 1] = py[i]
            data[o + 2] = atan2(vy[i], vx[i]) * 180f / PI.toFloat()
            data[o + 3] = flash[i]
        }
    }
    return data
}

private val dodgemColours = listOf(
    Palette.Gold, Palette.RoofBlue, Palette.RoofRed,
    Color(0xFFE8459B), Color(0xFF43B04A), Color(0xFFEE7B2F)
)

/**
 * One bumper car: a rubber ring round a painted shell, a driver inside it, and the pickup
 * pole that is the whole reason a dodgem looks like a dodgem.
 */
private fun DrawScope.drawDodgemCar(
    x: Float,
    y: Float,
    heading: Float,
    body: Color,
    shirt: Color,
    flash: Float
) {
    // The pickup pole is vertical in the world, so it is drawn *outside* the car's rotation.
    // Rotating it with the shell had the poles lying along the cars like oars.
    drawRect(Palette.MetalDark, Offset(x + 1.4f, y - 20f), Size(2.2f, 17f))
    drawCircle(Palette.Metal, 2.4f, Offset(x + 2.5f, y - 21f))
    // The contact flash on the pole head: the tell that two cars have just met.
    if (flash > 0.05f) {
        drawCircle(
            Color(0xFFFFF3B0).copy(alpha = flash),
            2f + flash * 2.4f,
            Offset(x + 2.5f, y - 22.5f)
        )
    }

    rotate(degrees = heading, pivot = Offset(x, y)) {
        // Rubber bumper ring, then the painted shell inside it.
        drawRoundRect(
            Palette.Outline,
            Offset(x - 9f, y - 7f), Size(18f, 14f), CornerRadius(4.5f, 4.5f)
        )
        drawRoundRect(body, Offset(x - 8f, y - 6f), Size(16f, 12f), CornerRadius(4f, 4f))
        drawRoundRect(
            shade(body, 0.36f),
            Offset(x - 8f, y + 3.4f), Size(16f, 2.6f), CornerRadius(1.3f, 1.3f)
        )
        // Nose scuttle and a little windscreen at the front of the tub.
        drawRoundRect(
            Palette.Metal,
            Offset(x + 4.6f, y - 5.6f), Size(3.4f, 11.2f), CornerRadius(1.4f, 1.4f)
        )
        drawRect(Palette.GlassLight, Offset(x + 0.4f, y - 4.4f), Size(3.6f, 8.8f))
        // Driver, sitting in the tub in front of the pole.
        drawRect(shirt, Offset(x - 4.4f, y - 3.2f), Size(6.6f, 6.4f))
        drawCircle(Palette.Skin, 2.9f, Offset(x - 1.4f, y - 4.6f))
    }

    // Impact sparks thrown out around the bumper ring.
    if (flash > 0.25f) {
        val spark = Color(0xFFFFF3B0).copy(alpha = (flash - 0.25f) * 1.3f)
        for (k in 0 until 4) {
            val a = k * 90f + heading
            val rad = a * PI.toFloat() / 180f
            val near = Offset(x + cos(rad) * 8f, y + sin(rad) * 6f)
            val away = Offset(x + cos(rad) * (8f + 4f * flash), y + sin(rad) * (6f + 3f * flash))
            drawLine(spark, near, away, strokeWidth = 1.6f)
        }
    }
}

private fun DrawScope.drawDodgems(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)
    val deckH = 5f

    // Fairground skirting: a chequered riding deck on a striped valance, not a riveted
    // grey slab. This is the single biggest "flat placeholder" tell in the old art.
    isoBlock(
        col, row, w, h, deckH,
        Palette.RoofTeal, Palette.RoofRed, shade(Palette.RoofRed, 0.2f),
        outline = true,
        topTex = TextureAtlas.checker(Palette.RoofTeal, Palette.RoofCream),
        sideTex = TextureAtlas.stripes(Palette.RoofRed, Palette.RoofCream),
        sideScale = 2.2f
    )
    val floor = Offset((base[0].x + base[2].x) / 2f, (base[0].y + base[2].y) / 2f - deckH)
    // The riding floor spans the whole 3x3 deck; only its inset markings are smaller.
    val span = footprintExpand(w, h)
    diamond(floor, span * 0.84f, Color(0xFF1E3C6A))
    diamondStroke(floor, span * 0.84f, Palette.Gold)
    diamondStroke(floor, span * 0.66f, Color(0xFF2F5FA6))
    diamond(floor, span * 0.42f, Color(0xFF17305A))

    // Six bumper cars running the precomputed collision loop. Half a rink unit is half the
    // floor diamond, and the two axes share a scale, so the table maps straight on.
    val floorR = span * 0.84f * Iso.HALF_W
    val sample = ((structure.ageSeconds % DODGEM_LOOP_SECONDS) * DODGEM_SAMPLE_HZ)
        .toInt()
        .coerceIn(0, DODGEM_FRAMES - 1)
    for (i in 0 until dodgemCars) {
        val o = (sample * dodgemCars + i) * 4
        val heading = dodgemTrack[o + 2]
        val flash = dodgemTrack[o + 3]
        // A hit shoves the car along its nose for a moment, so impacts are felt and not
        // just drawn.
        val jolt = flash * 1.8f
        val radians = heading * PI.toFloat() / 180f
        dodgemX[i] = floor.x + dodgemTrack[o] * floorR + cos(radians) * jolt
        dodgemY[i] = floor.y + dodgemTrack[o + 1] * floorR + sin(radians) * jolt
        dodgemAngle[i] = heading
        dodgemFlash[i] = flash
    }

    // Back of the floor first, so the cars nearer the camera overlap the ones behind them.
    for (pass in 0..1) {
        for (i in 0 until dodgemCars) {
            val front = dodgemY[i] > floor.y
            if (front != (pass == 1)) continue
            drawDodgemCar(
                x = dodgemX[i],
                y = dodgemY[i],
                heading = dodgemAngle[i],
                body = dodgemColours[i % dodgemColours.size],
                shirt = VisitorShirts[(i + 2) % VisitorShirts.size],
                flash = dodgemFlash[i]
            )
        }
    }

    // Corner poles carrying a striped canopy fringe right round the visible front.
    base.forEach { corner ->
        val top = Offset(corner.x, corner.y - deckH - 26f)
        drawRect(Palette.Metal, Offset(top.x - 1.5f, top.y), Size(3f, 26f))
        drawCircle(Palette.Gold, 3f, Offset(top.x, top.y - 2f))
    }
    // The overhead pickup frame: a slim cream rail with red ticks linking the posts. The
    // old version was a 9px band of stripes sat at the same height, which read as a solid
    // red beam lying across the floor.
    listOf(base[3] to base[2], base[2] to base[1]).forEach { (from, to) ->
        val top = deckH + 26f
        val a = Offset(from.x, from.y - top)
        val b = Offset(to.x, to.y - top)
        drawLine(shade(Palette.MetalDark, 0.15f), a, b, strokeWidth = 4.4f)
        drawLine(Palette.RoofCream, a, b, strokeWidth = 3f)
        val ticks = 10
        for (k in 0 until ticks step 2) {
            drawLine(
                Palette.RoofRed,
                lerp(a, b, k / ticks.toFloat()),
                lerp(a, b, (k + 1) / ticks.toFloat()),
                strokeWidth = 2.2f
            )
        }
    }
}

/** Eases [x] at both ends; the flume uses it for the lift and the plunge. */
private fun smoothStep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * How high the flume channel sits above the ground at loop parameter [t] (0..1).
 *
 * Flat water, a long climb, a short crest, then the plunge — the same silhouette a real
 * flume lift hill has, and the reason the ride reads as a ride rather than a moat.
 */
private fun flumeLift(t: Float): Float = when {
    t < 0.05f -> 7f
    t < 0.33f -> 7f + 45f * smoothStep((t - 0.05f) / 0.28f)
    t < 0.41f -> 52f
    t < 0.51f -> 52f - 45f * smoothStep((t - 0.41f) / 0.10f)
    else -> 7f
}

/**
 * A log flume: a winding water trough carried on trestles that climbs a lift hill, drops
 * into a splash pool and runs a closed circuit with logs riding the water.
 *
 * The old version was a flat brown deck with a pole through it and three boats spinning
 * around a pivot — which is why it read as a windmill in a dirt patch rather than a ride.
 * The course here is a closed, three-lobed loop measured in tile space, so the logs can be
 * placed by arc length and turned to face the tangent of the channel they are sitting in.
 */
private fun DrawScope.drawLogFlume(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()

    val steps = 120
    val centreCol = col + w / 2f
    val centreRow = row + h / 2f
    val radiusCol = w * 0.40f
    val radiusRow = h * 0.40f

    val ground = ArrayList<Offset>(steps + 1)
    val channel = ArrayList<Offset>(steps + 1)
    val lift = FloatArray(steps + 1)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val a = t * 2f * PI.toFloat()
        // Three lobes: a meandering watercourse, and one that never crosses itself.
        val lobe = 1f + 0.22f * sin(3f * a)
        val p = Iso.toScreen(
            centreCol + cos(a) * radiusCol * lobe,
            centreRow + sin(a) * radiusRow * lobe
        )
        val rise = flumeLift(t)
        ground += p
        channel += Offset(p.x, p.y - rise)
        lift[i] = rise
    }

    // Splash pool where the plunge lands, so the drop has somewhere to arrive.
    val landingIndex = (0.53f * steps).toInt().coerceIn(0, steps)
    val splash = ground[landingIndex]
    diamond(splash, 0.80f, Palette.WaterDeep)
    diamond(splash, 0.66f, Palette.WaterMid)
    diamond(splash, 0.46f, Palette.WaterLight)
    diamondStroke(splash, 0.80f, Palette.WaterFoam)
    repeat(7) { k ->
        val spread = (k - 3f) * 6f
        drawCircle(
            Palette.WaterFoam,
            3.4f - kotlin.math.abs(k - 3f) * 0.6f,
            Offset(splash.x + spread, splash.y - 3f - kotlin.math.abs(k - 3f))
        )
    }

    // Trestles under the raised half, so the channel is visibly carried rather than
    // floating: a post, a lit edge, and a brace leaning back to the ground.
    for (i in 0..steps step 4) {
        val rise = lift[i]
        if (rise < 11f) continue
        val top = channel[i]
        drawRect(Palette.WoodDark, Offset(top.x - 1.8f, top.y), Size(3.6f, rise))
        drawRect(Palette.Wood, Offset(top.x - 1.8f, top.y), Size(1.4f, rise))
        drawLine(Palette.WoodDark, ground[i], Offset(top.x + 5f, top.y + 4f), strokeWidth = 1.2f)
    }

    // The channel: timber walls, the shadow inside them, then the water itself.
    shapePath.rewind()
    shapePath.moveTo(channel[0].x, channel[0].y)
    for (i in 1..steps) shapePath.lineTo(channel[i].x, channel[i].y)
    shapePath.close()
    drawPath(shapePath, Palette.Outline, style = Stroke(15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(shapePath, Palette.Wood, style = Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(shapePath, Palette.WoodDark, style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(shapePath, Palette.WaterMid, style = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(shapePath, Palette.WaterLight, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Boarding station on the flat run before the lift: a deck under a striped canopy.
    val stationIndex = (0.90f * steps).toInt().coerceIn(0, steps)
    val station = channel[stationIndex]
    drawRect(Palette.Outline, Offset(station.x - 23f, station.y - 4f), Size(46f, 9f))
    drawRect(Palette.Wood, Offset(station.x - 22f, station.y - 3f), Size(44f, 7f))
    drawRect(Palette.WoodDark, Offset(station.x - 22f, station.y + 2f), Size(44f, 2f))
    drawRect(Palette.MetalDark, Offset(station.x - 20f, station.y - 27f), Size(2.5f, 24f))
    drawRect(Palette.MetalDark, Offset(station.x + 17.5f, station.y - 27f), Size(2.5f, 24f))
    blit(TextureAtlas.stripes(Palette.RoofRed, Palette.RoofCream), Offset(station.x - 23f, station.y - 32f), Size(46f, 8f))
    drawRect(Palette.Outline, Offset(station.x - 23f, station.y - 24f), Size(46f, 1.2f))

    // Logs riding the water, placed by arc length and turned to the channel tangent.
    val cycle = ((structure.animAngle / 360f) % 1f + 1f) % 1f
    for (boat in 0 until 3) {
        val raw = ((cycle + boat / 3f) % 1f) * steps
        val index = raw.toInt().coerceIn(0, steps - 1)
        val pos = lerp(channel[index], channel[index + 1], raw - index)
        val ahead = channel[(index + 2).coerceAtMost(steps)]
        val angle = atan2((ahead.y - pos.y).toDouble(), (ahead.x - pos.x).toDouble())

        // Wake trailing out behind the hull.
        val trail = Offset(
            pos.x - (cos(angle) * 13f).toFloat(),
            pos.y - (sin(angle) * 13f).toFloat()
        )
        drawCircle(Palette.WaterFoam.copy(alpha = 0.7f), 2.6f, trail)
        drawCircle(Palette.WaterFoam.copy(alpha = 0.45f), 2f, Offset(trail.x - (cos(angle) * 7f).toFloat(), trail.y - (sin(angle) * 7f).toFloat()))

        // Hull: a hollowed log, drawn along the tangent.
        rotate(degrees = (angle * 180.0 / PI).toFloat(), pivot = pos) {
            drawRect(Palette.Outline, Offset(pos.x - 12f, pos.y - 6f), Size(24f, 12f))
            drawRect(Palette.Wood, Offset(pos.x - 11f, pos.y - 5f), Size(22f, 10f))
            drawRect(Palette.WoodDark, Offset(pos.x - 8f, pos.y - 3.5f), Size(16f, 7f))
            drawRect(Palette.Outline, Offset(pos.x - 12f, pos.y - 6f), Size(1.2f, 12f))
            drawRect(Palette.Outline, Offset(pos.x + 10.8f, pos.y - 6f), Size(1.2f, 12f))
            drawRect(Palette.WoodDark, Offset(pos.x - 11f, pos.y + 3.5f), Size(22f, 1.5f))
        }
        // Riders stay upright — a person rotated to the hull looks broken at most angles.
        for (rider in 0 until 2) {
            val rx = pos.x - 4f + rider * 8f
            drawRect(VisitorShirts[(boat + rider * 3) % VisitorShirts.size], Offset(rx - 4.5f, pos.y - 6f), Size(9f, 4f))
            drawCircle(Palette.Skin, 3.2f, Offset(rx, pos.y - 8f))
        }
    }
}

private fun DrawScope.drawRollerCoaster(structure: Structure) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val ground = Iso.toScreen(structure.centerCol(), structure.centerRow())
    val peak = structure.item.blockHeight

    // Station at the foot of the hill: a paved platform under a striped canopy, so the
    // train has somewhere it visibly starts and stops.
    val stCol = col + w * 0.20f
    val stRow = row + h * 0.54f
    val stW = w * 0.60f
    val stH = h * 0.36f
    isoBlock(
        stCol, stRow, stW, stH, 5f,
        Palette.PathA, Palette.PathEdge, Palette.PathB,
        outline = true,
        topTex = TextureAtlas.path(1, 0),
        sideTex = TextureAtlas.bricks(Palette.BrickRed, Palette.Mortar),
        sideScale = 1.6f
    )
    val stationBase = corners(stCol, stRow, stW, stH)
    pyramidRoof(
        Offset(stationBase[0].x, stationBase[0].y - 5f),
        Offset(stationBase[1].x, stationBase[1].y - 5f),
        Offset(stationBase[2].x, stationBase[2].y - 5f),
        Offset(stationBase[3].x, stationBase[3].y - 5f),
        apexHeight = 17f,
        colorA = Palette.RoofRed,
        colorB = Palette.RoofCream,
        slices = 5
    )

    // A closed corkscrew standing straight on the lawn: three turns that tighten and
    // climb to a peak at the halfway point, then unwind back to where they started.
    // The old version laid two crossing rails on a brick plinth, which read as a slab
    // with sticks on it; the reference's coaster is a blue spiral you can see through.
    val halfW = Iso.HALF_W * w * 0.74f
    val halfH = halfW / 2f      // 2:1 dimetric keeps the ellipse honest
    val turns = 3f
    val steps = 96
    val startAngle = PI / 2.0   // begin at the front of the plot, nearest the camera

    val points = ArrayList<Offset>(steps + 1)
    val heights = FloatArray(steps + 1)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val a = startAngle + t * turns * 2.0 * PI
        val lift = sin(PI * t.toDouble()).toFloat()   // 0 at both ends, 1 at the peak
        val shrink = 1f - 0.45f * lift
        val y = (ground.y + (sin(a) * halfH * shrink) - peak * lift).toFloat()
        points += Offset(ground.x + (cos(a) * halfW * shrink).toFloat(), y)
        heights[i] = peak * lift
    }

    // Timber supports first, so the track always sits in front of its own scaffolding.
    for (i in 0 until steps) {
        val rise = heights[i]
        if (rise < 7f || i % 5 != 0) continue
        val p = points[i]
        val groundY = p.y + rise
        drawRect(Palette.WoodDark, Offset(p.x - 1.6f, p.y), Size(3.2f, rise))
        drawLine(Palette.Wood, Offset(p.x - 1.6f, p.y), Offset(p.x - 1.6f, groundY), strokeWidth = 1f)
        // Cross-brace, so the post reads as scaffolding rather than a stick.
        drawLine(
            Palette.WoodDark,
            Offset(p.x - 5f, groundY - 3f),
            Offset(p.x + 5f, groundY - 10f),
            strokeWidth = 1.4f
        )
    }

    // The rail itself: box girder, painted steel, then a highlight along the top.
    for (i in 0 until steps) {
        val a = points[i]
        val b = points[i + 1]
        drawLine(Palette.Outline, a, b, strokeWidth = 6f)
        drawLine(Palette.RoofBlue, a, b, strokeWidth = 4f)
        drawLine(Color(0xFF8CB6F2), Offset(a.x, a.y - 1f), Offset(b.x, b.y - 1f), strokeWidth = 1.2f)
    }
    // Sleepers marching along the rail — the detail that makes it a track.
    for (i in 0 until steps step 3) {
        val a = points[i]
        val b = points[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * 5f
        val ny = dx / len * 5f
        drawLine(
            Palette.WoodDark,
            Offset(a.x - nx, a.y - ny),
            Offset(a.x + nx, a.y + ny),
            strokeWidth = 2f
        )
    }

    // Train of three cars running the loop.
    val cycle = ((structure.animAngle / 360f) % 1f + 1f) % 1f
    for (car in 0 until 3) {
        val t = cycle - car * 0.028f
        if (t < 0f) continue
        val index = (t * steps).toInt().coerceIn(0, steps)
        val pos = points[index]
        val body = if (car == 0) Palette.RoofRed else Palette.RoofCream
        drawRect(Palette.Outline, Offset(pos.x - 8f, pos.y - 9f), Size(16f, 9f))
        drawRect(body, Offset(pos.x - 7f, pos.y - 8f), Size(14f, 7f))
        drawRect(Palette.GlassLight, Offset(pos.x - 5f, pos.y - 7f), Size(10f, 3f))
        drawRect(Palette.RoofRed, Offset(pos.x - 7f, pos.y - 11f), Size(14f, 3f))
    }

    // Pennant on the highest rail.
    val flag = TextureAtlas.flag(Palette.RoofRed)
    blit(
        flag,
        Offset(ground.x - flag.width / 2f, ground.y - peak - flag.height - 4f),
        Size(flag.width.toFloat(), flag.height.toFloat())
    )
}

/**
 * Wall paint per shop. Every stall in the reference wears its own colour; painting them
 * all cream is what made a row of huts read as one repeated brown box.
 */
private fun shopWalls(item: BuildItem): Color = when (item) {
    BuildItem.BURGER_BAR -> Palette.WallCream
    BuildItem.SODA_STAND -> Palette.WallPink
    BuildItem.ICE_CREAM -> Color(0xFFDCEBF2)
    BuildItem.GIFT_SHOP -> Color(0xFFF3D98A)
    BuildItem.RESTROOM -> Palette.WallStone
    else -> Palette.WallCream
}

/**
 * A shop: stone plinth, painted boards, a serving hatch under a striped awning, a tall
 * candy-striped roof and — the detail that sells it — the thing it sells sat on top.
 *
 * Proportions are keyed off [BuildItem.blockHeight] rather than fixed pixels so a taller
 * shop gets taller walls *and* a taller roof; that is what puts these buildings at the
 * scale the reference draws them.
 */
private fun DrawScope.drawShopHut(structure: Structure, roof: Color, measurer: TextMeasurer) {
    val col = structure.col.toFloat()
    val row = structure.row.toFloat()
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val base = corners(col, row, w, h)
    val item = structure.item
    val paint = shopWalls(item)
    val plinth = 4f
    val walls = item.blockHeight * 0.52f
    val wallTop = plinth + walls

    // 1. Stone plinth, so the hut does not look glued to the grass.
    isoBlock(
        col, row, w, h, plinth,
        Palette.WallStone, Palette.WallStone, Palette.WallStone,
        outline = true,
        sideTex = TextureAtlas.plates(Palette.WallStone)
    )

    // 2. Boarded walls in the shop's own paint.
    isoBlock(
        col, row, w, h, wallTop,
        paint, paint, paint,
        outline = false,
        sideTex = TextureAtlas.siding(paint)
    )

    // 3. Serving hatch on the shaded face, with a counter to lean on.
    facePanel(
        base[3], base[2], 0.16f, 0.84f,
        up0 = plinth + walls * 0.42f, up1 = plinth + walls * 0.88f,
        fill = Palette.Outline,
        frame = Palette.WoodDark
    )
    facePanel(
        base[3], base[2], 0.14f, 0.86f,
        up0 = plinth + walls * 0.32f, up1 = plinth + walls * 0.42f,
        fill = Palette.Wood, frame = Palette.WoodDark
    )
    // Striped awning over the counter, in the shop's own colours. It is a *band* across
    // the head of the wall — an earlier version spanned the whole face and quietly ate the
    // serving hatch underneath it.
    val awning = TextureAtlas.stripes(roof, Palette.RoofCream)
    texturedFace(
        facePoint(base[3], base[2], 0f, wallTop - 8f),
        facePoint(base[3], base[2], 1f, wallTop - 8f),
        facePoint(base[3], base[2], 1f, wallTop + 3f),
        facePoint(base[3], base[2], 0f, wallTop + 3f),
        awning, pixelScale = 2.5f
    )
    // Valance hanging under it.
    quad(
        facePoint(base[3], base[2], 0f, wallTop - 11f),
        facePoint(base[3], base[2], 1f, wallTop - 11f),
        facePoint(base[3], base[2], 1f, wallTop - 8f),
        facePoint(base[3], base[2], 0f, wallTop - 8f),
        shade(roof, 0.35f)
    )

    // 4. Windows on the lit face.
    facePanel(
        base[2], base[1], 0.20f, 0.48f,
        up0 = plinth + walls * 0.46f, up1 = plinth + walls * 0.92f,
        fill = Palette.GlassLight, frame = Palette.WoodDark
    )
    facePanel(
        base[2], base[1], 0.54f, 0.82f,
        up0 = plinth + walls * 0.46f, up1 = plinth + walls * 0.92f,
        fill = Palette.Glass, frame = Palette.WoodDark
    )

    // 5. A tall candy-striped roof — the silhouette that says "fairground kiosk".
    val roofTop = Offset(base[0].x, base[0].y - wallTop)
    val roofRight = Offset(base[1].x, base[1].y - wallTop)
    val roofBottom = Offset(base[2].x, base[2].y - wallTop)
    val roofLeft = Offset(base[3].x, base[3].y - wallTop)
    val apexHeight = item.blockHeight * 0.95f
    pyramidRoof(
        roofTop, roofRight, roofBottom, roofLeft,
        apexHeight = apexHeight,
        colorA = roof,
        colorB = Palette.RoofCream,
        slices = 8
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

    // 6. Name board hung under the eave — lettering fitted to the board, never past it.
    signBoard(
        Offset(apexGround.x, roofBottom.y - 4f),
        width = w * Iso.TILE_W * 0.66f,
        height = 13f,
        text = shortLabel(item),
        measurer = measurer
    )

    // 7. The product, hoisted onto the ridge and bobbing gently.
    val prop = TextureAtlas.roofProp(item)
    val bob = sin(structure.ageSeconds * 1.3f) * 1.5f
    val propScale = 1.4f
    val pw = prop.width * propScale
    val ph = prop.height * propScale
    blit(
        prop,
        Offset(
            apexGround.x - pw / 2f,
            apexGround.y - apexHeight - ph + 2f - bob
        ),
        Size(pw, ph)
    )
}

private fun DrawScope.drawFountain(structure: Structure) {
    val w = structure.wTiles.toFloat()
    val h = structure.hTiles.toFloat()
    val centre = Iso.toScreen(structure.centerCol(), structure.centerRow())

    // A round stone basin, not a paved plot: filling the whole 2x2 footprint with marble
    // left a big bone-white diamond on the lawn, which is exactly what the reference
    // fountains are not. A circle projects to a 2:1 ellipse in this projection.
    val marble = Color(0xFFE8DEC6)
    val marbleDark = shade(marble, 0.28f)
    val radius = Iso.HALF_W * footprintExpand(w, h) * 0.82f   // world px, along x
    val halfH = radius / 2f
    val rimTop = Offset(centre.x - radius, centre.y - halfH)
    val rimSize = Size(radius * 2f, halfH * 2f)

    drawOval(Palette.Shadow, rimTop, rimSize)
    // Basin wall, then the water sitting down inside it.
    drawOval(marbleDark, Offset(rimTop.x, rimTop.y - 9f), rimSize)
    drawOval(Palette.Outline, Offset(rimTop.x, rimTop.y - 9f), rimSize, style = Stroke(width = 1.6f))
    drawOval(marble, rimTop, rimSize)
    val inner = 0.86f
    val innerTop = Offset(
        centre.x - radius * inner,
        centre.y - halfH * inner
    )
    val innerSize = Size(radius * 2f * inner, halfH * 2f * inner)
    drawOval(Palette.WaterDeep, innerTop, innerSize)
    drawOval(Palette.WaterMid, Offset(innerTop.x + 2f, innerTop.y + 1.5f), Size(innerSize.width - 4f, innerSize.height - 3f))
    // Ripples and foam flecks across the water.
    repeat(5) { i ->
        val a = i * 72.0 * PI / 180.0
        drawCircle(
            Palette.WaterFoam.copy(alpha = 0.6f),
            1.8f,
            Offset(centre.x + (cos(a) * radius * 0.52f).toFloat(), centre.y + (sin(a) * halfH * 0.52f).toFloat())
        )
    }
    drawOval(Palette.Outline, rimTop, rimSize, style = Stroke(width = 1.4f))

    // Tiered stone pedestal standing in the water.
    val pool = Offset(centre.x, centre.y + 1f)
    drawOval(marbleDark, Offset(pool.x - 13f, pool.y - 6f), Size(26f, 12f))
    drawOval(marble, Offset(pool.x - 12f, pool.y - 7f), Size(24f, 12f))
    drawOval(Palette.WaterLight, Offset(pool.x - 9f, pool.y - 5f), Size(18f, 9f))
    drawRect(marbleDark, Offset(pool.x - 5f, pool.y - 22f), Size(10f, 18f))
    drawRect(marble, Offset(pool.x - 5f, pool.y - 22f), Size(7f, 18f))
    drawOval(marble, Offset(pool.x - 11f, pool.y - 30f), Size(22f, 11f))
    drawOval(Palette.Outline, Offset(pool.x - 11f, pool.y - 30f), Size(22f, 11f), style = Stroke(width = 1.2f))

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
    val crown = 13f + (detail % 4)
    val cx = center.x + lean
    val cy = center.y - 27f

    drawRect(Palette.Trunk, Offset(center.x - 3f, center.y - 22f), Size(6f, 22f))
    drawRect(shade(Palette.Trunk, 0.35f), Offset(center.x + 1.5f, center.y - 22f), Size(2.5f, 22f))
    drawRect(Palette.WoodDark, Offset(center.x - 5f, center.y - 24f), Size(10f, 3f))

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

    // A clipped hedge border with a turned bed inside it: a bare brown diamond read as a
    // hole in the lawn, and the reference's beds are planted right out to the edge.
    diamond(center, 0.78f, Palette.Hedge)
    diamondStroke(center, 0.78f, shade(Palette.Hedge, 0.4f))
    diamond(center, 0.64f, Color(0xFF6B4A2C))
    diamond(center, 0.5f, Color(0xFF87613C))
    diamondStroke(center, 0.5f, Palette.WallStone)
    for (i in 0 until 11) {
        val angle = (detail + i * 47) % 360 * PI / 180.0
        val radius = 3f + (i % 4) * 4.5f
        val fx = center.x + (cos(angle) * radius).toFloat()
        val fy = center.y + (sin(angle) * radius * 0.62f).toFloat()
        drawRect(Palette.LeafMid, Offset(fx - 2f, fy - 1f), Size(4f, 3f))
        drawRect(Palette.LeafDark, Offset(fx - 2f, fy + 1f), Size(4f, 1.5f))
        drawRect(petals[(detail + i) % petals.size], Offset(fx - 2f, fy - 4.5f), Size(4.5f, 4f))
        drawRect(Color.White.copy(alpha = 0.55f), Offset(fx - 1f, fy - 3.5f), Size(1.5f, 1.5f))
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

    fitText(
        center = Offset(barLeft + barWidth / 2f, barY - 10.5f),
        text = state.parkName.uppercase(),
        maxWidth = barWidth - 14f,
        maxHeight = 12f,
        colour = Palette.Gold,
        measurer = textMeasurer,
        baseSp = 11
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

    // "BUS ➔" road marking on tarmac, sized to the carriageway.
    fitText(
        center = at(0.12f, centreDepth),
        text = "BUS ➔",
        maxWidth = 120f,
        maxHeight = 30f,
        colour = Color(0xFFE9E4D0),
        measurer = textMeasurer,
        baseSp = 14
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
        // ParkIcons crops the result to whatever is actually drawn, so this anchor only
        // has to keep the *ground* on screen; the headroom below it is free.
        translate(size.width / 2f - base.x * scale, size.height * 0.78f - base.y * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        if (item.isTerrainBrush) {
            drawIconTerrain(item, map)
        } else {
            drawStructure(Structure(id = "icon", item = item, col = 0, row = 0), map, measurer)
        }
    }

    if (item == BuildItem.BULLDOZE) {
        // A crossed-out swatch reads instantly as "get rid of this". It sits on the same
        // anchor the swatch was drawn from, or the cross floats above the land it kills.
        val cx = size.width / 2f
        val cy = size.height * 0.78f
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
