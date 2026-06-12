package com.vitalwork.app.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TutorialPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("tutorial_prefs", Context.MODE_PRIVATE)

    fun isFirstLaunchPending(): Boolean = prefs.getBoolean("first_launch_pending", true)

    fun markFirstLaunchDone() {
        prefs.edit().putBoolean("first_launch_pending", false).apply()
    }
}
