package se.sensnology.spotnav.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.prices.AreaPublication
import se.sensnology.spotnav.prices.AreaSelection
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceRepository
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Supplements AppWidgetProvider's regular 30-minute interval around each configured area's own
 * expected publication (13:00 Brussels for ENTSO-E, 16:00 UK time for Agile, 20:15 Madrid for PVPC).
 * Alarms are intentionally inexact to avoid requiring exact-alarm permission.
 */
object PriceUpdateScheduler {
    /** Minutes after an expected publication at which it is looked for, before the 30-minute retry. */
    private val publicationAttempts = listOf(0L, 10L, 20L, 35L)
    private val RETRY: Duration = Duration.ofMinutes(30)
    private const val REQUEST_CODE = 13_035

    fun scheduleNext(context: Context, tomorrowAvailable: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            .filter { WidgetSettings.isConfigured(context, it) }
        // An area missing from the catalogue contributes no clock.
        val configured = ids.mapNotNull { PriceMarkets.find(WidgetSettings.load(context, it).area)?.publication }
        val default = AreaSelection.defaultArea(PriceMarkets.all, AppLanguageSettings.region(context))
            ?.let { PriceMarkets.find(it)?.publication }
        // No area to schedule against.
        val next = nextCheck(Instant.now(), configured.ifEmpty { listOfNotNull(default) }, tomorrowAvailable) ?: return
        val alarm = context.getSystemService(AlarmManager::class.java)
        // Five-minute window: lets Android batch wake-ups without drifting far from publication.
        alarm.setWindow(
            AlarmManager.RTC_WAKEUP,
            next.toEpochMilli(),
            5 * 60 * 1000L,
            pendingIntent(context)
        )
        Log.i("SpotNavScheduler", "Next publication check $next available=$tomorrowAvailable")
    }

    /**
     * The next look for tomorrow's prices, or `null` with no area. With tomorrow missing: the next of
     * each area's attempts today (its publication time in its own zone, then a few minutes after),
     * else 30 minutes from [now]. With tomorrow in hand: the earliest area's publication tomorrow.
     */
    internal fun nextCheck(now: Instant, publications: List<AreaPublication>, tomorrowAvailable: Boolean): Instant? =
        publications.distinct().minOfOrNull { publication ->
            val today = now.atZone(publication.zoneId).toLocalDate()
            if (tomorrowAvailable) {
                publication.on(today.plusDays(1))
            } else {
                val expected = publication.on(today)
                publicationAttempts.map { expected.plus(Duration.ofMinutes(it)) }.firstOrNull { it.isAfter(now) }
                    ?: now.plus(RETRY).truncatedTo(ChronoUnit.MINUTES)
            }
        }

    fun scheduleForActiveWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            .filter { WidgetSettings.isConfigured(context, it) }
        val areas = ids.map { WidgetSettings.load(context, it).area }.distinct()
        scheduleNext(context, areas.isNotEmpty() && areas.all { PriceRepository.hasCachedTomorrow(context, it) })
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PriceWidgetProvider::class.java).apply {
            action = PriceWidgetProvider.ACTION_PUBLICATION_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
