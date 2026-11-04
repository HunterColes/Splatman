package com.huntercoles.splatman.splat.mesh

/** The cloud has no surface to mesh: empty, all faint, or no density reaches the iso level (ADR 0018 point 5). */
class MeshEmptyException(message: String = "the cloud has no surface to mesh") : RuntimeException(message)
