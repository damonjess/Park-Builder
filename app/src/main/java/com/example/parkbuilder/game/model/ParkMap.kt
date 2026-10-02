package com.example.parkbuilder.game.model

/**
 * The park's terrain grid.
 *
 * This is deliberately *not* a `data class` holding a big list: it wraps a packed
 * [ByteArray] and edits are copy-on-write. The renderer reads it every frame for free,
 * and the engine only pays for a copy when the player actually paints a tile.
 */
class ParkMap private constructor(
    val cols: Int,
    val rows: Int,
    private val terrain: ByteArray
) {

    constructor(cols: Int, rows: Int) : this(
        cols = cols,
        rows = rows,
        terrain = ByteArray(cols * rows) { Terrain.GRASS.ordinal.toByte() }
    )

    val tileCount: Int get() = cols * rows

    fun inBounds(col: Int, row: Int): Boolean = col >= 0 && col < cols && row >= 0 && row < rows

    fun terrainAt(col: Int, row: Int): Terrain =
        if (!inBounds(col, row)) Terrain.GRASS
        else Terrain.entries[terrain[row * cols + col].toInt()]

    /** Returns a new map with [col], [row] repainted, or `this` if nothing changed. */
    fun withTerrain(col: Int, row: Int, value: Terrain): ParkMap {
        if (!inBounds(col, row) || terrainAt(col, row) == value) return this
        val next = terrain.copyOf()
        next[row * cols + col] = value.ordinal.toByte()
        return ParkMap(cols, rows, next)
    }

    fun withTiles(tiles: Iterable<TilePos>, value: Terrain): ParkMap {
        var result = this
        tiles.forEach { result = result.withTerrain(it.col, it.row, value) }
        return result
    }

    /** Paths and queue lines are both walked on; water, grass and buildings are not. */
    fun isWalkable(col: Int, row: Int): Boolean =
        terrainAt(col, row) == Terrain.PATH || terrainAt(col, row) == Terrain.QUEUE

    /**
     * Stable pseudo-random per-tile value in `0..99`.
     *
     * Used for grass shading, flower scatter and path speckling. Deriving it from the
     * coordinates instead of storing it keeps the map tiny and fully deterministic.
     */
    fun detailAt(col: Int, row: Int): Int {
        var h = col * 374761393 + row * 668265263
        h = (h xor (h shr 13)) * 1274126177
        return ((h xor (h shr 16)) and 0x7fffffff) % 100
    }
}
