package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [ScanStatusSync] over the real repository on a fake DAO and a real folder tree. */
class ScanStatusSyncTest {

    @TempDir
    lateinit var tmp: File

    private val root by lazy { File(tmp, "scans").apply { mkdirs() } }
    private val repository by lazy {
        ScanRepositoryImpl(FakeScanDao(), root, ProcessingCanceller { }, Dispatchers.Unconfined)
    }

    private fun TestScope.sync() = ScanStatusSync(repository, backgroundScope)

    private fun bundle(id: String): File =
        File(root, id).apply { mkdirs() }.also { File(it, "transforms.json").writeText("{}") }

    private fun splat(id: String) {
        val bytes = javaClass.getResourceAsStream("/splats/tiny-sh1.ply")!!.use { it.readBytes() }
        File(root, "$id/splat.ply").writeBytes(bytes)
    }

    @Test
    fun `a new scan's run adopts its folder as PROCESSING, then READY with its splat`() = runTest {
        val sync = sync()
        bundle("scan_1")

        sync.started("scan_1")
        runCurrent()
        assertEquals(ScanStatus.PROCESSING, repository.get("scan_1")!!.status)

        splat("scan_1")
        sync.finished("scan_1", failed = false)
        runCurrent()
        repository.get("scan_1")!!.let {
            assertEquals(ScanStatus.READY, it.status)
            assertEquals(4, it.splatCount)
        }
    }

    @Test
    fun `a failed run reads FAILED, a cancel with no splat reads CAPTURED`() = runTest {
        val sync = sync()
        bundle("bad")
        bundle("stopped")

        sync.started("bad")
        sync.finished("bad", failed = true)
        sync.started("stopped")
        sync.finished("stopped", failed = false)
        runCurrent()

        assertEquals(ScanStatus.FAILED, repository.get("bad")!!.status)
        assertEquals(ScanStatus.CAPTURED, repository.get("stopped")!!.status)
    }

    @Test
    fun `after a restart a PROCESSING row with no run going reads FAILED, a live one is kept`() = runTest {
        val sync = sync()
        bundle("killed")
        bundle("live")
        bundle("done")
        splat("done")
        repository.sync()
        repository.updateStatus("killed", ScanStatus.PROCESSING)
        repository.updateStatus("live", ScanStatus.PROCESSING)

        sync.recover { folder -> folder == "live" }

        assertEquals(ScanStatus.FAILED, repository.get("killed")!!.status)
        assertEquals(ScanStatus.PROCESSING, repository.get("live")!!.status)
        assertEquals(ScanStatus.READY, repository.get("done")!!.status)
    }
}
