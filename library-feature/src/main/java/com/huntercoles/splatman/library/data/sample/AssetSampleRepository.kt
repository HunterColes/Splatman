package com.huntercoles.splatman.library.data.sample

import android.content.res.AssetManager
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Lists the splat files in `assets/samples` (the licence notes beside them are skipped). */
class AssetSampleRepository(
    private val assets: AssetManager,
    private val io: CoroutineDispatcher,
) : SampleRepository {

    override suspend fun samples(): List<Sample> = withContext(io) {
        sampleFiles(assets.list(SAMPLES_DIR).orEmpty().toList()).map(Sample::fromFileName)
    }

    companion object {
        const val SAMPLES_DIR = "samples"

        /** The splat files among [names], sorted. */
        fun sampleFiles(names: List<String>): List<String> =
            names.filter { name -> SPLAT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }.sorted()

        private val SPLAT_EXTENSIONS = listOf(".ply", ".splat", ".spz")
    }
}
