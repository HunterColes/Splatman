package com.huntercoles.splatman.splat.packet

/** File formats a cloud can be exported to (ADR 0002, 0007). */
enum class ExportFormat(
    /** File extension without the dot. */
    val extension: String,
) {
    /** 3DGS PLY, the default. */
    PLY("ply"),

    /** antimatter15 `.splat`. */
    SPLAT("splat"),

    /** Niantic SPZ. */
    SPZ("spz"),
    ;

    companion object {
        /** Version of this format list. */
        const val VERSION = 1
    }
}
