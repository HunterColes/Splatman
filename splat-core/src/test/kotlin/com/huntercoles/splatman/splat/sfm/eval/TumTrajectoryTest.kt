package com.huntercoles.splatman.splat.sfm.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TumTrajectoryTest {
    @Test
    fun `reads lines, skips comments and blanks`() {
        val text = """
            # timestamp tx ty tz qx qy qz qw
            1305031102.175304 1.3405 0.6266 1.6575 0.6574 0.6126 -0.2949 -0.3248

            1305031102.211214 1.3303 0.6256 1.6464 0.6579 0.6161 -0.2932 -0.3189 # trailing
        """.trimIndent()
        val poses = TumTrajectory.read(text)
        assertEquals(2, poses.size)
        assertEquals(TumPose(1305031102.175304, 1.3405, 0.6266, 1.6575, 0.6574, 0.6126, -0.2949, -0.3248), poses[0])
        assertEquals(-0.3189, poses[1].qw)
    }

    @Test
    fun `write then read round-trips exactly`() {
        val poses = listOf(
            TumPose(0.1, 1.0 / 3, -2.5e-7, 9.0, 0.0, 0.0, 0.7071067811865476, 0.7071067811865476),
            TumPose(0.2, 0.0, 0.0, 0.0),
        )
        assertEquals(poses, TumTrajectory.read(TumTrajectory.write(poses)))
    }

    @Test
    fun `wrong field count or junk throws with the line number`() {
        val e = assertThrows<IllegalArgumentException> { TumTrajectory.read("# h\n1 2 3") }
        assertEquals(true, e.message!!.startsWith("line 2"))
        assertThrows<IllegalArgumentException> { TumTrajectory.read("1 2 3 4 5 6 7 x") }
    }
}
