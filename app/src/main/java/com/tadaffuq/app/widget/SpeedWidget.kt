package com.tadaffuq.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tadaffuq.app.MainActivity
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.download.DownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntry { fun manager(): DownloadManager }

private fun manager(ctx: Context) = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntry::class.java).manager()

/** ودجت السرعة: قراءات حقيقية من المدير، وتُحدَّث عند تغيّر الحالة وكل نصف دقيقة أثناء التنزيل فقط. */
class SpeedWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = manager(context)
        val speed = m.live.value.values.sumOf { it.speedBps }
        val running = m.runningIds.value.size
        val ar = context.resources.configuration.locales[0].language == "ar"
        provideContent { Content(context, speed, running, ar) }
    }

    @Composable
    private fun Content(ctx: Context, speed: Long, running: Int, ar: Boolean) {
        val navy = ColorProvider(Color(0xFF142B4A))
        val mint = ColorProvider(Color(0xFF34D6AE))
        val white = ColorProvider(Color.White)
        Column(
            GlanceModifier.fillMaxSize().background(navy).cornerRadius(20.dp).padding(14.dp)
                .clickable(actionStartActivity(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_DOWNLOADS))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ctx.getString(R.string.app_name), style = TextStyle(color = mint, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Text(Fmt.speed(speed, ar), style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Text(if (running > 0) ctx.resources.getQuantityString(R.plurals.notif_running, running).replace("%d", running.toString()) else ctx.getString(R.string.home_idle),
                style = TextStyle(color = white, fontSize = 12.sp))
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Text(ctx.getString(R.string.action_pause_all), GlanceModifier.clickable(actionRunCallback<PauseAll>()).padding(6.dp), style = TextStyle(color = mint, fontSize = 13.sp))
                Spacer(GlanceModifier.height(1.dp).padding(horizontal = 8.dp))
                Text(ctx.getString(R.string.action_resume_all), GlanceModifier.clickable(actionRunCallback<ResumeAll>()).padding(6.dp), style = TextStyle(color = mint, fontSize = 13.sp))
            }
        }
    }

    companion object {
        suspend fun refresh(ctx: Context) {
            if (GlanceAppWidgetManager(ctx).getGlanceIds(SpeedWidget::class.java).isNotEmpty()) SpeedWidget().updateAll(ctx)
        }
    }
}

class PauseAll : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) { manager(context).apply { start(); pauseAll() }; SpeedWidget.refresh(context) }
}

class ResumeAll : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) { manager(context).apply { start(); resumeAll() }; SpeedWidget.refresh(context) }
}

class SpeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpeedWidget()
}
