package com.example.parkbuilder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.parkbuilder.game.model.Iso
import com.example.parkbuilder.game.model.ParkMap
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening shot has to fill the frame.
 *
 * A park floating as a small diamond in a dark void is the difference between "looks like
 * the original" and "looks like a prototype", and it is easy to regress by nudging the
 * camera. These tests pin the property down: every corner of the screen lands outside the
 * buildable plot, and the middle of the screen is park.
 */
class ParkFramingTest {

    private val map = ParkMap(40, 40)

    private fun viewports() = listOf(
        "landscape" to Size(2808f, 1256f),
        "portrait" to Size(1256f, 2808f),
        "square" to Size(1200f, 1200f)
    )

    @Test
    fun testFramingCameraCoversTheWholeViewport() {
        viewports().forEach { (name, viewport) ->
            val camera = framingCamera(viewport, map)
            val corners = listOf(
                Offset(0f, 0f),
                Offset(viewport.width, 0f),
                Offset(0f, viewport.height),
                Offset(viewport.width, viewport.height)
            )
            corners.forEach { corner ->
                val world = camera.screenToWorld(corner)
                val col = Iso.toCol(world.x, world.y)
                val row = Iso.toRow(world.x, world.y)
                assertTrue(
                    "$name: corner $corner landed on the plot at ($col, $row) — the park " +
                        "would not reach the edges of the frame",
                    col < 0f || col > map.cols || row < 0f || row > map.rows
                )
            }
        }
    }

    @Test
    fun testFramingCameraCentresOnThePark() {
        viewports().forEach { (name, viewport) ->
            val camera = framingCamera(viewport, map)
            val centre = camera.screenToWorld(Offset(viewport.width / 2f, viewport.height / 2f))
            val tile = Iso.toTile(centre.x, centre.y)
            assertTrue("$name: the middle of the screen is not park", map.inBounds(tile.col, tile.row))
        }
    }

    @Test
    fun testFramingZoomIsInsideTheCameraLimits() {
        // Someone's ultrawide must not ask for a zoom the camera cannot do.
        val wide = framingCamera(Size(20000f, 400f), map)
        assertTrue("zoom above the maximum: ${wide.zoom}", wide.zoom <= 4.5f)

        val tiny = framingCamera(Size(120f, 90f), map)
        assertTrue("zoom below the minimum: ${tiny.zoom}", tiny.zoom >= 0.35f)
    }

    /**
     * Tiles of equal depth sit on one horizontal line, which is what lets the road outside
     * the gate run straight across the bottom of the screen instead of streaking diagonally.
     */
    @Test
    fun testEqualDepthIsHorizontalOnScreen() {
        val depth = 86f
        val left = Iso.toScreen((depth - 40f) / 2f, (depth + 40f) / 2f)
        val right = Iso.toScreen((depth + 40f) / 2f, (depth - 40f) / 2f)
        assertTrue("depth line should be level", kotlin.math.abs(left.y - right.y) < 0.01f)
        assertTrue("depth line should run across the screen", right.x - left.x > 2000f)
    }
}
