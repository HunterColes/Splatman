package com.huntercoles.splatman.library.domain.repository

import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import kotlinx.coroutines.flow.Flow

/** The scans on this phone: a Room row per folder under `scans/`, newest first. */
interface ScanRepository {

    fun scans(): Flow<List<Scan>>

    suspend fun get(id: String): Scan?

    /** Makes an empty scan folder and its CAPTURED row. */
    suspend fun create(name: String, createdAtMs: Long): Scan

    /** Sets [status]; counts left null keep their value. */
    suspend fun updateStatus(id: String, status: ScanStatus, splatCount: Int? = null, keyframeCount: Int? = null)

    suspend fun rename(id: String, name: String)

    /** Cancels processing first when the scan is PROCESSING, then deletes its folder, then its row. */
    suspend fun delete(id: String)

    /** Adopts scan folders that have no row and drops rows whose folder is gone. */
    suspend fun sync()

    /**
     * Reads scan [id]'s folder again: READY with its splat count when `splat.ply` reads, CAPTURED with only a bundle,
     * else FAILED; adopts the folder when it has no row yet. The name and date are kept. No folder: nothing happens.
     */
    suspend fun refresh(id: String)
}

/** Stops the background build of a scan; the app binds it to the processing runner. */
fun interface ProcessingCanceller {
    /** Returns once processing of [scanId] has stopped writing to its folder. */
    suspend fun cancel(scanId: String)
}

/** The splat files bundled with the app. */
fun interface SampleRepository {
    suspend fun samples(): List<Sample>
}
