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

    val widgetIds: IntArray
        get() = listOf(TasksWidget::class.java, CalendarWidget::class.java)
            .flatMap {
                appWidgetManager?.getAppWidgetIds(ComponentName(context, it))?.toList()
                    ?: emptyList()
            }
            .toIntArray()

    fun rebuildWidgets(vararg appWidgetIds: Int) {
        if (appWidgetIds.isEmpty()) {
            return
        }
        Timber.d("rebuildWidgets(${appWidgetIds.joinToString()})")
        // Each widget id is routed to the provider that owns it
        appWidgetIds
            .groupBy { providerFor(it) }
            .forEach { (provider, ids) ->
                val intent = Intent(context, provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids.toIntArray())
                    .apply { action = AppWidgetManager.ACTION_APPWIDGET_UPDATE }
                context.sendBroadcast(intent)
            }
    }

    private fun providerFor(appWidgetId: Int): Class<*> =
        if (appWidgetManager?.getAppWidgetInfo(appWidgetId)?.provider?.className ==
            CalendarWidget::class.java.name
        ) {
            CalendarWidget::class.java
        } else {
            TasksWidget::class.java
        }

    fun updateWidgets() {
        rebuildWidgets(*widgetIds)
    }

    fun exists(id: Int) = appWidgetManager?.getAppWidgetInfo(id) != null
}
