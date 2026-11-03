package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.library.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Each validation result maps to its `imp_*` message (docs/design/screens/import.md, States). */
class ImportMessageTest {

    @Test
    fun `a point cloud PLY says it is a point cloud, not a splat`() {
        assertEquals(ImportMessage(R.string.imp_point_cloud), ImportValidation.PointCloud.message())
    }

    @Test
    fun `an unsupported file names its extension`() {
        assertEquals(
            ImportMessage(R.string.imp_unsupported, listOf(MessageArg.Text(".obj"))),
            ImportValidation.Unsupported(".obj").message(),
        )
    }

    @Test
    fun `a corrupt file says it looks damaged`() {
        assertEquals(ImportMessage(R.string.imp_corrupt), ImportValidation.Corrupt.message())
    }

    @Test
    fun `a file over the budget gives its splat count`() {
        assertEquals(
            ImportMessage(R.string.imp_too_large, listOf(MessageArg.Count(12_000_000))),
            ImportValidation.TooLarge(12_000_000).message(),
        )
    }

    @Test
    fun `no space gives what is needed and what is free, in the export wording`() {
        assertEquals(
            ImportMessage(R.string.imp_low_storage, listOf(MessageArg.Bytes(5_000), MessageArg.Bytes(1_000))),
            ImportValidation.NoSpace(neededBytes = 5_000, availableBytes = 1_000).message(),
        )
    }

    @Test
    fun `a valid file has no error message`() {
        assertNull(ImportValidation.Valid("Desk", ImportFixtures.cloud(2)).message())
    }
}
