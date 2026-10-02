package com.example.parkbuilder.game.model

/** A need that builds up in a visitor and is satisfied by a shop or facility. */
enum class Need { HUNGER, THIRST, BLADDER }

enum class Terrain(val displayName: String) {
    GRASS("Grass"),
    PATH("Path"),
    WATER("Water")
}

enum class ToolCategory(val displayName: String) {
    RIDE("Rides"),
    SHOP("Shops"),
    FACILITY("Facilities"),
    SCENERY("Scenery"),
    TERRAIN("Land")
}

/**
 * Everything the player can buy.
 *
 * Terrain brushes carry a [terrain]; every other entry is placed on the map as a
 * [Structure] sized [wTiles] x [hTiles].
 */
enum class BuildItem(
    val displayName: String,
    val cost: Int,
    val category: ToolCategory,
    val wTiles: Int = 1,
    val hTiles: Int = 1,
    /** What one visitor pays each time they use it. */
    val price: Int = 0,
    /** Charged once per in-game day, whether or not anybody shows up. */
    val upkeep: Int = 0,
    /** 0..1 — how much this adds to a visitor's happiness and to the park rating. */
    val excitement: Float = 0f,
    /** How many visitors can be on it at once. */
    val capacity: Int = 0,
    val useSeconds: Float = 0f,
    val need: Need? = null,
    /** Visual height of the isometric block, in world pixels. */
    val blockHeight: Float = 18f,
    val terrain: Terrain? = null
) {
    // ---- Landscaping ------------------------------------------------------
    PATH("Path", 6, ToolCategory.TERRAIN, terrain = Terrain.PATH),
    WATER("Water", 45, ToolCategory.TERRAIN, terrain = Terrain.WATER),
    GRASS("Grass", 3, ToolCategory.TERRAIN, terrain = Terrain.GRASS),
    BULLDOZE("Demolish", 0, ToolCategory.TERRAIN),

    // ---- Rides ------------------------------------------------------------
    CAROUSEL(
        "Carousel", 320, ToolCategory.RIDE, 2, 2,
        price = 3, upkeep = 12, excitement = 0.45f, capacity = 8, useSeconds = 12f, blockHeight = 26f
    ),
    FERRIS_WHEEL(
        "Ferris Wheel", 780, ToolCategory.RIDE, 3, 3,
        price = 4, upkeep = 22, excitement = 0.60f, capacity = 10, useSeconds = 16f, blockHeight = 64f
    ),
    DODGEMS(
        "Dodgems", 1150, ToolCategory.RIDE, 3, 3,
        price = 5, upkeep = 30, excitement = 0.55f, capacity = 12, useSeconds = 14f, blockHeight = 22f
    ),
    LOG_FLUME(
        "Log Flume", 1650, ToolCategory.RIDE, 3, 4,
        price = 6, upkeep = 38, excitement = 0.70f, capacity = 9, useSeconds = 18f, blockHeight = 30f
    ),
    ROLLER_COASTER(
        "Coaster", 2600, ToolCategory.RIDE, 4, 4,
        price = 8, upkeep = 55, excitement = 0.90f, capacity = 14, useSeconds = 24f, blockHeight = 48f
    ),

    // ---- Shops ------------------------------------------------------------
    BURGER_BAR(
        "Burger Bar", 240, ToolCategory.SHOP,
        price = 6, upkeep = 8, need = Need.HUNGER, useSeconds = 6f, blockHeight = 28f
    ),
    SODA_STAND(
        "Soda Stand", 180, ToolCategory.SHOP,
        price = 4, upkeep = 6, need = Need.THIRST, useSeconds = 4f, blockHeight = 24f
    ),
    ICE_CREAM(
        "Ice Cream", 210, ToolCategory.SHOP,
        price = 5, upkeep = 7, need = Need.HUNGER, useSeconds = 5f, blockHeight = 26f
    ),
    GIFT_SHOP(
        "Gift Shop", 300, ToolCategory.SHOP,
        price = 9, upkeep = 10, excitement = 0.10f, useSeconds = 5f, blockHeight = 30f
    ),

    // ---- Facilities -------------------------------------------------------
    RESTROOM(
        "Restroom", 260, ToolCategory.FACILITY,
        price = 2, upkeep = 7, need = Need.BLADDER, useSeconds = 8f, blockHeight = 28f
    ),

    // ---- Scenery ----------------------------------------------------------
    FLOWERS("Flowers", 20, ToolCategory.SCENERY, blockHeight = 7f),
    BENCH("Bench", 40, ToolCategory.SCENERY, blockHeight = 10f),
    TREE("Tree", 30, ToolCategory.SCENERY, blockHeight = 42f),
    LAMP("Lamp", 55, ToolCategory.SCENERY, blockHeight = 52f),
    FOUNTAIN("Fountain", 250, ToolCategory.SCENERY, 2, 2, excitement = 0.15f, blockHeight = 14f);

    /** Rides, shops and facilities are the things visitors actually walk to. */
    val isAttraction: Boolean
        get() = category == ToolCategory.RIDE || category == ToolCategory.SHOP || category == ToolCategory.FACILITY

    val isTerrainBrush: Boolean get() = category == ToolCategory.TERRAIN
}

