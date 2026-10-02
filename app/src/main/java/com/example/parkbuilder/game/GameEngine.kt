package com.example.parkbuilder.game

import com.example.parkbuilder.game.model.BuildItem
import com.example.parkbuilder.game.model.GameSpeed
import com.example.parkbuilder.game.model.GameState
import com.example.parkbuilder.game.model.Need
import com.example.parkbuilder.game.model.ParkMap
import com.example.parkbuilder.game.model.ParkStats
import com.example.parkbuilder.game.model.Structure
import com.example.parkbuilder.game.model.Terrain
import com.example.parkbuilder.game.model.TilePos
import com.example.parkbuilder.game.model.ToolCategory
import com.example.parkbuilder.game.model.Visitor
import com.example.parkbuilder.game.model.VisitorState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Why a build action was allowed or refused. Drives the red/green build ghost. */
enum class Placement {
    OK,
    OUT_OF_BOUNDS,
    BLOCKED_BY_WATER,
    OCCUPIED,
    BUILT_OVER,
    NOTHING_TO_DEMOLISH,
    NOT_ENOUGH_MONEY,

    /** Queue lines have to be single file, or guests get stuck in a dead end. */
    QUEUE_JUNCTION
}

/**
 * The whole simulation: pure functions over [GameState] so the renderer can stay dumb and
 * the rules stay unit-testable.
 */
class GameEngine(private val random: Random = Random.Default) {

