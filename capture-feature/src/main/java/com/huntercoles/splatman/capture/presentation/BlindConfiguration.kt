package com.huntercoles.splatman.capture.presentation

import android.os.Parcelable
import com.huntercoles.splatman.core.constants.TournamentDefaults
import kotlinx.parcelize.Parcelize

@Parcelize
data class BlindConfiguration(
    val smallestChip: Int = TournamentDefaults.SMALLEST_CHIP,
    val startingChips: Int = TournamentDefaults.STARTING_CHIPS,
    val roundLengthMinutes: Int = TournamentDefaults.ROUND_LENGTH_MINUTES
) : Parcelable
