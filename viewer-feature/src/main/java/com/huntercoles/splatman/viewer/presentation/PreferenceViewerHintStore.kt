package com.huntercoles.splatman.viewer.presentation

import android.content.Context
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SharedPreferenceStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** The gesture hints' flags in the `viewer_prefs` shared preferences. */
@Singleton
class PreferenceViewerHintStore internal constructor(private val store: PreferenceStore) : ViewerHintStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPreferenceStore(context, PREFS))

    override val hintSeen: Boolean get() = store.getBoolean(HINT_SEEN, false)

    override fun markHintSeen() = store.putBoolean(HINT_SEEN, true)

    override val roomHintSeen: Boolean get() = store.getBoolean(ROOM_HINT_SEEN, false)

    override fun markRoomHintSeen() = store.putBoolean(ROOM_HINT_SEEN, true)

    private companion object {
        const val PREFS = "viewer_prefs"
        const val HINT_SEEN = "gesture_hint_seen"
        const val ROOM_HINT_SEEN = "room_hint_seen"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ViewerHintModule {
    @Binds
    abstract fun bindHints(store: PreferenceViewerHintStore): ViewerHintStore
}