/** A building the player has placed on the map. */
data class Structure(
    val id: String,
    val item: BuildItem,
    val col: Int,
    val row: Int,
    val isSelected: Boolean = false,
    /** Shared animation phase for the spinning parts (wheels, carousels, trains). */
    val animAngle: Float = 0f,
    val riders: Int = 0,
    val lifetimeVisitors: Int = 0,
    val revenue: Int = 0,
    val ageSeconds: Float = 0f
) {
    val wTiles: Int get() = item.wTiles
    val hTiles: Int get() = item.hTiles

    val maxCol: Int get() = col + wTiles - 1
    val maxRow: Int get() = row + hTiles - 1

    fun covers(targetCol: Int, targetRow: Int): Boolean =
        targetCol >= col && targetCol <= maxCol && targetRow >= row && targetRow <= maxRow

    /** Front-most tile of the footprint — used to depth-sort against other structures. */
    val depth: Int get() = maxCol + maxRow

    fun centerCol(): Float = col + wTiles / 2f
    fun centerRow(): Float = row + hTiles / 2f

    /** The walkable ring around the footprint; visitors stand here to use the building. */
    fun perimeter(): List<TilePos> {
        val out = ArrayList<TilePos>(wTiles * 2 + hTiles * 2 + 4)
        val left = col - 1
        val right = maxCol + 1
        for (c in left..right) {
            out += TilePos(c, row - 1)
            out += TilePos(c, maxRow + 1)
        }
        for (r in row..maxRow) {
            out += TilePos(left, r)
            out += TilePos(right, r)
        }
        return out
    }
}

enum class VisitorState {
    /** Following a path towards a building. */
    WALKING,

    /** On a ride / eating / queueing. */
    USING,

    /** Heading back to the gate and out of the park. */
    LEAVING
}

/**
 * A park guest. Position is kept in fractional tile coordinates so movement stays smooth
 * while the AI itself thinks purely in path tiles.
 */
data class Visitor(
    val id: String,
    val col: Float,
    val row: Float,
    val state: VisitorState,
    val path: List<TilePos> = emptyList(),
    val pathIndex: Int = 0,
    val targetId: String? = null,
    val useTimer: Float = 0f,
    /** 0..1, drives the park rating and whether they go home angry. */
    val happiness: Float = 0.75f,
    val hunger: Float = 0f,
    val thirst: Float = 0f,
    val bladder: Float = 0f,
    val wallet: Int = 60,
    val ridesTaken: Int = 0,
    val paletteIndex: Int = 0,
    /** Tiles per second. */
    val speed: Float = 1.8f,
    /** Animation phase used for the walk bounce. */
    val bob: Float = 0f
) {
    /** The map tile the visitor is currently standing in. */
    val tileCol: Int get() = col.toInt()
    val tileRow: Int get() = row.toInt()
}

enum class GameSpeed(val label: String, val multiplier: Float) {
    PAUSED("❚❚", 0f),
    NORMAL("▶", 1f),
    FAST("▶▶", 2f),
    ULTRA("▶▶▶", 3f);
}

data class ParkStats(
    val rides: Int = 0,
    val shops: Int = 0,
    val visitors: Int = 0,
    val averageHappiness: Float = 0f,
    val dailyIncome: Int = 0,
    val dailyUpkeep: Int = 0,
    /** 0..100, the headline number in the reference's top bar. */
    val rating: Int = 0
)

data class GameState(
    val parkName: String = "Wonder Park",
    val money: Int = 12000,
    val map: ParkMap,
    val structures: List<Structure> = emptyList(),
    val visitors: List<Visitor> = emptyList(),
    val entrance: TilePos = TilePos(19, 38),
    val selectedItem: BuildItem? = null,
    val speed: GameSpeed = GameSpeed.NORMAL,
    val day: Int = 1,
    val dayProgress: Float = 0f,
    val gameTime: Float = 0f,
    val totalVisitors: Int = 0,
    val todayIncome: Int = 0,
    val todayVisitors: Int = 0,
    val stats: ParkStats = ParkStats(),
    /** Ticker text shown in the HUD ("Carousel opened!", "A guest went home unhappy"). */
    val message: String? = null,
    val messageTimer: Float = 0f
) {
    val isPaused: Boolean get() = speed == GameSpeed.PAUSED

    val totalUpkeep: Int get() = structures.sumOf { it.item.upkeep }

    fun structureAt(col: Int, row: Int): Structure? =
        structures.firstOrNull { it.covers(col, row) }

    fun structureById(id: String?): Structure? =
        if (id == null) null else structures.firstOrNull { it.id == id }
}
