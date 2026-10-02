package com.example.parkbuilder.ui

import androidx.compose.ui.graphics.Color
import com.example.parkbuilder.game.model.BuildItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * A rectangular ARGB pixel image, row-major.
 *
 * Everything the park is made of is written into one of these a pixel at a time, then
 * handed to [TextureAtlas] to become an `ImageBitmap`. Working on raw ints keeps the art
 * crisp — a real `Canvas` would anti-alias every edge and quietly melt the pixel look.
 */
internal class PixelImage(val w: Int, val h: Int) {
    val pixels = IntArray(w * h)

    /** Writes one pixel, ignoring anything off the edge. */
    fun set(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        pixels[y * w + x] = argb
    }

    fun get(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= w || y >= h) 0 else pixels[y * w + x]

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        for (y in y0..y1) {
            for (x in x0..x1) set(x, y, argb)
        }
    }

    /** Filled disc — wheels, tree crowns, fountain bowls. */
    fun disc(cx: Float, cy: Float, radius: Float, argb: Int) {
        val minX = (cx - radius).toInt() - 1
        val maxX = (cx + radius).toInt() + 1
        val minY = (cy - radius).toInt() - 1
        val maxY = (cy + radius).toInt() + 1
        val r2 = radius * radius
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (dx * dx + dy * dy <= r2) set(x, y, argb)
            }
        }
    }

    /** Thick rasterised line, used for diamond edges and rails. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Int, argb: Int) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1)
        val half = thickness / 2
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = (x0 + (x1 - x0) * t).toInt()
            val y = (y0 + (y1 - y0) * t).toInt()
            rect(x - half, y - half, x - half + thickness - 1, y - half + thickness - 1, argb)
        }
    }

    /** Number of distinct colours, used by the tests to prove a texture is not flat. */
    fun distinctColours(): Int {
        val seen = HashSet<Int>()
        pixels.forEach { if (it != 0) seen += it }
        return seen.size
    }
}

/** Packs a Compose [Color] into a non-premultiplied ARGB int. */
internal fun Color.packed(alpha: Float = this.alpha): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val r = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val g = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val b = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Mixes two colours and packs the result — handy for shading a base tone. */
internal fun mixArgb(a: Color, b: Color, t: Float): Int =
    Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        1f
    ).packed()

/**
 * Every texture and sprite in the park, generated in code.
 *
 * The park used to be flat vector polygons, which is exactly what "solid colours" means.
 * The original game's look came from bitmaps: dithered grass, cobbled paths with a raised
 * curb, brick walls, shingle roofs, striped awnings, pixel vehicles. So we generate those
 * bitmaps here, and the renderer blits them with nearest-neighbour filtering.
 *
 * All of this is pure Kotlin over [IntArray]s, so it is unit-testable on the JVM —
 * [TextureAtlas] is the only part that touches Android.
 */
internal object PixelArt {

    /** Tile texture size. Exactly one isometric diamond. */
    const val TILE_W = 64
    const val TILE_H = 64

    const val GRASS_VARIANTS = 9
    const val PATH_VARIANTS = 4
    const val WATER_VARIANTS = 4
    const val QUEUE_VARIANTS = 2

    /** Edge bits: which sides of a tile have no same-surface neighbour. */
    const val EDGE_NW = 1
    const val EDGE_NE = 2
    const val EDGE_SE = 4
    const val EDGE_SW = 8

    /** Material textures are 16x16 unless the caller says otherwise. */
    const val MATERIAL = 16

    /**
     * Tiles are drawn a few percent past their own diamond so neighbouring tiles overlap
     * by a pixel. Without that you get grass-coloured hairlines between every two tiles
     * once the camera zooms in.
     */
    private const val FILL = 1.07f

    // Diamond corners in bitmap pixels.
    private const val NX = 31f
    private const val NY = 0f
    private const val EX = 63f
    private const val EY = 16f
    private const val SX = 31f
    private const val SY = 31f
    private const val WX = 0f
    private const val WY = 16f
    private const val CX = TILE_W / 2f - 0.5f
    private const val CY = TILE_H / 2f - 0.5f

    fun inDiamond(x: Int, y: Int): Boolean {
        val dx = abs(x + 0.5f - CX)
        val dy = abs(y + 0.5f - 16f)
        return (dx / (TILE_W / 2f) + dy / 16f) <= FILL
    }

