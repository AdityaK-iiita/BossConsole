package ai.rever.boss.components.overlays

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [cornerPosition], the only part of the toast overlay reachable without a display.
 *
 * Placement is the part of this that has actually gone wrong before: an overlay measured against
 * the wrong origin, or in the wrong units, compiles and passes every other gate while sitting
 * visibly off-target on screen.
 */
class HeavyweightCornerTest {
    private val parent = intArrayOf(100, 50, 1000, 800)
    private val size = DpSize(432.dp, 200.dp)

    @Test
    fun `top end sits at the parent's right edge, not the screen's`() {
        // 100 + (1000 - 432) = 668, i.e. offset from the PARENT origin. Using the screen origin
        // instead is the classic version of this bug and lands the toast on the wrong monitor.
        assertEquals(668 to 50, cornerPosition(parent, size, Alignment.TopEnd))
    }

    @Test
    fun `top start sits at the parent origin`() {
        assertEquals(100 to 50, cornerPosition(parent, size, Alignment.TopStart))
    }

    @Test
    fun `bottom end offsets by both slacks`() {
        assertEquals(668 to 650, cornerPosition(parent, size, Alignment.BottomEnd))
    }

    @Test
    fun `center centres on both axes`() {
        assertEquals(384 to 350, cornerPosition(parent, size, Alignment.Center))
    }

    @Test
    fun `content larger than the parent overhangs bottom-right rather than escaping top-left`() {
        // A negative slack would put the toast above and left of the window, where it is off screen
        // and its dismiss button is unreachable. Floor at the parent origin instead.
        val huge = DpSize(2000.dp, 2000.dp)
        assertEquals(100 to 50, cornerPosition(parent, huge, Alignment.TopEnd))
        assertEquals(100 to 50, cornerPosition(parent, huge, Alignment.BottomEnd))
    }

    @Test
    fun `unmeasured parent falls back to the origin`() {
        assertEquals(0 to 0, cornerPosition(null, size, Alignment.TopEnd))
    }
}
