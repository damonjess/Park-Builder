package com.example.parkbuilder.ui

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bumper cars are only bumper cars if they actually collide.
 *
 * The renderer plays back [dodgemTrack] — a loop simulated once, up front — so these tests
 * check the table itself: that nobody drives through the wall, that the cars really do meet
 * each other, and that the impacts are marked on the cars that felt them.
 */
class DodgemsSimulationTest {

    private fun x(frame: Int, car: Int): Float = dodgemTrack[(frame * dodgemCars + car) * 4]
    private fun y(frame: Int, car: Int): Float = dodgemTrack[(frame * dodgemCars + car) * 4 + 1]
    private fun heading(frame: Int, car: Int): Float = dodgemTrack[(frame * dodgemCars + car) * 4 + 2]
    private fun flash(frame: Int, car: Int): Float = dodgemTrack[(frame * dodgemCars + car) * 4 + 3]

    @Test
    fun testNobodyLeavesTheRink() {
        // The floor diamond is |x| + 2|y| <= 1; a car may touch the wall but not pass it.
        var worst = 0f
        for (frame in 0 until DODGEM_FRAMES) {
            for (car in 0 until dodgemCars) {
                val reach = abs(x(frame, car)) + 2f * abs(y(frame, car))
                if (reach > worst) worst = reach
            }
        }
        assertTrue("a car drove through the wall (reach $worst)", worst <= 1.001f)
    }

    @Test
    fun testTheCarsBumpIntoEachOther() {
        var contacts = 0
        var impacts = 0
        for (frame in 0 until DODGEM_FRAMES) {
            for (car in 0 until dodgemCars) {
                if (flash(frame, car) > 0.5f) impacts++
                for (other in car + 1 until dodgemCars) {
                    val dx = x(frame, other) - x(frame, car)
                    val dy = y(frame, other) - y(frame, car)
                    if (dx * dx + dy * dy < 0.26f * 0.26f) contacts++
                }
            }
        }
        println("dodgems: $contacts car-on-car contacts, $impacts impact frames")
        assertTrue("the cars never touch each other ($contacts contacts)", contacts > 20)
        assertTrue("no impacts were recorded ($impacts", impacts > 20)
    }

    @Test
    fun testTheCarsDoNotDriveThroughEachOther() {
        var closest = Float.MAX_VALUE
        for (frame in 0 until DODGEM_FRAMES) {
            for (car in 0 until dodgemCars) {
                for (other in car + 1 until dodgemCars) {
                    val d = hypot(
                        (x(frame, other) - x(frame, car)).toDouble(),
                        (y(frame, other) - y(frame, car)).toDouble()
                    ).toFloat()
                    if (d < closest) closest = d
                }
            }
        }
        // A contact is 0.26 apart; resolving several at once leaves a little slack.
        assertTrue("two cars ended up on top of each other ($closest apart)", closest > 0.18f)
    }

    @Test
    fun testTheCarsAreNotAllOnOneOrbit() {
        // On the old "clock" every car sat on the same ring at a fixed angle offset. The
        // giveaway is that their distances from the centre were all identical.
        val radii = (0 until dodgemCars).map { hypot(x(0, it).toDouble(), y(0, it).toDouble()) }
        val spread = radii.max() - radii.min()
        assertTrue("the cars are pinned to one orbit (spread $spread)", spread > 0.05)
    }

    @Test
    fun testTheCarsTurnToFaceTheWayTheyAreGoing() {
        // A car's heading must match its direction of travel, or they slide sideways. Each
        // sample stores the heading *after* that frame's impacts, so it describes the move
        // from the next sample onward. Measured over several frames, and skipping stretches
        // where a car is bouncing, because a rebound is supposed to change the direction.
        var worst = 0f
        val window = 5
        for (frame in 0 until DODGEM_FRAMES - window - 1) {
            for (car in 0 until dodgemCars) {
                val bouncing = (1..window).any { flash(frame + it, car) > 0.05f }
                if (bouncing) continue
                val dx = x(frame + window, car) - x(frame + 1, car)
                val dy = y(frame + window, car) - y(frame + 1, car)
                if (hypot(dx.toDouble(), dy.toDouble()) < 0.02) continue
                val travel = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                var diff = abs(travel - heading(frame, car)) % 360f
                if (diff > 180f) diff = 360f - diff
                if (diff > worst) worst = diff
            }
        }
        assertTrue("a car was driving sideways (worst $worst degrees off)", worst < 20f)
    }
}
