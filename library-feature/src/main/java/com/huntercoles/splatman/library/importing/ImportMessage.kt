package com.huntercoles.splatman.library.importing

import androidx.annotation.StringRes
import com.huntercoles.splatman.library.R

/** One format argument of an import message; the screen formats counts and sizes for the user's locale. */
sealed interface MessageArg {
    data class Text(val value: String) : MessageArg

    data class Count(val value: Long) : MessageArg

    data class Bytes(val value: Long) : MessageArg
}

/** An error dialog's message: a string resource (copy keys `imp_*`) and its arguments. */
data class ImportMessage(@StringRes val text: Int, val args: List<MessageArg> = emptyList())

/** The error message for each failed [ImportValidation] (docs/design/screens/import.md, States); null when valid. */
fun ImportValidation.message(): ImportMessage? = when (this) {
    is ImportValidation.Valid -> null
    ImportValidation.PointCloud -> ImportMessage(R.string.imp_point_cloud)
    is ImportValidation.Unsupported -> ImportMessage(R.string.imp_unsupported, listOf(MessageArg.Text(extension)))
    ImportValidation.Corrupt -> ImportMessage(R.string.imp_corrupt)
    is ImportValidation.TooLarge -> ImportMessage(R.string.imp_too_large, listOf(MessageArg.Count(splatCount)))
    is ImportValidation.NoSpace -> ImportMessage(
        R.string.imp_low_storage,
        listOf(MessageArg.Bytes(neededBytes), MessageArg.Bytes(availableBytes)),
    )
}