    companion object {
        /** Real seconds per in-game day at 1x speed. */
        const val DAY_SECONDS = 60f
        const val MAX_VISITORS = 110
        const val NEED_THRESHOLD = 0.42f
        const val DEMOLISH_REFUND = 0.5f

        private const val HUNGER_RATE = 0.010f
        private const val THIRST_RATE = 0.013f
        private const val BLADDER_RATE = 0.008f
        private const val HAPPINESS_DECAY = 0.0035f
        private const val ARRIVE_EPSILON = 0.02f

        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)
    }

    /** Seconds of simulation time banked towards the next arrival at the gate. */
    private var spawnTimer = 0f

    // ------------------------------------------------------------------
    // Scratch buffers
    //
    // Guests re-route constantly, and a fresh set of search arrays per call would be
    // ~16KB of garbage each time — enough to trigger visible GC pauses at 60fps. The
    // engine is single-threaded, so these can simply be reused between searches.
    //
    // Nothing here is cleared wholesale: only the `visited`/`goal` markers are reset.
    // `cameFrom` is written before a node is marked visited, so stale entries are never
    // read back.
    // ------------------------------------------------------------------

    private var scratchReachable = BooleanArray(0)
    private var scratchVisited = BooleanArray(0)
    private var scratchGoals = BooleanArray(0)
    private var scratchCameFrom = IntArray(0)
    private var scratchQueue = IntArray(0)

    /** Which queue tiles are taken this frame. Rebuilt in [seedQueueOccupancy]. */
    private var scratchOccupied = BooleanArray(0)

    /**
     * Queue lines, ordered head (next to the ride) to tail. Rebuilt once per frame so a
     * whole crowd shuffling forwards costs one walk per attraction instead of one each.
     */
    private val chainCache = HashMap<String, List<TilePos>>()

    private fun prepareScratch(size: Int) {
        if (scratchReachable.size >= size) return
        scratchReachable = BooleanArray(size)
        scratchVisited = BooleanArray(size)
        scratchGoals = BooleanArray(size)
        scratchCameFrom = IntArray(size)
        scratchQueue = IntArray(size)
        scratchOccupied = BooleanArray(size)
    }

    // ==================================================================
    // Frame update
    // ==================================================================

    fun update(currentState: GameState, rawDelta: Float): GameState {
        if (currentState.isPaused) return currentState

        // Cap the step so one stall never teleports every guest across the park.
        val dt = (rawDelta * currentState.speed.multiplier).coerceIn(0f, 0.1f)
        if (dt <= 0f) return currentState

        val map = currentState.map
        // One flood fill per frame: every reachability question below reads this.
        val walkable = floodFillFromGate(map, currentState.entrance)
        chainCache.clear()
        val occupied = seedQueueOccupancy(currentState)

        val ledger = Ledger()
        var earned = 0
        val survivors = ArrayList<Visitor>(currentState.visitors.size + 4)

        currentState.visitors.forEach { visitor ->
            val outcome = stepVisitor(currentState, visitor, dt, walkable, occupied, ledger)
            earned += outcome.income
            if (!outcome.remove) survivors += outcome.visitor
        }

        // ---- New arrivals at the gate -----------------------------------
        var visitors: List<Visitor> = survivors
        var carried = spawnTimer + dt
        var totalVisitors = currentState.totalVisitors
        var todayVisitors = currentState.todayVisitors
        val interval = spawnInterval(currentState.stats.rating)
        while (carried >= interval && visitors.size < MAX_VISITORS) {
            carried -= interval
            val fresh = spawnVisitor(currentState, visitors.size)
            if (fresh == null) break
            visitors = visitors + fresh
            totalVisitors++
            todayVisitors++
        }
        spawnTimer = carried

        // ---- Buildings ---------------------------------------------------
        val structures = currentState.structures.map { structure ->
            structure.copy(
                animAngle = (structure.animAngle + rotationSpeed(structure) * dt) % 360f,
                riders = (structure.riders + ledger.ridersFor(structure.id)).coerceAtLeast(0),
                lifetimeVisitors = structure.lifetimeVisitors + ledger.usersFor(structure.id),
                ageSeconds = structure.ageSeconds + dt
            )
        }

        // ---- Day cycle ---------------------------------------------------
        var money = currentState.money + earned
        var day = currentState.day
        var dayProgress = currentState.dayProgress + dt / DAY_SECONDS
        var todayIncome = currentState.todayIncome + earned
        var message = currentState.message
        var messageTimer = (currentState.messageTimer - dt).coerceAtLeast(0f)

        if (dayProgress >= 1f) {
            dayProgress -= 1f
            day++
            val upkeep = structures.sumOf { it.item.upkeep }
            money -= upkeep
            message = if (money < 0) {
                "Day $day — the park is £${-money} in the red!"
            } else {
                "Day $day — takings £$todayIncome, upkeep £$upkeep"
            }
            messageTimer = 3.5f
            todayIncome = 0
            todayVisitors = 0
        }

        val stats = computeStats(
            structures = structures,
            visitors = visitors,
            todayIncome = todayIncome,
            previous = currentState.stats
        )

        return currentState.copy(
            money = money,
            structures = structures,
            visitors = visitors,
            day = day,
            dayProgress = dayProgress,
            gameTime = currentState.gameTime + dt,
            totalVisitors = totalVisitors,
            todayIncome = todayIncome,
            todayVisitors = todayVisitors,
            stats = stats,
            message = message,
            messageTimer = messageTimer
        )
    }

    private fun spawnInterval(rating: Int): Float = (4.2f - rating / 40f).coerceAtLeast(0.9f)

    // ==================================================================
    // Visitors
    // ==================================================================

    private class Outcome(val visitor: Visitor, val remove: Boolean, val income: Int)

    /**
     * Seat and visit counters accumulated while walking the guest list. Structures are
     * immutable, so deltas are batched up here and folded in once per frame.
     */
    private class Ledger {
        private val riders = HashMap<String, Int>()
        private val users = HashMap<String, Int>()

        fun seat(id: String) {
            riders[id] = (riders[id] ?: 0) + 1
            users[id] = (users[id] ?: 0) + 1
        }

        fun release(id: String) {
            riders[id] = (riders[id] ?: 0) - 1
        }

        fun ridersFor(id: String): Int = riders[id] ?: 0
        fun usersFor(id: String): Int = users[id] ?: 0
    }

    private fun stepVisitor(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        val aged = growNeeds(visitor, dt)
        return when (aged.state) {
            VisitorState.USING -> finishUse(state, aged, dt, walkable, occupied, ledger)
            VisitorState.QUEUEING -> stepQueue(state, aged, dt, walkable, occupied, ledger)
            else -> walkTowards(state, aged, dt, walkable, occupied, ledger)
        }
    }

    private fun finishUse(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        val remaining = visitor.useTimer - dt
        if (remaining > 0f) {
            return Outcome(visitor.copy(useTimer = remaining, bob = visitor.bob + dt * 6f), false, 0)
        }
        // Done here — release the seat and look for the next thing to do.
        visitor.targetId?.let { ledger.release(it) }
        val rested = visitor.copy(
            state = VisitorState.WALKING,
            targetId = null,
            path = emptyList(),
            pathIndex = 0,
            useTimer = 0f
        )
        return continueVisit(state, rested, dt, walkable, occupied, ledger)
    }

    /** Follows the remaining path; on arrival either uses a building or goes home. */
    private fun walkTowards(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        if (visitor.pathIndex >= visitor.path.size) {
            return when (visitor.state) {
                VisitorState.LEAVING -> Outcome(visitor, true, 0)
                // Reached the back of a queue: stand here and let the line advance.
                VisitorState.QUEUEING -> Outcome(visitor, false, 0)
                else -> continueVisit(state, visitor, dt, walkable, occupied, ledger)
            }
        }

        val node = visitor.path[visitor.pathIndex]
        val targetCol = node.col + 0.5f
        val targetRow = node.row + 0.5f
        val dx = targetCol - visitor.col
        val dy = targetRow - visitor.row
        val distance = sqrt(dx * dx + dy * dy)
        val step = visitor.speed * dt

        val moved = if (distance <= step + ARRIVE_EPSILON) {
            visitor.copy(
                col = targetCol,
                row = targetRow,
                pathIndex = visitor.pathIndex + 1,
                bob = visitor.bob + dt * 9f
            )
        } else {
            val inv = 1f / distance
            visitor.copy(
                col = visitor.col + dx * inv * step,
                row = visitor.row + dy * inv * step,
                bob = visitor.bob + dt * 9f
            )
        }

        if (moved.pathIndex < moved.path.size) return Outcome(moved, false, 0)

        // Path exhausted: act on the target if we still want it.
        val target = state.structureById(moved.targetId)
        return when {
            moved.state == VisitorState.LEAVING -> Outcome(moved, true, 0)
            moved.state == VisitorState.QUEUEING -> Outcome(moved, false, 0)
            target == null -> continueVisit(state, moved, dt, walkable, occupied, ledger)
            else -> beginUse(state, moved, target, dt, walkable, occupied, ledger)
        }
    }

    private fun beginUse(
        state: GameState,
        visitor: Visitor,
        target: Structure,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        // Shops and facilities have no seat limit; rides do.
        val capacity = seatLimit(target)
        if (target.riders + ledger.ridersFor(target.id) >= capacity ||
            visitor.wallet < target.item.price
        ) {
            return continueVisit(state, visitor.copy(targetId = null), dt, walkable, occupied, ledger)
        }

        ledger.seat(target.id)
        return Outcome(applyUse(visitor, target), false, target.item.price)
    }

    /** Applies the effect of one ride / meal / restroom visit to a visitor. */
    private fun applyUse(visitor: Visitor, target: Structure): Visitor {
        val item = target.item
        val next = visitor.copy(
            state = VisitorState.USING,
            targetId = target.id,
            useTimer = item.useSeconds,
            wallet = visitor.wallet - item.price,
            ridesTaken = visitor.ridesTaken + 1,
            happiness = (visitor.happiness + item.excitement * 0.45f).coerceAtMost(1f)
        )
        return when (item.need) {
            Need.HUNGER -> next.copy(hunger = 0f, happiness = (next.happiness + 0.15f).coerceAtMost(1f))
            Need.THIRST -> next.copy(thirst = 0f, happiness = (next.happiness + 0.15f).coerceAtMost(1f))
            Need.BLADDER -> next.copy(bladder = 0f, happiness = (next.happiness + 0.10f).coerceAtMost(1f))
            null -> next
        }
    }

    /** Picks the next building; sends the visitor home when there is nothing left to do. */
    private fun continueVisit(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        if (shouldLeave(state, visitor)) return sendHome(state, visitor)

        val target = chooseTarget(state, visitor, walkable) ?: return sendHome(state, visitor)
        val from = TilePos(visitor.tileCol, visitor.tileRow)

        // A queue line beats walking straight up to the ride: guests join the back of it
        // and shuffle forwards instead of crowding the entrance.
        val chain = cachedQueueChain(state.map, target)
        if (chain.isNotEmpty()) {
            val toTail = findPath(state.map, from, listOf(chain.last()))
            if (toTail != null) {
                val queued = visitor.copy(
                    state = VisitorState.QUEUEING,
                    targetId = target.id,
                    path = toTail,
                    pathIndex = 0
                )
                return walkTowards(state, queued, dt, walkable, occupied, ledger)
            }
        }

        val path = findPath(state.map, from, target.perimeter())
            ?: return sendHome(state, visitor)

        val routed = visitor.copy(
            state = VisitorState.WALKING,
            targetId = target.id,
            path = path,
            pathIndex = 0
        )
        return walkTowards(state, routed, dt, walkable, occupied, ledger)
    }

    // ------------------------------------------------------------------
    // Queue lines
    // ------------------------------------------------------------------

    /** Seats a ride offers, or effectively unlimited for shops and facilities. */
    private fun seatLimit(target: Structure): Int =
        if (target.item.capacity > 0) target.item.capacity else Int.MAX_VALUE

    /**
     * The queue line running into [structure], ordered head (the tile beside the ride that
     * guests board from) to tail (the back of the line).
     *
     * Only queue tiles that touch the footprint can be an entrance, and the line is walked
     * one tile at a time, so a single-file queue comes out in order. Branches are ignored:
     * the longest chain wins, which is the one the player actually drew. Empty when the
     * attraction has no queue path attached.
     */
    fun queueChain(map: ParkMap, structure: Structure): List<TilePos> =
        computeQueueChain(map, structure)

    /** Per-frame wrapper: the map cannot change mid-frame, so one walk serves everybody. */
    private fun cachedQueueChain(map: ParkMap, structure: Structure): List<TilePos> =
        chainCache.getOrPut(structure.id) { computeQueueChain(map, structure) }

    private fun computeQueueChain(map: ParkMap, structure: Structure): List<TilePos> {
        val starts = structure.perimeter().filter {
            map.terrainAt(it.col, it.row) == Terrain.QUEUE
        }
        var best = emptyList<TilePos>()
        starts.forEach { start ->
            val chain = ArrayList<TilePos>(16)
            chain += start
            var current = start
            while (chain.size <= 64) {
                var next: TilePos? = null
                for (d in 0 until 4) {
                    val nc = current.col + DX[d]
                    val nr = current.row + DY[d]
                    if (!map.inBounds(nc, nr)) continue
                    if (map.terrainAt(nc, nr) != Terrain.QUEUE) continue
                    if (chain.any { it.col == nc && it.row == nr }) continue
                    next = TilePos(nc, nr)
                    break
                }
                val step = next ?: break
                chain += step
                current = step
            }
            if (chain.size > best.size) best = chain
        }

        return best
    }

    /** Marks every tile currently held by a queuing guest, so nobody doubles up. */
    private fun seedQueueOccupancy(state: GameState): BooleanArray {
        prepareScratch(state.map.tileCount)
        val occupied = scratchOccupied
        occupied.fill(false)
        state.visitors.forEach { visitor ->
            if (visitor.state != VisitorState.QUEUEING) return@forEach
            if (!state.map.inBounds(visitor.tileCol, visitor.tileRow)) return@forEach
            occupied[visitor.tileRow * state.map.cols + visitor.tileCol] = true
        }
        return occupied
    }

    /**
     * One frame in the life of a queuing guest: advance a tile towards the front when the
     * one in front is free, or board the ride once they are at the head of the line.
     */
    private fun stepQueue(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome {
        // Still walking to the back of the line.
        if (visitor.pathIndex < visitor.path.size) {
            return walkTowards(state, visitor, dt, walkable, occupied, ledger)
        }

        val target = state.structureById(visitor.targetId)
        if (target == null) return leaveQueue(state, visitor, dt, walkable, occupied, ledger)

        val chain = cachedQueueChain(state.map, target)
        val slot = chain.indexOfFirst { it.col == visitor.tileCol && it.row == visitor.tileRow }
        if (slot < 0) return leaveQueue(state, visitor, dt, walkable, occupied, ledger)

        // Waiting this long with no money left is a waste of everybody's day.
        if (visitor.wallet < target.item.price) {
            occupied[queueIndex(state, visitor.tileCol, visitor.tileRow)] = false
            return continueVisit(
                state,
                visitor.copy(state = VisitorState.WALKING, targetId = null),
                dt, walkable, occupied, ledger
            )
        }

        val here = queueIndex(state, visitor.tileCol, visitor.tileRow)

        if (slot == 0) {
            if (target.riders + ledger.ridersFor(target.id) >= seatLimit(target)) {
                // Ride is full: shuffle on the spot.
                return Outcome(visitor.copy(bob = visitor.bob + dt * 3f), false, 0)
            }
            ledger.seat(target.id)
            occupied[here] = false
            return Outcome(applyUse(visitor, target), false, target.item.price)
        }

        val next = chain[slot - 1]
        val nextIndex = queueIndex(state, next.col, next.row)
        val blocked = nextIndex >= 0 && nextIndex < occupied.size && occupied[nextIndex]
        if (blocked) return Outcome(visitor.copy(bob = visitor.bob + dt * 3f), false, 0)

        occupied[here] = false
        if (nextIndex in occupied.indices) occupied[nextIndex] = true
        return Outcome(
            visitor.copy(
                col = next.col + 0.5f,
                row = next.row + 0.5f,
                bob = visitor.bob + dt * 6f
            ),
            false,
            0
        )
    }

    private fun queueIndex(state: GameState, col: Int, row: Int): Int =
        if (state.map.inBounds(col, row)) row * state.map.cols + col else -1

    /** Abandons the line (demolished queue, sold attraction) and picks something else. */
    private fun leaveQueue(
        state: GameState,
        visitor: Visitor,
        dt: Float,
        walkable: BooleanArray,
        occupied: BooleanArray,
        ledger: Ledger
    ): Outcome = continueVisit(
        state,
        visitor.copy(state = VisitorState.WALKING, targetId = null, path = emptyList(), pathIndex = 0),
        dt, walkable, occupied, ledger
    )

    private fun shouldLeave(state: GameState, visitor: Visitor): Boolean {
        if (visitor.state == VisitorState.LEAVING) return false
        if (visitor.happiness <= 0.12f) return true
        if (visitor.wallet <= 1) return true
        if (visitor.ridesTaken >= 9) return true
        return state.structures.none { it.item.isAttraction }
    }

    private fun sendHome(state: GameState, visitor: Visitor): Outcome {
        val path = findPath(state.map, TilePos(visitor.tileCol, visitor.tileRow), listOf(state.entrance))
        if (path == null || path.isEmpty()) {
            return Outcome(visitor.copy(state = VisitorState.LEAVING), true, 0)
        }
        return Outcome(
            visitor.copy(
                state = VisitorState.LEAVING,
                targetId = null,
                path = path,
                pathIndex = 0
            ),
            false,
            0
        )
    }

    private fun chooseTarget(
        state: GameState,
        visitor: Visitor,
        walkable: BooleanArray
    ): Structure? {
        val scored = ArrayList<Pair<Structure, Float>>()
        state.structures.forEach { structure ->
            val item = structure.item
            if (!item.isAttraction) return@forEach
            if (visitor.wallet < item.price) return@forEach
            if (!perimeterReachable(structure, state.map, walkable)) return@forEach
            val weight = interest(visitor, structure)
            if (weight > 0f) scored += structure to weight
        }
        if (scored.isEmpty()) return null

        val total = scored.fold(0f) { acc, entry -> acc + entry.second }
        var roll = random.nextFloat() * total
        for ((structure, weight) in scored) {
            roll -= weight
            if (roll <= 0f) return structure
        }
        return scored.last().first
    }

    /** How much a visitor fancies this particular building right now. */
    private fun interest(visitor: Visitor, structure: Structure): Float = when {
        structure.item.need == Need.HUNGER ->
            if (visitor.hunger > NEED_THRESHOLD) visitor.hunger * 6f else 0f
        structure.item.need == Need.THIRST ->
            if (visitor.thirst > NEED_THRESHOLD) visitor.thirst * 6f else 0f
        structure.item.need == Need.BLADDER ->
            if (visitor.bladder > NEED_THRESHOLD) visitor.bladder * 8f else 0f
        structure.item.category == ToolCategory.RIDE ->
            (1.3f - visitor.happiness) * (0.4f + structure.item.excitement) * 3f
        structure.item.category == ToolCategory.SHOP -> 0.5f
        else -> 0f
    }

    private fun growNeeds(visitor: Visitor, dt: Float): Visitor {
        var hunger = visitor.hunger + HUNGER_RATE * dt
        var thirst = visitor.thirst + THIRST_RATE * dt
        var bladder = visitor.bladder + BLADDER_RATE * dt
        var happiness = (visitor.happiness - HAPPINESS_DECAY * dt).coerceAtLeast(0f)

        // Letting a need boil over is what sends guests home in a huff.
        if (hunger >= 1f) {
            happiness = (happiness - dt * 0.2f).coerceAtLeast(0f)
            hunger = 0.7f
        }
        if (thirst >= 1f) {
            happiness = (happiness - dt * 0.2f).coerceAtLeast(0f)
            thirst = 0.7f
        }
        if (bladder >= 1f) {
            happiness = (happiness - dt * 0.2f).coerceAtLeast(0f)
            bladder = 0.7f
        }

        return visitor.copy(hunger = hunger, thirst = thirst, bladder = bladder, happiness = happiness)
    }

    fun spawnVisitor(state: GameState, index: Int): Visitor? {
        val start = nearestWalkable(state.map, state.entrance.col, state.entrance.row, 4) ?: return null
        return Visitor(
            id = UUID.randomUUID().toString(),
            col = start.col + 0.5f,
            row = start.row + 0.5f,
            state = VisitorState.WALKING,
            happiness = 0.62f + random.nextFloat() * 0.25f,
            wallet = 40 + random.nextInt(85),
            paletteIndex = abs(random.nextInt()) % 8,
            speed = 1.35f + random.nextFloat() * 0.8f
        )
    }

    // ==================================================================
    // Building
    // ==================================================================

    /** Applies the selected tool at one tile, or explains why it cannot. */
    fun applyTool(state: GameState, item: BuildItem?, col: Int, row: Int): GameState {
        if (item == null) return state
        return when (placementFor(state, item, col, row)) {
            Placement.OK -> if (item.isTerrainBrush) {
                paintTerrain(state, item, col, row)
            } else {
                placeStructure(state, item, col, row)
            }

            Placement.QUEUE_JUNCTION -> notify(state, "Queues must stay single file")
            Placement.NOT_ENOUGH_MONEY -> notify(state, "Not enough cash for ${item.displayName}")
            Placement.BLOCKED_BY_WATER -> notify(state, "${item.displayName} cannot go on water")
            Placement.OCCUPIED -> notify(state, "Something is already built there")
            Placement.BUILT_OVER -> notify(state, "Demolish the building first")
            Placement.NOTHING_TO_DEMOLISH -> state
            Placement.OUT_OF_BOUNDS -> notify(state, "That is outside the park")
        }
    }

    private fun paintTerrain(state: GameState, item: BuildItem, col: Int, row: Int): GameState {
        if (item == BuildItem.BULLDOZE) {
            val structure = state.structureAt(col, row)
            if (structure != null) {
                val refund = (structure.item.cost * DEMOLISH_REFUND).toInt()
                return notify(
                    state.copy(
                        money = state.money + refund,
                        structures = state.structures.filterNot { it.id == structure.id }
                    ),
                    "Demolished ${structure.item.displayName} (+£$refund)"
                )
            }
            val repainted = state.map.withTerrain(col, row, Terrain.GRASS)
            if (repainted === state.map) return state
            return state.copy(money = state.money - item.cost, map = repainted)
        }

        val terrain = item.terrain ?: return state
        val repainted = state.map.withTerrain(col, row, terrain)
        if (repainted === state.map) return state
        return state.copy(money = state.money - item.cost, map = repainted)
    }

    private fun placeStructure(state: GameState, item: BuildItem, col: Int, row: Int): GameState {
        val structure = Structure(
            id = UUID.randomUUID().toString(),
            item = item,
            col = col,
            row = row,
            animAngle = random.nextFloat() * 360f
        )
        return notify(
            state.copy(
                money = state.money - item.cost,
                structures = state.structures.map { it.copy(isSelected = false) } + structure,
                selectedItem = null
            ),
            "${item.displayName} opened!"
        )
    }

    fun selectItem(state: GameState, item: BuildItem?): GameState =
        state.copy(selectedItem = if (state.selectedItem == item) null else item)

    fun selectStructure(state: GameState, col: Int, row: Int): GameState {
        val hit = state.structureAt(col, row)
        return state.copy(
            structures = state.structures.map { it.copy(isSelected = it.id == hit?.id) },
            selectedItem = null
        )
    }

    fun setSpeed(state: GameState, speed: GameSpeed): GameState = state.copy(speed = speed)

    fun notify(state: GameState, text: String): GameState =
        state.copy(message = text, messageTimer = 2.5f)

    // ==================================================================
    // Pathfinding
    // ==================================================================

    /**
     * Every path tile connected to the park gate — visitors may only stand on these.
     *
     * Returns a shared scratch buffer that stays valid until the next flood fill, which is
     * fine because [update] does exactly one per frame and threads the result down.
     */
    private fun floodFillFromGate(map: ParkMap, entrance: TilePos): BooleanArray {
        prepareScratch(map.tileCount)
        val reachable = scratchReachable
        reachable.fill(false)
        val start = nearestWalkable(map, entrance.col, entrance.row, 4) ?: return reachable
        val queue = scratchQueue
        var head = 0
        var tail = 0
        val startIndex = start.row * map.cols + start.col
        reachable[startIndex] = true
        queue[tail++] = startIndex
        while (head < tail) {
            val index = queue[head++]
            val col = index % map.cols
            val row = index / map.cols
            for (d in 0 until 4) {
                val nc = col + DX[d]
                val nr = row + DY[d]
                if (!map.inBounds(nc, nr) || !map.isWalkable(nc, nr)) continue
                val ni = nr * map.cols + nc
                if (reachable[ni]) continue
                reachable[ni] = true
                queue[tail++] = ni
            }
        }
        return reachable
    }

    fun isReachableFromGate(state: GameState, tile: TilePos): Boolean {
        if (!state.map.isWalkable(tile.col, tile.row)) return false
        return floodFillFromGate(state.map, state.entrance)[tile.row * state.map.cols + tile.col]
    }

    private fun perimeterReachable(
        structure: Structure,
        map: ParkMap,
        walkable: BooleanArray
    ): Boolean = structure.perimeter().any { tile ->
        map.inBounds(tile.col, tile.row) &&
            map.isWalkable(tile.col, tile.row) &&
            walkable[tile.row * map.cols + tile.col]
    }

    /**
     * Breadth-first search over path tiles. Flat int arrays and a linear ring buffer keep
     * it cheap enough to re-route a whole coach-load of guests in a single frame.
     */
    fun findPath(map: ParkMap, from: TilePos, goals: List<TilePos>): List<TilePos>? {
        val start = if (map.isWalkable(from.col, from.row)) {
            from
        } else {
            nearestWalkable(map, from.col, from.row, 4)
        } ?: return null

        prepareScratch(map.tileCount)
        val goalFlags = scratchGoals
        goalFlags.fill(false)
        var goalCount = 0
        goals.forEach { tile ->
            if (map.inBounds(tile.col, tile.row) && map.isWalkable(tile.col, tile.row)) {
                val index = tile.row * map.cols + tile.col
                if (!goalFlags[index]) {
                    goalFlags[index] = true
                    goalCount++
                }
            }
        }
        if (goalCount == 0) return null

        val total = map.tileCount
        val cameFrom = scratchCameFrom
        val visited = scratchVisited
        visited.fill(false)
        val queue = scratchQueue

        val startIndex = start.row * map.cols + start.col
        var head = 0
        var tail = 0
        queue[tail++] = startIndex
        visited[startIndex] = true

        var found = -1
        while (head < tail) {
            val index = queue[head++]
            if (goalFlags[index]) {
                found = index
                break
            }
            val col = index % map.cols
            val row = index / map.cols
            for (d in 0 until 4) {
                val nc = col + DX[d]
                val nr = row + DY[d]
                if (!map.inBounds(nc, nr) || !map.isWalkable(nc, nr)) continue
                val ni = nr * map.cols + nc
                if (visited[ni]) continue
                visited[ni] = true
                cameFrom[ni] = index
                queue[tail++] = ni
            }
        }

        if (found < 0) return null

        val path = ArrayList<TilePos>(16)
        var cursor = found
        while (cursor != startIndex && cursor >= 0) {
            path += TilePos(cursor % map.cols, cursor / map.cols)
            cursor = cameFrom[cursor]
        }
        path.reverse()
        return path
    }

    fun nearestWalkable(map: ParkMap, col: Int, row: Int, maxRadius: Int): TilePos? {
        if (map.isWalkable(col, row)) return TilePos(col, row)
        for (radius in 1..maxRadius) {
            for (dc in -radius..radius) {
                for (dr in -radius..radius) {
                    if (abs(dc) != radius && abs(dr) != radius) continue
                    val c = col + dc
                    val r = row + dr
                    if (map.inBounds(c, r) && map.isWalkable(c, r)) return TilePos(c, r)
                }
            }
        }
        return null
    }

    // ==================================================================
    // Stats
    // ==================================================================

    private fun computeStats(
        structures: List<Structure>,
        visitors: List<Visitor>,
        todayIncome: Int,
        previous: ParkStats
    ): ParkStats {
        val rides = structures.count { it.item.category == ToolCategory.RIDE }
        val shops = structures.count {
            it.item.category == ToolCategory.SHOP || it.item.category == ToolCategory.FACILITY
        }
        val happiness = if (visitors.isEmpty()) {
            previous.averageHappiness
        } else {
            visitors.sumOf { it.happiness.toDouble() }.toFloat() / visitors.size
        }
        return ParkStats(
            rides = rides,
            shops = shops,
            visitors = visitors.size,
            averageHappiness = happiness,
            dailyIncome = todayIncome,
            dailyUpkeep = structures.sumOf { it.item.upkeep },
            rating = computeRating(rides, shops, happiness, visitors.size)
        )
    }

    private fun computeRating(rides: Int, shops: Int, happiness: Float, visitors: Int): Int {
        val value = rides * 4f +
            shops * 1.5f +
            happiness * 25f +
            min(visitors, 60) * 0.12f
        return value.coerceIn(0f, 100f).toInt()
    }

    private fun rotationSpeed(structure: Structure): Float = when (structure.item) {
        BuildItem.ROLLER_COASTER -> 130f
        BuildItem.DODGEMS -> 90f
        BuildItem.CAROUSEL -> 65f
        BuildItem.LOG_FLUME -> 55f
        BuildItem.FERRIS_WHEEL -> 22f
        else -> 0f
    }
}

