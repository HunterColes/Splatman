package com.huntercoles.splatman.splat.io.ply

import java.io.IOException

/** A PLY stream this codec cannot read: the message says what is wrong and, for the body, at which vertex. */
class PlyFormatException(message: String) : IOException(message)
