package com.huntercoles.splatman.core.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The first-run flag: false until onboarding is finished or skipped. */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("shell_prefs", Context.MODE_PRIVATE)

    val isOnboardingDone: Boolean
        get() = prefs.getBoolean(ONBOARDING_DONE_KEY, false)

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(ONBOARDING_DONE_KEY, done).apply()
    }

    companion object {
        private const val ONBOARDING_DONE_KEY = "onboarding_done"
    }
}
