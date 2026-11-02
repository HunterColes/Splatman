package com.huntercoles.splatman.capture.preview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The surface the tracker draws the live camera into. [onTarget] gets a [PreviewTarget] when the surface is ready or
 * resized, and null when it is about to be destroyed; it must not return before the tracker has stopped drawing.
 */
@Composable
fun CameraPreview(onTarget: (PreviewTarget?) -> Unit, modifier: Modifier = Modifier) {
    val currentOnTarget = rememberUpdatedState(onTarget)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            val rotation = display?.rotation ?: 0
                            currentOnTarget.value(PreviewTarget(holder.surface, width, height, rotation))
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            currentOnTarget.value(null)
                        }
                    },
                )
            }
        },
    )
}
