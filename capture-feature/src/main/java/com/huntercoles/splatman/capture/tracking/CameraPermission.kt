package com.huntercoles.splatman.capture.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

/** The CAMERA permission the tracker needs before a session opens. */
object CameraPermission {
    /** The permission string. */
    const val NAME = Manifest.permission.CAMERA

    /** True when CAMERA is granted to this app. */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}

/**
 * The CAMERA permission request flow for a screen: returns a function that asks the user (the system dialog) and
 * reports the answer to [onResult] (true = granted). Call it when [CameraPermission.isGranted] is false, then start
 * the [PoseSource] once granted.
 */
@Composable
fun rememberCameraPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return remember(launcher) { { launcher.launch(CameraPermission.NAME) } }
}
