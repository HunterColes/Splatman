package com.huntercoles.splatman.pipeline

import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.library.presentation.ScanSharer
import com.huntercoles.splatman.library.presentation.SharedFiles
import com.huntercoles.splatman.viewer.export.ExportCodecs
import com.huntercoles.splatman.viewer.export.ExportFileName
import com.huntercoles.splatman.viewer.export.ExportFormat
import com.huntercoles.splatman.viewer.export.ExportResult
import com.huntercoles.splatman.viewer.export.ExportTarget
import com.huntercoles.splatman.viewer.export.ExportUseCase
import com.huntercoles.splatman.viewer.presentation.load.SplatFileDecoder
import com.huntercoles.splatman.viewer.presentation.load.SplatOpener
import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * The Library's Share (#177) through the viewer's export (#20): each built scan is written in Settings' default
 * export format into the export cache folder, a bundled sample is copied as it is, and the files go out as the
 * export FileProvider's `content://` URIs. A scan whose splat cannot be read fails the whole share (null).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LibraryShareModule {

    @Provides
    fun provideScanSharer(
        useCase: ExportUseCase,
        target: ExportTarget,
        opener: SplatOpener,
        settings: SettingsPreferences,
        @IoDispatcher io: CoroutineDispatcher,
    ): ScanSharer = ScanSharer { scans ->
        val chosen = ExportFormat.of(settings.exportFormat.value)
        val format = if (ExportCodecs.of(chosen) != null) chosen else ExportCodecs.DEFAULT
        val names = ExportFileName.unique(scans.map { it.name }, format).toMutableList()
        useCase.cleanUp(target::clearShareFiles)
        for ((i, scan) in scans.withIndex()) {
            val sample = SplatSamples.fromScanId(scan.id)
            val ok = if (sample != null) {
                copySample(opener, target, sample, names[i].substringBeforeLast('.') + "." + sampleExtension(sample), io)
                    ?.also { names[i] = it } != null
            } else {
                val source = SplatSource.Scan.fromScanId(scan.id)
                source != null && useCase.export(
                    load = { opener.open(source).use { SplatFileDecoder().decode(it).cloud } },
                    format = format,
                    open = { target.openShareFile(names[i]) },
                ) is ExportResult.Done
            }
            if (!ok) {
                useCase.cleanUp(target::clearShareFiles)
                return@ScanSharer null
            }
        }
        try {
            SharedFiles(names.map(target::shareUri), names.toList())
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "No share URI for %s", names)
            null
        }
    }

    private fun sampleExtension(sample: SplatSource.Sample): String = sample.assetPath.substringAfterLast('.', "ply")

    /** Copies the bundled bytes as they are; the name written, or null when the asset cannot be read. */
    private suspend fun copySample(
        opener: SplatOpener,
        target: ExportTarget,
        sample: SplatSource.Sample,
        name: String,
        io: CoroutineDispatcher,
    ): String? = withContext(io) {
        try {
            opener.open(sample).use { input -> target.openShareFile(name).use { input.copyTo(it) } }
            name
        } catch (e: IOException) {
            Timber.w(e, "Sharing sample %s failed", sample.assetPath)
            null
        }
    }
}
