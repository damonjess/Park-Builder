package com.example.parkbuilder.game.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor

/** A coordinate in the park's tile grid. */
data class TilePos(val col: Int, val row: Int)

/**
 * Classic 2:1 "dimetric" projection — the same maths every PS1-era park sim used.
 *
 * The world is a grid of diamond tiles. Increasing `col` walks down-right on screen and
 * increasing `row` walks down-left, so `col + row` is the on-screen depth of a tile.
 */
object Iso {

    /** Full width of one diamond tile, in unscaled world pixels. */
    const val TILE_W = 64f

    /** Full height of one diamond tile — exactly half the width, hence "2:1". */
    const val TILE_H = 32f

    const val HALF_W = TILE_W / 2f
    const val HALF_H = TILE_H / 2f

    fun toScreenX(col: Float, row: Float): Float = (col - row) * HALF_W

    fun toScreenY(col: Float, row: Float): Float = (col + row) * HALF_H

    fun toScreen(col: Float, row: Float): Offset = Offset(toScreenX(col, row), toScreenY(col, row))

    /** Fractional column for a world-space screen point (unscaled world units). */
    fun toCol(screenX: Float, screenY: Float): Float =
        (screenX / HALF_W + screenY / HALF_H) / 2f

    /** Fractional row for a world-space screen point (unscaled world units). */
    fun toRow(screenX: Float, screenY: Float): Float =
        (screenY / HALF_H - screenX / HALF_W) / 2f

    fun toTile(screenX: Float, screenY: Float): TilePos =
        TilePos(floor(toCol(screenX, screenY)).toInt(), floor(toRow(screenX, screenY)).toInt())
}
