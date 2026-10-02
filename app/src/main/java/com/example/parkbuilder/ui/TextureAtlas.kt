package com.example.parkbuilder.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * Turns the generated [PixelImage]s into `ImageBitmap`s, once each.
 *
 * Generating a tile is a few thousand writes, so doing it inside the draw call would cost
 * a hitch on the first frame and nothing thereafter — but the cache also means a park with
 * a thousand grass tiles shares nine bitmaps between them.
 *
 * Keys are cheap strings rather than the colour objects themselves: `Color` is a value
 * class with a `ULong` inside, so string keys keep the map trivial to reason about.
 */
internal object TextureAtlas {

    private val cache = HashMap<String, ImageBitmap>(64)

    private fun image(key: String, build: () -> PixelImage): ImageBitmap =
        cache.getOrPut(key) { build().toImageBitmap() }

    // ---- Terrain ------------------------------------------------------

    fun grass(variant: Int): ImageBitmap =
        image("grass$variant") { PixelArt.grassTile(variant) }

    fun path(variant: Int, edgeMask: Int): ImageBitmap =
        image("path$variant-$edgeMask") { PixelArt.pathTile(variant, edgeMask) }

    fun water(variant: Int, edgeMask: Int): ImageBitmap =
        image("water$variant-$edgeMask") { PixelArt.waterTile(variant, edgeMask) }

    fun queue(alongCol: Boolean): ImageBitmap =
        image("queue$alongCol") { PixelArt.queueTile(if (alongCol) 0 else 1, alongCol) }

    // ---- Materials ----------------------------------------------------

    fun bricks(base: Color, mortar: Color): ImageBitmap =
        image("bricks${base.value}-${mortar.value}") { PixelArt.bricks(base, mortar, seedFor(base)) }

    fun siding(base: Color): ImageBitmap =
        image("siding${base.value}") { PixelArt.siding(base, seedFor(base)) }

    fun planks(base: Color): ImageBitmap =
        image("planks${base.value}") { PixelArt.planks(base, seedFor(base)) }

    fun shingles(base: Color): ImageBitmap =
        image("shingles${base.value}") { PixelArt.shingles(base, seedFor(base)) }

    fun stripes(a: Color, b: Color): ImageBitmap =
        image("stripes${a.value}-${b.value}") { PixelArt.stripes(a, b) }

    fun plates(base: Color): ImageBitmap =
        image("plates${base.value}") { PixelArt.plates(base, seedFor(base)) }

    fun asphalt(): ImageBitmap =
        image("asphalt") { PixelArt.asphalt(seedFor(Palette.Asphalt)) }

    // ---- Vehicles and props -------------------------------------------

    fun bus(body: Color, roof: Color): ImageBitmap =
        image("bus${body.value}-${roof.value}") { PixelArt.bus(body, roof, seedFor(body)) }

    fun car(body: Color): ImageBitmap =
        image("car${body.value}") { PixelArt.car(body, seedFor(body)) }

    fun roofProp(item: com.example.parkbuilder.game.model.BuildItem): ImageBitmap =
        image("prop${item.ordinal}") { PixelArt.roofProp(item) }

    fun flag(colour: Color): ImageBitmap =
        image("flag${colour.value}") { PixelArt.flag(colour) }

    /** The manager's portrait for the toolbar. */
    fun portrait(): ImageBitmap = image("portrait") { PixelArt.portrait() }

    /** Stable per-colour seed so a given material always comes out the same. */
    private fun seedFor(colour: Color): Int = (colour.value.toLong() % 977L).toInt()

    private fun PixelImage.toImageBitmap(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap.asImageBitmap()
    }
}
