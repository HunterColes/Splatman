package com.huntercoles.splatman.viewer.presentation.load

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject

/** Samples from the APK's assets; documents through the content resolver (the picker grants read access). */
class AndroidSplatOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) : SplatOpener {

    override fun open(source: SplatSource): InputStream = when (source) {
        is SplatSource.Sample -> context.assets.open(source.assetPath)
        is SplatSource.Document -> context.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: throw FileNotFoundException("The file could not be opened.")
    }

    override fun displayName(source: SplatSource): String = when (source) {
        is SplatSource.Sample -> source.name
        is SplatSource.Document -> queryName(Uri.parse(source.uri)) ?: source.name
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ViewerLoadModule {
    @Binds
    abstract fun bindOpener(opener: AndroidSplatOpener): SplatOpener

    @Binds
    abstract fun bindLoader(loader: DefaultSplatLoader): SplatLoader
}