    /** Cheap coordinate hash, so every tile's speckle is stable but unique. */
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h shr 13)) * 1274126177
        return (h xor (h shr 16)) and 0x7fffffff
    }

    // ==================================================================
    // Terrain
    // ==================================================================

    /**
     * Woven, dithered grass — the light/dark 2x2 checker the original uses, broken up by
     * darker clumps, brighter blades and the odd flower head.
     */
    fun grassTile(variant: Int): PixelImage {
        val image = PixelImage(TILE_W, TILE_H)
        val a = Palette.GrassA.packed()
        val b = Palette.GrassB.packed()
        val c = Palette.GrassC.packed()
        val dark = Palette.GrassDark.packed()
        val light = Palette.GrassLight.packed()
        val tuft = Palette.GrassTuft.packed()

        for (y in 0 until TILE_H) {
            for (x in 0 until TILE_W) {
                if (!inDiamond(x, y)) continue
                val checker = (((x shr 1) + (y shr 1)) and 1) == 0
                val h = hash(x, y, variant)
                image.set(
                    x, y, when {
                        h % 53 == 0 -> tuft
                        h % 37 == 0 -> dark
                        h % 23 == 0 -> light
                        h % 17 == 0 -> c
                        checker -> a
                        else -> b
                    }
                )
            }
        }

        val rng = Random(variant * 1013 + 7)
        repeat(7) {
            val x = 4 + rng.nextInt(TILE_W - 8)
            val y = 4 + rng.nextInt(TILE_H - 8)
            if (!inDiamond(x, y - 2)) return@repeat
            image.set(x, y, dark)
            image.set(x, y - 1, dark)
            image.set(x, y - 2, light)
        }
        // A couple of flower heads per tile, like the reference's planted borders.
        repeat(2) {
            val x = 6 + rng.nextInt(TILE_W - 14)
            val y = 6 + rng.nextInt(TILE_H - 12)
            if (!inDiamond(x, y + 2)) return@repeat
            val petal = if (rng.nextBoolean()) Palette.Gold.packed() else FLOWER_PINK
            image.rect(x, y, x + 1, y + 1, petal)
            image.set(x, y + 2, Palette.GrassTuft.packed())
            image.set(x + 1, y + 2, Palette.GrassTuft.packed())
        }
        return image
    }

    /**
     * Paved tile: slabs with joints, plus a raised curb on any side that faces something
     * other than path. The curb is what makes a path read as a *built* walkway rather than
     * a beige smear, and it is baked in per edge-mask so it stays pixel crisp.
     */
    fun pathTile(variant: Int, edgeMask: Int): PixelImage {
        val image = PixelImage(TILE_W, TILE_H)
        val a = Palette.PathA.packed()
        val b = Palette.PathB.packed()
        val dark = Palette.PathDark.packed()
        val speck = Palette.PathSpeck.packed()
        val joint = mixArgb(Palette.PathA, Palette.PathDark, 0.55f)

        for (y in 0 until TILE_H) {
            for (x in 0 until TILE_W) {
                if (!inDiamond(x, y)) continue
                val course = y / 3
                val shift = if (course % 2 == 0) 0 else 2
                val onJoint = ((y % 3 == 0) || ((x + shift) % 4 == 0)) && hash(x, y, variant * 7) % 4 != 0
                val h = hash((x + shift) / 4, course, variant * 31 + 5)
                image.set(
                    x, y, when {
                        onJoint -> joint
                        h % 13 == 0 -> speck
                        h % 3 == 0 -> a
                        else -> b
                    }
                )
            }
        }
        curb(image, edgeMask, Palette.Curb, Palette.CurbLight)
        return image
    }

    /**
     * Queue line: a paved channel with a hand rail down both long sides. [alongCol] says
     * which way the line runs so the rails go on the flanking edges, not across the line.
     */
    fun queueTile(variant: Int, alongCol: Boolean): PixelImage {
        val image = PixelImage(TILE_W, TILE_H)
        val floorA = mixArgb(Palette.PathA, Palette.PathDark, 0.45f)
        val floorB = mixArgb(Palette.PathB, Palette.PathDark, 0.3f)
        val stripe = mixArgb(Palette.PathA, Palette.PathSpeck, 0.5f)

        for (y in 0 until TILE_H) {
            for (x in 0 until TILE_W) {
                if (!inDiamond(x, y)) continue
                val h = hash(x, y, variant * 61 + 11)
                // A lighter stripe straight down the middle of the channel.
                val mid = if (alongCol) {
                    abs((x + 0.5f) - TILE_W / 2f) < 5f
                } else {
                    abs((y + 0.5f) - TILE_H / 2f) < 4f
                }
                image.set(
                    x, y, when {
                        h % 17 == 0 -> stripe
                        h % 5 == 0 -> floorB
                        mid -> mixArgb(Palette.PathA, Palette.PathSpeck, 0.35f)
                        else -> floorA
                    }
                )
            }
        }

        val rail = Palette.Metal.packed()
        val railDark = Palette.MetalDark.packed()
        val post = Palette.Outline.packed()
        val edges = if (alongCol) {
            // Line runs along columns, so the rails sit on the NE and SW sides.
            listOf(
                floatArrayOf(NX, NY, EX, EY),
                floatArrayOf(SX, SY, WX, WY)
            )
        } else {
            listOf(
                floatArrayOf(NX, NY, WX, WY),
                floatArrayOf(EX, EY, SX, SY)
            )
        }
        edges.forEachIndexed { index, e ->
            // Drop the rail line a touch inside the tile edge so it reads as standing on
            // the ground rather than drawn on the boundary.
            val x0 = e[0] + (CX - e[0]) * 0.10f
            val y0 = e[1] + (CY - e[1]) * 0.10f
            val x1 = e[2] + (CX - e[2]) * 0.10f
            val y1 = e[3] + (CY - e[3]) * 0.10f
            image.line(x0, y0, x1, y1, 1, rail)
            image.line(x0, y0 + 1f, x1, y1 + 1f, 1, railDark)
            val posts = if (index == 0) 2 else 3
            for (p in 0..posts) {
                val t = p.toFloat() / posts
                val px = (x0 + (x1 - x0) * t).toInt()
                val py = (y0 + (y1 - y0) * t).toInt()
                image.rect(px - 1, py - 2, px, py + 2, post)
            }
        }
        return image
    }

    /** Open water: horizontal wave bands with a dark shoreline where land touches it. */
    fun waterTile(variant: Int, edgeMask: Int): PixelImage {
        val image = PixelImage(TILE_W, TILE_H)
        val deep = Palette.WaterDeep.packed()
        val mid = Palette.WaterMid.packed()
        val light = Palette.WaterLight.packed()

        for (y in 0 until TILE_H) {
            for (x in 0 until TILE_W) {
                if (!inDiamond(x, y)) continue
                val band = (y + variant * 3) % 7
                val h = hash(x, y, variant * 17 + 3)
                image.set(
                    x, y, when {
                        band == 0 -> light
                        band == 3 -> deep
                        h % 47 == 0 -> light
                        h % 11 == 0 -> deep
                        else -> mid
                    }
                )
            }
        }
        curb(image, edgeMask, Palette.WaterDark, Palette.WaterFoam)
        return image
    }

    /** Dark rim plus a foam line, used for both pavement curbs and shorelines. */
    private fun curb(image: PixelImage, edgeMask: Int, dark: Color, light: Color) {
        if (edgeMask == 0) return
        val darkArgb = dark.packed()
        val lightArgb = light.packed()

        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            image.line(x0, y0, x1, y1, 2, darkArgb)
            // The highlight sits just inside the edge, which is what gives the kerb its
            // little 3D lip in the original's paths.
            image.line(
                x0 + (CX - x0) * 0.09f,
                y0 + (CY - y0) * 0.09f,
                x1 + (CX - x1) * 0.09f,
                y1 + (CY - y1) * 0.09f,
                1,
                lightArgb
            )
        }

        if (edgeMask and EDGE_NW != 0) edge(NX, NY, WX, WY)
        if (edgeMask and EDGE_NE != 0) edge(NX, NY, EX, EY)
        if (edgeMask and EDGE_SE != 0) edge(EX, EY, SX, SY)
        if (edgeMask and EDGE_SW != 0) edge(SX, SY, WX, WY)
    }

    // ==================================================================
    // Building materials
    // ==================================================================

    /** Brick courses with staggered joints — shop and entrance walls. */
    fun bricks(base: Color, mortar: Color, seed: Int): PixelImage {
        val image = PixelImage(MATERIAL, MATERIAL)
        val brick = base.packed()
        val dark = mixArgb(base, Color.Black, 0.24f)
        val light = mixArgb(base, Color.White, 0.16f)
        val joint = mortar.packed()
        for (y in 0 until MATERIAL) {
            val course = y / 4
            val shift = if (course % 2 == 0) 0 else 4
            for (x in 0 until MATERIAL) {
                val onJoint = (y % 4 == 3) || ((x + shift) % 8 == 0)
                image.set(
                    x, y, when {
                        onJoint -> joint
                        hash(x, y, seed) % 13 == 0 -> dark
                        hash(x, y, seed) % 29 == 0 -> light
                        else -> brick
                    }
                )
            }
        }
        return image
    }

    /** Horizontal weatherboard, for kiosks and the ticket booth. */
    fun siding(base: Color, seed: Int): PixelImage {
        val image = PixelImage(MATERIAL, MATERIAL)
        val tone = base.packed()
        val dark = mixArgb(base, Color.Black, 0.2f)
        val light = mixArgb(base, Color.White, 0.14f)
        for (y in 0 until MATERIAL) {
            for (x in 0 until MATERIAL) {
                val board = y % 4
                image.set(
                    x, y, when {
                        board == 3 -> dark
                        board == 0 -> light
                        hash(x, y, seed) % 19 == 0 -> dark
                        else -> tone
                    }
                )
            }
        }
        return image
    }

    /** Vertical planks with grain — wood huts, flume troughs, fences. */
    fun planks(base: Color, seed: Int): PixelImage {
        val image = PixelImage(MATERIAL, MATERIAL)
        val dark = mixArgb(base, Color.Black, 0.34f)
        val mid = mixArgb(base, Color.Black, 0.14f)
        val light = mixArgb(base, Color.White, 0.14f)
        // Each plank is cut from a slightly different board, so no two are identical.
        val boards = intArrayOf(
            base.packed(),
            mid,
            mixArgb(base, Color.Black, 0.06f)
        )
        for (y in 0 until MATERIAL) {
            for (x in 0 until MATERIAL) {
                val tone = boards[(x / 5) % boards.size]
                image.set(
                    x, y, when {
                        x % 5 == 0 -> dark
                        y % 8 == 0 -> mid
                        hash(x, y / 2, seed) % 23 == 0 -> light
                        else -> tone
                    }
                )
            }
        }
        return image
    }

    /**
     * Scalloped roof shingles, 32x16 so a whole roof face can take one stretch of texture
     * without visibly repeating.
     */
    fun shingles(base: Color, seed: Int): PixelImage {
        val image = PixelImage(32, 16)
        val tone = base.packed()
        val dark = mixArgb(base, Color.Black, 0.3f)
        val mid = mixArgb(base, Color.Black, 0.14f)
        val light = mixArgb(base, Color.White, 0.2f)
        for (y in 0 until 16) {
            val row = y / 4
            val shift = if (row % 2 == 0) 0 else 4
            for (x in 0 until 32) {
                val scallop = (x + shift) % 8
                val bottomOfRow = y % 4 == 3
                image.set(
                    x, y, when {
                        bottomOfRow -> dark
                        scallop == 0 -> dark
                        y % 4 == 0 -> light
                        hash(x, row, seed) % 17 == 0 -> dark
                        hash(x, row, seed) % 23 == 0 -> mid
                        else -> tone
                    }
                )
            }
        }
        return image
    }

    /**
     * Wide vertical stripes — awnings, canopies, big tops.
     *
     * Each stripe carries a shaded edge and a hem along the bottom, which is what makes a
     * canvas awning look like cloth rather than a flat two-colour bar.
     */
    fun stripes(a: Color, b: Color): PixelImage {
        val image = PixelImage(8, 16)
        val first = a.packed()
        val firstEdge = mixArgb(a, Color.Black, 0.24f)
        val second = b.packed()
        val secondEdge = mixArgb(b, Color.Black, 0.2f)
        val hem = mixArgb(a, Color.Black, 0.45f)
        for (y in 0 until 16) {
            for (x in 0 until 8) {
                val left = (x / 2) % 2 == 0
                val edge = x % 2 == 1
                image.set(
                    x, y, when {
                        y >= 15 -> hem
                        y == 0 -> if (left) firstEdge else secondEdge
                        edge -> if (left) firstEdge else secondEdge
                        else -> if (left) first else second
                    }
                )
            }
        }
        return image
    }

    /** Riveted metal panels — ride machinery, flume supports, queue counters. */
    fun plates(base: Color, seed: Int): PixelImage {
        val image = PixelImage(MATERIAL, MATERIAL)
        val tone = base.packed()
        val dark = mixArgb(base, Color.Black, 0.3f)
        val light = mixArgb(base, Color.White, 0.18f)
        for (y in 0 until MATERIAL) {
            for (x in 0 until MATERIAL) {
                val panelEdge = (x % 8 == 0) || (y % 8 == 0)
                val rivet = (x % 8 == 2) && (y % 8 == 2)
                image.set(
                    x, y, when {
                        rivet -> light
                        panelEdge -> dark
                        hash(x, y, seed) % 23 == 0 -> dark
                        else -> tone
                    }
                )
            }
        }
        return image
    }

    /** Tarmac for the road outside the gate. */
    fun asphalt(seed: Int): PixelImage {
        val image = PixelImage(32, 32)
        val tone = Palette.Asphalt.packed()
        val deep = Palette.AsphaltDark.packed()
        val mid = mixArgb(Palette.Asphalt, Color.Black, 0.16f)
        val light = mixArgb(Palette.Asphalt, Color.White, 0.24f)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                image.set(
                    x, y, when {
                        hash(x, y, seed) % 37 == 0 -> light
                        // Two scales of grit: fine speckle inside coarser worn patches.
                        hash(x / 2, y / 2, seed + 5) % 3 == 0 -> mid
                        hash(x, y, seed) % 7 == 0 -> deep
                        else -> tone
                    }
                )
            }
        }
        return image
    }

    // ==================================================================
    // Vehicles and props
    // ==================================================================

    /** Side-on bus, like the one parked outside the gate in the reference. */
    fun bus(body: Color, roof: Color, seed: Int): PixelImage {
        val w = 64
        val h = 30
        val image = PixelImage(w, h)
        val shell = body.packed()
        val shellDark = mixArgb(body, Color.Black, 0.3f)
        val top = roof.packed()
        val glass = Palette.Glass.packed()
        val glassLight = Palette.GlassLight.packed()
        val tyre = TYRE
        val rim = Palette.Metal.packed()

        image.rect(2, 6, w - 3, h - 9, shell)
        image.rect(2, 6, w - 3, 8, top)
        image.rect(2, h - 11, w - 3, h - 9, top)
        image.rect(2, h - 9, w - 3, h - 8, shellDark)

        // Window band with a highlight along the top of each pane.
        for (i in 0 until 5) {
            val x = 6 + i * 11
            image.rect(x, 10, x + 8, 18, glassLight)
            image.rect(x, 12, x + 8, 18, glass)
            image.rect(x, 10, x + 8, 10, mixArgb(Palette.GlassLight, Color.White, 0.4f))
        }
        // Headlight and destination board.
        image.rect(2, 12, 4, 16, Palette.Gold.packed())
        image.rect(w - 8, 9, w - 4, 13, Palette.RoofCream.packed())

        image.disc(15f, (h - 6).toFloat(), 5f, tyre)
        image.disc(15f, (h - 6).toFloat(), 2.5f, rim)
        image.disc(46f, (h - 6).toFloat(), 5f, tyre)
        image.disc(46f, (h - 6).toFloat(), 2.5f, rim)

        // Windows need a little noise so the glass is not a flat rectangle.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (image.get(x, y) == glass && hash(x, y, seed) % 9 == 0) {
                    image.set(x, y, mixArgb(Palette.Glass, Color.White, 0.35f))
                }
            }
        }
        return image
    }

    /** Small saloon car, for the car park. */
    fun car(body: Color, seed: Int): PixelImage {
        val w = 34
        val h = 20
        val image = PixelImage(w, h)
        val shell = body.packed()
        val shellDark = mixArgb(body, Color.Black, 0.32f)
        val glass = Palette.Glass.packed()
        val tyre = TYRE
        val rim = Palette.Metal.packed()

        image.rect(2, 8, w - 3, 14, shell)
        image.rect(7, 3, w - 8, 9, shell)
        image.rect(8, 4, w - 9, 8, glass)
        image.rect(2, 13, w - 3, 15, shellDark)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (image.get(x, y) == shell && hash(x, y, seed) % 11 == 0) {
                    image.set(x, y, mixArgb(body, Color.White, 0.25f))
                }
            }
        }
        image.disc(8f, 15f, 3.5f, tyre)
        image.disc(8f, 15f, 1.5f, rim)
        image.disc(w - 9f, 15f, 3.5f, tyre)
        image.disc(w - 9f, 15f, 1.5f, rim)
        image.rect(2, 9, 4, 11, Palette.Gold.packed())
        return image
    }

