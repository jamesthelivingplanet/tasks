package org.tasks.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

class AppWidgetManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val appWidgetManager: AppWidgetManager? by lazy {
        AppWidgetManager.getInstance(context)
    }

    /** Ids of the list widgets only. */
    val taskListWidgetIds: IntArray
        get() = idsFor(TasksWidget::class.java)

    /** Ids of every widget, list and calendar. */
    val widgetIds: IntArray
        get() = taskListWidgetIds + idsFor(CalendarWidget::class.java)

    private fun idsFor(provider: Class<*>): IntArray =
        appWidgetManager?.getAppWidgetIds(ComponentName(context, provider)) ?: IntArray(0)

    fun rebuildWidgets(vararg appWidgetIds: Int) {
        if (appWidgetIds.isEmpty()) {
            return
        }
        Timber.d("rebuildWidgets(${appWidgetIds.joinToString()})")
        // Each widget id is routed to the provider that owns it
        appWidgetIds.toList()
            .mapNotNull { id -> providerFor(id)?.let { it to id } }
            .groupBy({ it.first }, { it.second })
            .forEach { (provider, ids) ->
                val intent = Intent(context, provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids.toIntArray())
                    .apply { action = AppWidgetManager.ACTION_APPWIDGET_UPDATE }
                context.sendBroadcast(intent)
            }
    }

    /** The provider that owns [appWidgetId], or null if the widget no longer exists. */
    private fun providerFor(appWidgetId: Int): Class<*>? =
        when (appWidgetManager?.getAppWidgetInfo(appWidgetId)?.provider?.className) {
            CalendarWidget::class.java.name -> CalendarWidget::class.java
            TasksWidget::class.java.name -> TasksWidget::class.java
            else -> null
        }

    fun updateWidgets() {
        rebuildWidgets(*widgetIds)
    }

    fun exists(id: Int) = appWidgetManager?.getAppWidgetInfo(id) != null
}
