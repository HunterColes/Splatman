package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoverageTest {
    private fun heading(deg: Float) =
        CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.toRadians(deg.toDouble()).toFloat()), Vec3.ZERO)

    @Test
    fun `a full turn covers everything`() {
        val c = Coverage()
        for (i in 0 until 24) c.add(heading(i * 15f))
        assertEquals(1f, c.fraction)
    }

    @Test
    fun `a quarter turn covers a quarter`() {
        val c = Coverage()
        for (i in 0 until 6) c.add(heading(i * 15f + 1f)) // 1..76 degrees: three 30-degree sectors
        assertEquals(3f / 12, c.fraction)
    }

    @Test
    fun `looking straight down counts for nothing and reset forgets`() {
        val c = Coverage()
        c.add(CameraPose(Quat.fromAxisAngle(Vec3(1f, 0f, 0f), (-Math.PI / 2).toFloat()), Vec3.ZERO))
        assertEquals(0f, c.fraction)
        c.add(heading(0f))
        assertEquals(1f / 12, c.fraction)
        c.reset()
        assertEquals(0f, c.fraction)
    }
}
