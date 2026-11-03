package com.huntercoles.splatman.splat.capture

/**
 * What a capture scans (ADR 0013 section 4): one object circled from outside, or a room seen from inside. [key] is
 * the value a bundle stores (`splatman_mode` in `transforms.json`, `mode` in `capture.json`); OBJECT is never written.
 */
enum class ScanMode(val key: String) {
    OBJECT("object"),
    ROOM("room"),
    ;

    companion object {
        /** The mode stored as [key]; null (older bundles) or an unknown value reads as [OBJECT]. */
        fun fromKey(key: String?): ScanMode = entries.firstOrNull { it.key == key } ?: OBJECT
    }
}
