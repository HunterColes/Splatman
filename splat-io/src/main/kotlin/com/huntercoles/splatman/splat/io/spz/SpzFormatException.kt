package com.huntercoles.splatman.splat.io.spz

import java.io.IOException

/** An SPZ stream this codec cannot read: the message says what is wrong (gzip, magic, version, truncation). */
class SpzFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)
