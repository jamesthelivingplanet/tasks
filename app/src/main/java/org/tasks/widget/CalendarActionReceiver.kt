package org.tasks.widget

import android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.preferences.Preferences
import java.time.LocalDate
import javax.inject.Inject

/** Handles taps inside [CalendarWidget]. Not exported, so only the widget's own intents reach it. */
@AndroidEntryPoint
class CalendarActionReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var appWidgetManager: AppWidgetManager

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID)
        if (widgetId == INVALID_APPWIDGET_ID) {
            return
        }
        val widgetPreferences = WidgetPreferences(context, preferences, widgetId)
        val today = LocalDate.now()
        val selected = widgetPreferences.calendarSelectedDay?.let(LocalDate::ofEpochDay) ?: today
        val monthMode = CalendarWidget.isMonthMode(
            android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        )
        val newSelected = when (intent.action) {
            ACTION_SELECT_DAY -> LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_DAY, today.toEpochDay()))
            ACTION_TODAY -> today
            ACTION_PREVIOUS ->
                if (monthMode) CalendarDays.pageMonth(selected, today, -1) else selected.minusDays(7)
            ACTION_NEXT ->
                if (monthMode) CalendarDays.pageMonth(selected, today, 1) else selected.plusDays(7)
            else -> return
        }
        // Following today (null) lets the selection move with midnight rollover
        widgetPreferences.calendarSelectedDay = newSelected.takeIf { it != today }?.toEpochDay()
        appWidgetManager.rebuildWidgets(widgetId)
    }

    companion object {
        const val ACTION_SELECT_DAY = "org.tasks.calendar.SELECT_DAY"
        const val ACTION_TODAY = "org.tasks.calendar.TODAY"
        const val ACTION_PREVIOUS = "org.tasks.calendar.PREVIOUS"
        const val ACTION_NEXT = "org.tasks.calendar.NEXT"
        const val EXTRA_DAY = "extra_day"
    }
}