private fun queueNeighbours(map: ParkMap, col: Int, row: Int): Int {
    var count = 0
    if (map.terrainAt(col + 1, row) == Terrain.QUEUE) count++
    if (map.terrainAt(col - 1, row) == Terrain.QUEUE) count++
    if (map.terrainAt(col, row + 1) == Terrain.QUEUE) count++
    if (map.terrainAt(col, row - 1) == Terrain.QUEUE) count++
    return count
}

/**
 * True when laying a queue tile here would branch the line.
 *
 * A queue is a chain, so it has to stay single file: three queue neighbours on one tile
 * would make a T-junction, and guests would have no way to tell which way the front of
 * the line is. Extending an existing line is fine; bridging a gap is fine; joining two
 * lines is not.
 */
private fun createsQueueJunction(map: ParkMap, col: Int, row: Int): Boolean {
    if (queueNeighbours(map, col, row) > 2) return true
    // Nor may this tile push a neighbour past two, which is what joining two lines does.
    if (map.terrainAt(col + 1, row) == Terrain.QUEUE && queueNeighbours(map, col + 1, row) >= 2) return true
    if (map.terrainAt(col - 1, row) == Terrain.QUEUE && queueNeighbours(map, col - 1, row) >= 2) return true
    if (map.terrainAt(col, row + 1) == Terrain.QUEUE && queueNeighbours(map, col, row + 1) >= 2) return true
    if (map.terrainAt(col, row - 1) == Terrain.QUEUE && queueNeighbours(map, col, row - 1) >= 2) return true
    return false
}

