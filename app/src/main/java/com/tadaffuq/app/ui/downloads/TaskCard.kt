package com.tadaffuq.app.ui.downloads

import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import com.tadaffuq.app.core.sr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.components.ProgressLine
import com.tadaffuq.app.ui.errorText
import com.tadaffuq.app.ui.statusText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.FileCategory
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Snapshot

fun categoryIcon(name: String): ImageVector = when (FileNames.category(name)) {
    FileCategory.VIDEO -> Icons.Rounded.Movie
    FileCategory.AUDIO -> Icons.Rounded.MusicNote
    FileCategory.IMAGES -> Icons.Rounded.Image
    FileCategory.COMPRESSED -> Icons.Rounded.Archive
    FileCategory.DOCUMENTS -> Icons.Rounded.Description
    FileCategory.PROGRAMS -> Icons.Rounded.Apps
    FileCategory.DISK_IMAGES -> Icons.Rounded.Album
    FileCategory.MODELS -> Icons.Rounded.Memory
    FileCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

/** لون الفئة: خلفية باستيل وأيقونة مشبعة تميّز نوع الملف بنظرة. */
fun categoryTint(name: String): com.tadaffuq.app.ui.components.Tint = categoryTint(FileNames.category(name))

fun categoryTint(c: FileCategory): com.tadaffuq.app.ui.components.Tint = when (c) {
    FileCategory.VIDEO -> com.tadaffuq.app.ui.components.Tint.CORAL
    FileCategory.AUDIO -> com.tadaffuq.app.ui.components.Tint.VIOLET
    FileCategory.IMAGES -> com.tadaffuq.app.ui.components.Tint.MINT
    FileCategory.COMPRESSED -> com.tadaffuq.app.ui.components.Tint.AMBER
    FileCategory.DOCUMENTS -> com.tadaffuq.app.ui.components.Tint.BLUE
    FileCategory.PROGRAMS -> com.tadaffuq.app.ui.components.Tint.VIOLET
    FileCategory.DISK_IMAGES -> com.tadaffuq.app.ui.components.Tint.SLATE
    FileCategory.MODELS -> com.tadaffuq.app.ui.components.Tint.CORAL
    FileCategory.OTHER -> com.tadaffuq.app.ui.components.Tint.MINT
}

fun statusIcon(s: TaskStatus): ImageVector = when (s) {
    TaskStatus.RUNNING -> Icons.Rounded.ArrowDownward
    TaskStatus.VERIFYING -> Icons.Rounded.VerifiedUser
    TaskStatus.COMPLETED -> Icons.Rounded.CheckCircle
    TaskStatus.FAILED -> Icons.Rounded.ErrorOutline
    TaskStatus.PAUSED -> Icons.Rounded.Pause
    TaskStatus.WAITING_NETWORK -> Icons.Rounded.HourglassTop
    TaskStatus.QUEUED -> Icons.Rounded.Schedule
}

@Composable
fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.RUNNING, TaskStatus.VERIFYING -> T.extra.chartLine
    TaskStatus.COMPLETED -> T.extra.success
    TaskStatus.FAILED -> T.extra.danger
    TaskStatus.WAITING_NETWORK -> T.extra.warning
    else -> T.extra.paused
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    task: TaskEntity,
    live: Snapshot?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimary: () -> Unit,
    compact: Boolean = false,
    showSegments: Boolean = false,
) {
    val downloaded = live?.downloaded ?: task.downloadedBytes
    val total = live?.total ?: task.totalBytes
    val color = statusColor(task.status)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else T.extra.card,
    ) {
        Column(
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button).padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 8.dp else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) com.tadaffuq.app.ui.components.TintIcon(categoryIcon(task.fileName), categoryTint(task.fileName))
                if (!compact) Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.fileName, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle(task, live, downloaded, total), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
                if (!selectionMode) PrimaryAction(task.status, onPrimary)
            }
            Spacer(Modifier.height(8.dp))
            if (task.status != TaskStatus.COMPLETED) {
                val fraction = if (total > 0) downloaded.toFloat() / total else if (task.status == TaskStatus.RUNNING) null else 0f
                if (showSegments && live != null && live.total > 0) com.tadaffuq.app.ui.components.SegmentMap(live.segments, live.total, color, com.tadaffuq.app.core.pr(R.plurals.segments_a11y, live.segments.size, live.segments.size))
                else ProgressLine(fraction, color)
                Spacer(Modifier.height(6.dp))
            }
            // الحالة برمز ونص ولون معاً، والنسبة في الطرف المقابل
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.tadaffuq.app.ui.components.StatusChip(statusIcon(task.status), statusText(task.status), color)
                Spacer(Modifier.weight(1f))
                if (task.status != TaskStatus.COMPLETED && total > 0) Text(Fmt.percent(downloaded, total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (task.status != TaskStatus.COMPLETED) {
                if (task.status == TaskStatus.FAILED) Text(if (task.errorCode == "SPACE" && task.errorDetail == "COPY") stringResource(R.string.err_space_copy) else errorText(task.errorCode), style = MaterialTheme.typography.bodySmall, color = T.extra.danger, modifier = Modifier.padding(top = 4.dp))
                task.scheduledAt?.takeIf { it > System.currentTimeMillis() && task.status.isWaiting }?.let {
                    Text(com.tadaffuq.app.core.sr(R.string.schedule_at, Fmt.date(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (task.hashOk == false) Text(stringResource(R.string.err_checksum), style = MaterialTheme.typography.bodySmall, color = T.extra.danger)
            // اكتمل لكن النقل/النشر لم يتم: الملف سليم في مكانه، والسبب ظاهر
            if (task.status == TaskStatus.COMPLETED) when (task.errorDetail) {
                "MOVE_SPACE" -> stringResource(R.string.move_space)
                "MOVE_FAILED" -> stringResource(R.string.move_failed_kept)
                else -> null
            }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = T.extra.warning) }
        }
    }
}

@Composable
private fun subtitle(task: TaskEntity, live: Snapshot?, downloaded: Long, total: Long): String = buildString {
    when (task.status) {
        TaskStatus.COMPLETED -> {
            append(Fmt.bytes(task.totalBytes))
            task.completedAt?.let { append(" · ").append(Fmt.date(it)) }
            if (task.hashOk == true) append(" · ").append(stringResource(R.string.hash_verified))
        }
        TaskStatus.RUNNING -> {
            append(Fmt.bytes(downloaded)).append(" / ").append(Fmt.bytes(total))
            if (live != null) {
                append(" · ").append(Fmt.speed(live.speedBps))
                if (live.etaSeconds >= 0) append(" · ").append(sr(R.string.eta, Fmt.duration(live.etaSeconds)))
                append(" · ").append(com.tadaffuq.app.core.pr(R.plurals.connections_n, live.connections, live.connections))
            }
        }
        else -> {
            append(Fmt.bytes(downloaded)).append(" / ").append(Fmt.bytes(total))
        }
    }
}

@Composable
private fun PrimaryAction(status: TaskStatus, onClick: () -> Unit) {
    val (icon, label) = when (status) {
        TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK -> Icons.Rounded.Pause to R.string.action_pause
        TaskStatus.PAUSED -> Icons.Rounded.PlayArrow to R.string.action_resume
        TaskStatus.FAILED -> Icons.Rounded.Refresh to R.string.action_retry
        else -> return
    }
    // الإيقاف كهرماني، والاستئناف وإعادة المحاولة أخضر (وسيلة التعافي، لا لون الفشل)
    val tint = com.tadaffuq.app.ui.components.roleColor(if (icon == Icons.Rounded.Pause) com.tadaffuq.app.ui.components.Role.PAUSE else com.tadaffuq.app.ui.components.Role.START)
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label), tint = tint) }
}
