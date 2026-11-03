package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.tracking.CameraImage

/**
 * Keeps an `ImageReader` open while frames it handed out are still open (#340). `ImageReader.close()` closes every
 * image it handed out, so closing it in [Camera2FrameSource.suspend] (backgrounding) or [Camera2FrameSource.close]
 * while the recorder still measured a frame, or had one waiting, made that frame's next plane read throw
 * "Image is already closed". [retire] marks the reader done: it is closed when the last frame out is closed, or at
 * once when none is. Thread-safe.
 */
internal class ReaderLease(private val reader: AutoCloseable) {
    private var out = 0
    private var retired = false
    private var closed = false

    /** Counts one more frame out; false once retired (the caller closes that image itself). */
    @Synchronized
    fun acquire(): Boolean {
        if (retired) return false
        out++
        return true
    }

    /** One frame from [acquire] was closed. */
    @Synchronized
    fun release() {
        check(out > 0) { "release without acquire" }
        out--
        closeIfDone()
    }

    /** No more frames: closes the reader now, or when the last frame out is closed. */
    @Synchronized
    fun retire() {
        retired = true
        closeIfDone()
    }

    private fun closeIfDone() {
        if (!retired || out > 0 || closed) return
        closed = true
        reader.close()
    }
}

/** A [CameraImage] counted in a [ReaderLease]: [close] closes the image, then releases the lease, once. */
internal class LeasedImage(private val image: CameraImage, private val lease: ReaderLease) : CameraImage by image {
    @Volatile private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        try {
            image.close()
        } finally {
            lease.release()
        }
    }
}