/**
 * Pure placement rule check, exposed at file level so the renderer can tint the build
 * ghost without holding a reference to the engine.
 */
fun placementFor(state: GameState, item: BuildItem, col: Int, row: Int): Placement {
    if (item.isTerrainBrush) {
        if (!state.map.inBounds(col, row)) return Placement.OUT_OF_BOUNDS
        val existing = state.structureAt(col, row)
        if (item == BuildItem.BULLDOZE) {
            if (existing != null) return Placement.OK
            if (state.map.terrainAt(col, row) == Terrain.GRASS) return Placement.NOTHING_TO_DEMOLISH
            return if (state.money >= item.cost) Placement.OK else Placement.NOT_ENOUGH_MONEY
        }
        if (existing != null) return Placement.BUILT_OVER
        val terrain = item.terrain ?: return Placement.NOTHING_TO_DEMOLISH
        if (state.map.terrainAt(col, row) == terrain) return Placement.NOTHING_TO_DEMOLISH
        if (terrain == Terrain.QUEUE && createsQueueJunction(state.map, col, row)) {
            return Placement.QUEUE_JUNCTION
        }
        return if (state.money >= item.cost) Placement.OK else Placement.NOT_ENOUGH_MONEY
    }

    for (c in col until col + item.wTiles) {
        for (r in row until row + item.hTiles) {
            if (!state.map.inBounds(c, r)) return Placement.OUT_OF_BOUNDS
            if (state.map.terrainAt(c, r) == Terrain.WATER) return Placement.BLOCKED_BY_WATER
            if (state.structureAt(c, r) != null) return Placement.OCCUPIED
        }
    }
    return if (state.money >= item.cost) Placement.OK else Placement.NOT_ENOUGH_MONEY
}
