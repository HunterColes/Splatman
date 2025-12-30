package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.ViewModel
import com.huntercoles.splatman.core.preferences.AudioPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(
    val audioPreferences: AudioPreferences
) : ViewModel()
