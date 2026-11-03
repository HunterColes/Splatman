package com.huntercoles.splatman.capture.presentation.processing

import androidx.lifecycle.SavedStateHandle
import com.huntercoles.splatman.capture.processing.KeyframeDecoder
import com.huntercoles.splatman.capture.processing.NotificationAsk
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.capture.processing.ThermalMonitor
import com.huntercoles.splatman.capture.processing.ThumbnailWriter
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.io.bundle.CAPTURE_FILE
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.Trainer
import com.huntercoles.splatman.viewer.export.ExportTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import java.io.OutputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The Processing screen's Needs poses state for a pose-free scan (#217). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingNeedsPosesTest {

    @TempDir
    lateinit var root: File

    private val started = mutableListOf<File>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A pose-free folder as far as the runner can tell: capture.json with [frames] frames, every file present. */
    private fun poseFreeScan(id: String, frames: Int): File {
        val scan = File(root, id).apply { mkdirs() }
        File(scan, "images").mkdirs()
        val entries = (1..frames).joinToString(",") { i ->
            val path = String.format(Locale.ROOT, "images/frame_%05d.jpg", i)
            File(scan, path).writeBytes(byteArrayOf(1, 2, 3))
            """{"file_path":"$path","timestamp_ns":$i,"w":1920,"h":1080,"sharpness":1.0}"""
        }
        File(scan, "imu.csv").writeText("t,sensor,x,y,z\n")
        File(scan, "calibration.json").writeText(CALIBRATION)
        File(scan, CAPTURE_FILE).writeText(
            """{"splatman_capture_version":1,"poses":"none","timestamp_source":"REALTIME",""" +
                """"imu_file_path":"imu.csv","calibration_file_path":"calibration.json","frames":[$entries]}""",
        )
        return scan
    }

    private fun viewModel(scanId: String): ProcessingViewModel {
        val repository = ProcessingRepository(
            object : Trainer {
                override fun train(input: TrainingInput): Flow<TrainingProgress> = error("a pose-free scan must not train")
            },
            KeyframeDecoder { _, _ -> error("unused") },
            object : ThermalMonitor {
                override val severe = MutableStateFlow(false)
            },
            TrainingConfig(),
            { Long.MAX_VALUE },
            Dispatchers.Unconfined,
            ThumbnailWriter({ _, _, _, _ -> }, 0),
        )
        return ProcessingViewModel(
            repository = repository,
            starter = { started += it },
            deleter = {},
            notificationAsk = object : NotificationAsk {
                override val asked = false
                override fun markAsked() = Unit
            },
            exportTarget = NoExport,
            settings = SettingsPreferences(NoStore),
            folders = ScanFolders(root),
            savedStateHandle = SavedStateHandle(mapOf(ProcessingViewModel.ARG_SCAN_ID to scanId)),
        )
    }

    @Test
    fun `a pose-free scan shows needs poses with its frame count and does not start a run`() {
        poseFreeScan("scan1", frames = 4)

        val state = viewModel("scan1").uiState.value

        assertEquals(ProcessingState.NeedsPoses(4), state.state)
        assertTrue(state.needsPoses)
        assertEquals(4, state.frameCount)
        assertFalse(state.failed)
        assertFalse(state.canRetry)
        assertFalse(state.canCancel)
        assertFalse(state.done)
        assertNull(state.failureCopy)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `Back to library leaves straight away, no notification ask`() = runTest {
        poseFreeScan("scan2", frames = 1)
        val vm = viewModel("scan2")

        vm.onIntent(ProcessingIntent.Leave)

        assertEquals(ProcessingEvent.Leave, withTimeout(1_000) { vm.effects.first() })
    }

    @Test
    fun `the copy is the not-built line plus one line that the frames are saved`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        val title = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "proc_needs_poses_title" }.textContent
        val plurals = doc.getElementsByTagName("plurals")
        val frames = (0 until plurals.length).map { plurals.item(it) as Element }
            .single { it.getAttribute("name") == "proc_needs_poses_frames" }
        val items = frames.getElementsByTagName("item")
        val lines = (0 until items.length).map { items.item(it).textContent }

        assertEquals("Camera positions not built yet", title)
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue(line.contains("%1\$d") && line.contains("saved"), line)
            assertFalse(line.contains("\n"), line)
        }
    }

    private object NoStore : PreferenceStore {
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) = Unit
    }

    private object NoExport : ExportTarget {
        override fun openDocument(uri: String): OutputStream = error("unused")
        override fun createInFolder(treeUri: String, fileName: String): String = error("unused")
        override fun deleteDocument(uri: String) = Unit
        override fun clearShareFiles() = Unit
        override fun openShareFile(fileName: String): OutputStream = error("unused")
        override fun shareUri(fileName: String): String = error("unused")
        override fun freeBytes(): Long? = null
    }

    private companion object {
        const val CALIBRATION = """{"camera_id":"0","sensor_orientation_deg":90,"timestamp_source":"REALTIME",""" +
            """"pixel_array_size":null,"active_array":null,"pre_correction_active_array":null,""" +
            """"physical_size_mm":null,"focal_lengths_mm":null,"lens_intrinsic_calibration":null,""" +
            """"lens_distortion":null,"lens_pose_rotation":null,"lens_pose_translation":null,""" +
            """"lens_pose_reference":null,"image":{"w":1920,"h":1080},""" +
            """"intrinsics":{"fl_x":1489.6,"fl_y":1489.6,"cx":960.0,"cy":540.0,"source":"FOCAL_LENGTH"}}"""
    }
}