/**
 * The park manager, for the corner of the toolbar — the little character portrait the
 * original keeps bottom-left of the screen.
 */
    fun portrait(skin: Color = Palette.Skin, hair: Color = Color(0xFFE0B34A)): PixelImage {
        val image = PixelImage(30, 34)
        val flesh = skin.packed()
        val fleshShade = mixArgb(skin, Color.Black, 0.22f)
        val mane = hair.packed()
        val jacket = Color(0xFF2F6FE8).packed()
        val jacketDark = mixArgb(Color(0xFF2F6FE8), Color.Black, 0.3f)
        val cap = Palette.RoofRed.packed()

        // Shoulders.
        image.rect(2, 24, 27, 33, jacket)
        image.rect(2, 30, 27, 33, jacketDark)
        image.rect(13, 24, 16, 29, Palette.RoofCream.packed())
        image.rect(14, 24, 15, 28, flesh)

        // Head and ears.
        image.rect(7, 6, 22, 25, flesh)
        image.set(6, 15, flesh)
        image.set(23, 15, flesh)
        image.rect(19, 8, 22, 24, fleshShade)

        // Hair and cap.
        image.rect(6, 4, 23, 9, mane)
        image.rect(5, 10, 7, 17, mane)
        image.rect(22, 10, 24, 17, mane)
        image.rect(5, 1, 24, 6, cap)
        image.rect(3, 6, 26, 8, cap)
        image.rect(5, 1, 24, 2, mixArgb(Palette.RoofRed, Color.White, 0.4f))

        // Face: brows, eyes, nose, a grin.
        image.rect(10, 12, 13, 13, Palette.Outline.packed())
        image.rect(17, 12, 20, 13, Palette.Outline.packed())
        image.rect(11, 14, 12, 15, 0xFF2E5C8A.toInt())
        image.rect(18, 14, 19, 15, 0xFF2E5C8A.toInt())
        image.rect(15, 15, 16, 18, fleshShade)
        image.rect(11, 20, 19, 21, mixArgb(Palette.Skin, Color.Black, 0.55f))
        image.rect(12, 19, 18, 19, Palette.Outline.packed())
        return image
    }

    /** A tall roof prop: what the shop is selling, silhouetted against the sky. */
    fun roofProp(item: BuildItem): PixelImage = when (item) {
        BuildItem.BURGER_BAR -> burger()
        BuildItem.SODA_STAND -> sodaCup()
        BuildItem.ICE_CREAM -> iceCream()
        BuildItem.GIFT_SHOP -> giftBox()
        BuildItem.RESTROOM -> restroomSign()
        else -> balloon()
    }

    fun flag(colour: Color): PixelImage {
        val image = PixelImage(14, 10)
        val cloth = colour.packed()
        val shade = mixArgb(colour, Color.Black, 0.25f)
        for (y in 0 until 10) {
            for (x in 0 until 14) {
                // A swallow-tail pennant: V-notch out of the free edge.
                val notch = abs(y - 4.5f) < (x - 6f) / 1.4f
                if (notch) continue
                image.set(x, y, if (y % 5 == 0) shade else cloth)
            }
        }
        return image
    }

    private fun burger(): PixelImage {
        val image = PixelImage(26, 22)
        image.disc(13f, 7f, 7f, BUN_TOP)
        image.rect(6, 7, 19, 9, BUN_TOP)
        image.rect(5, 9, 20, 10, LETTUCE)
        image.rect(6, 10, 19, 13, PATTY)
        image.rect(5, 13, 20, 16, BUN_BOTTOM)
        image.rect(7, 14, 18, 15, BUN_TOP)
        // Sesame seeds.
        image.rect(9, 4, 10, 5, SEED)
        image.rect(14, 3, 15, 4, SEED)
        image.rect(17, 6, 18, 7, SEED)
        return image
    }

    private fun sodaCup(): PixelImage {
        val image = PixelImage(22, 26)
        image.rect(5, 8, 16, 25, CUP_RED)
        image.rect(5, 8, 16, 9, Palette.RoofCream.packed())
        image.rect(7, 12, 14, 20, Palette.RoofCream.packed())
        // Straw, angled out of the lid.
        image.rect(10, 2, 11, 9, Palette.MetalDark.packed())
        image.rect(11, 2, 14, 3, Palette.MetalDark.packed())
        // Fizz.
        image.rect(4, 6, 17, 7, FOAM)
        return image
    }

    private fun iceCream(): PixelImage {
        val image = PixelImage(20, 26)
        // Cone.
        for (y in 12 until 26) {
            val half = ((26 - y) * 0.55f).toInt()
            image.rect(10 - half, y, 10 + half, y, CONE)
        }
        image.disc(10f, 10f, 6f, CREAM)
        image.disc(7f, 7f, 4f, PINK)
        image.disc(13f, 8f, 3f, CREAM)
        image.set(9, 3, CHERRY)
        image.set(10, 3, CHERRY)
        return image
    }

    private fun giftBox(): PixelImage {
        val image = PixelImage(22, 22)
        image.rect(3, 8, 18, 19, GIFT_BLUE)
        image.rect(0, 5, 21, 8, GIFT_BLUE_DARK)
        image.rect(9, 5, 12, 19, RIBBON)
        // Bow.
        image.disc(7f, 3f, 3f, RIBBON)
        image.disc(14f, 3f, 3f, RIBBON)
        return image
    }

    private fun restroomSign(): PixelImage {
        val image = PixelImage(20, 20)
        image.rect(1, 4, 18, 16, Palette.WallCream.packed())
        image.rect(1, 4, 18, 5, Palette.MetalDark.packed())
        // Two little pixel people, one in a skirt.
        image.rect(4, 8, 6, 13, Palette.RoofBlue.packed())
        image.set(5, 7, Palette.Skin.packed())
        image.rect(6, 7, 6, 7, Palette.Skin.packed())
        image.rect(12, 8, 14, 11, Palette.RoofRed.packed())
        image.rect(11, 11, 15, 13, Palette.RoofRed.packed())
        image.rect(12, 6, 14, 7, Palette.Skin.packed())
        return image
    }

    private fun balloon(): PixelImage {
        val image = PixelImage(22, 26)
        image.disc(8f, 8f, 6f, BALLOON_A)
        image.disc(15f, 6f, 5f, BALLOON_B)
        image.rect(7, 13, 8, 25, STRING)
        image.rect(14, 11, 15, 25, STRING)
        return image
    }

    // Shared sprite colours, kept here so the shapes above read plainly.
    private val FLOWER_PINK = 0xFFE8459B.toInt()
    private val TYRE = 0xFF23262C.toInt()
    private val BUN_TOP = 0xFFE0A63C.toInt()
    private val BUN_BOTTOM = 0xFFD0913E.toInt()
    private val LETTUCE = 0xFF5FBF3A.toInt()
    private val PATTY = 0xFF6E3B1E.toInt()
    private val SEED = 0xFFFFF0C0.toInt()
    private val CUP_RED = 0xFFD8352C.toInt()
    private val FOAM = 0xFFFFFFFF.toInt()
    private val CONE = 0xFFD9A24C.toInt()
    private val CREAM = 0xFFF7E9C4.toInt()
    private val PINK = 0xFFF08BB4.toInt()
    private val CHERRY = 0xFFD22B3A.toInt()
    private val GIFT_BLUE = 0xFF3A6FD8.toInt()
    private val GIFT_BLUE_DARK = 0xFF27509E.toInt()
    private val RIBBON = 0xFFE8402F.toInt()
    private val BALLOON_A = 0xFFE8402F.toInt()
    private val BALLOON_B = 0xFFF2C53D.toInt()
    private val STRING = 0xFF6B6255.toInt()
}
