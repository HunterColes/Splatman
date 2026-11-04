package com.huntercoles.splatman.library.domain

import android.content.Context
import android.text.format.DateFormat
import com.huntercoles.splatman.library.R
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** Names a new captured scan from its folder id and its creation time. */
fun interface ScanNamer {
    fun name(id: String, createdAtMs: Long): String
}

/**
 * copy.md `scan_default_name` (#426): [template] (`Scan %1$s`) filled with the scan's local start date and time, the
 * date from [datePattern] (the locale's best `dMMM`) and the time from [timePattern] (the locale's `Hm` or `hm`),
 * joined by ", ". The start is read from an id `scan_yyyyMMdd_HHmmss` (local time), else from `createdAtMs`.
 */
class DefaultScanName(
    private val template: String,
    private val datePattern: String,
    private val timePattern: String,
    private val locale: Locale,
    private val zone: ZoneId,
) : ScanNamer {

    override fun name(id: String, createdAtMs: Long): String {
        val start = startOf(id) ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMs), zone)
        val stamp = try {
            DateTimeFormatter.ofPattern(datePattern, locale).format(start) + ", " +
                DateTimeFormatter.ofPattern(timePattern, locale).format(start)
        } catch (_: IllegalArgumentException) {
            DateTimeFormatter.ofPattern(FALLBACK_PATTERN, locale).format(start)
        } catch (_: DateTimeException) {
            DateTimeFormatter.ofPattern(FALLBACK_PATTERN, locale).format(start)
        }
        return String.format(locale, template, stamp)
    }

    companion object {
        private val ID = Regex("""scan_(\d{8}_\d{6})""")
        private val ID_TIME = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private const val FALLBACK_PATTERN = "uuuu-MM-dd HH:mm"

        /** The local start time in a `scan_yyyyMMdd_HHmmss` id, or null when [id] is not one or not a real time. */
        fun startOf(id: String): LocalDateTime? {
            val match = ID.matchEntire(id) ?: return null
            return try {
                LocalDateTime.parse(match.groupValues[1], ID_TIME)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /** The phone's namer: its locale, time zone and 12/24-hour setting, read at each call. */
        fun of(context: Context): ScanNamer = ScanNamer { id, createdAtMs ->
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val hours = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
            DefaultScanName(
                template = context.getString(R.string.scan_default_name),
                datePattern = DateFormat.getBestDateTimePattern(locale, "dMMM"),
                timePattern = DateFormat.getBestDateTimePattern(locale, hours),
                locale = locale,
                zone = ZoneId.systemDefault(),
            ).name(id, createdAtMs)
        }
    }
}
