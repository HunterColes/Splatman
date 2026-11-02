package com.huntercoles.splatman.capture.processing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app already asked for `POST_NOTIFICATIONS` (asked once, on the first Back during processing). */
interface NotificationAsk {
    val asked: Boolean
    fun markAsked()
}

/** [NotificationAsk] in its own SharedPreferences file. */
@Singleton
class PrefsNotificationAsk @Inject constructor(@ApplicationContext context: Context) : NotificationAsk {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val asked: Boolean get() = prefs.getBoolean(KEY_ASKED, false)

    override fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    private companion object {
        const val PREFS = "processing_prefs"
        const val KEY_ASKED = "notifications_asked"
    }
}
