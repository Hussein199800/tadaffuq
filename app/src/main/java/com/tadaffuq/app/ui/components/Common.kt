package com.tadaffuq.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.engine.SegmentState
import kotlin.math.max

/** تقليل الحركة: لا رسوم متحركة زخرفية (المشهد الحي، الشريط غير المحدد). */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

/** عنوان كبير بروح Apple مع سطر رمادي اختياري. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = T.extra.secondaryText, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = MaterialTheme.shapes.large,
        color = T.extra.card,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
            content = content,
        )
    }
}

/** صف إعداد: عنوان وسطر شرح دائماً، وعنصر تحكم في الطرف. */
@Composable
fun SettingRow(
    title: String,
    description: String,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    tint: Tint? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && tint != null) {
            TintIcon(icon, tint)
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun ToggleRow(title: String, description: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) =
    SettingRow(title, description, icon, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
fun SliderRow(title: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}

@Composable
fun Hairline() = HorizontalDivider(Modifier.padding(start = 16.dp), color = T.extra.hairline)

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** شريط تقدم بزوايا دائرية يتبع اتجاه الواجهة. */
@Composable
fun ProgressLine(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduce = LocalReduceMotion.current
    // حركة لا نهائية فقط لتقدم غير معروف؛ التقدم المعروف ينتقل بنعومة ثم يسكن (لا رسم في كل إطار)
    val shift = if (fraction == null && !reduce) {
        val anim = rememberInfiniteTransition(label = "indeterminate")
        anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shift").value
    } else 0f
    val shown = if (fraction != null && !reduce)
        androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "progress").value
    else (fraction ?: 0f).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (fraction == null && reduce) {
            drawRoundRect(color.copy(alpha = 0.45f), cornerRadius = r)
        } else if (fraction == null) {
            val w = size.width * 0.3f
            val x = (size.width + w) * shift - w
            drawRoundRect(color, topLeft = Offset(if (rtl) size.width - x - w else x, 0f), size = Size(w, size.height), cornerRadius = r)
        } else {
            val w = size.width * shown
            if (w > 0) drawRoundRect(color, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/**
 * خريطة الأجزاء: كل جزء بموضعه الحقيقي من الملف، والمنجز منه ملوّن، وحافة القراءة الحالية لكل جزء
 * غير مكتمل علامة واضحة (أين وصل كل اتصال)، والفواصل بين الأجزاء ظاهرة.
 */
@Composable
fun SegmentMap(segments: List<SegmentState>, total: Long, color: Color, description: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 22.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val divider = T.extra.card
    val edge = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = description }) {
        drawRect(track)
        if (total <= 0) return@Canvas
        fun x(pos: Long) = (pos.toDouble() / total * size.width).toFloat().let { if (rtl) size.width - it else it }
        segments.forEach { s ->
            val a = x(s.start); val b = x(s.start + s.downloaded)
            val left = minOf(a, b); val w = kotlin.math.abs(b - a)
            if (w > 0) drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
            if (!s.done) drawLine(edge, Offset(b, 0f), Offset(b, size.height), strokeWidth = 3f)
            drawLine(divider, Offset(a, 0f), Offset(a, size.height), strokeWidth = 2f)
        }
    }
}

/** صف لكل جزء (مثل ADM): رقمه، شريطه، النسبة، والمنجز من حجمه. الأجزاء مرتبة بموضعها في الملف. */
@Composable
fun SegmentList(segments: List<SegmentState>, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        segments.sortedBy { it.start }.forEachIndexed { i, s ->
            val size = (s.end - s.start + 1).coerceAtLeast(1)
            val frac = (s.downloaded.toDouble() / size).coerceIn(0.0, 1.0)
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(com.tadaffuq.app.core.Fmt.iso((i + 1).toString()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
                ProgressLine(frac.toFloat(), color, Modifier.weight(1f).height(8.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (s.done) "✓ " + com.tadaffuq.app.core.Fmt.bytes(size)
                    else com.tadaffuq.app.core.Fmt.iso("${(frac * 100).toInt()}٪") + " · " + com.tadaffuq.app.core.Fmt.bytes(s.downloaded) + " / " + com.tadaffuq.app.core.Fmt.bytes(size),
                    style = MaterialTheme.typography.labelSmall, color = T.extra.secondaryText,
                )
            }
        }
    }
}

/** رسم حي للسرعة من عيّنات حقيقية (عيّنة كل ثانية). */
@Composable
fun SpeedChart(history: List<Long>, modifier: Modifier = Modifier, description: String) {
    val line = T.extra.chartLine
    val fill = T.extra.chartFill
    val grid = T.extra.hairline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = description }) {
        val maxV = max(history.maxOrNull() ?: 0L, 1L).toFloat() * 1.15f
        for (i in 1..3) {
            val y = size.height * i / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (history.size < 2) return@Canvas
        val step = size.width / (history.size - 1)
        // الأحدث في جهة نهاية القراءة: يمين في الإنجليزية ويسار في العربية
        fun px(i: Int) = if (rtl) size.width - i * step else i * step
        fun py(v: Long) = size.height - (v / maxV) * size.height
        val path = Path().apply {
            moveTo(px(0), py(history[0]))
            for (i in 1 until history.size) lineTo(px(i), py(history[i]))
        }
        val area = Path().apply {
            addPath(path)
            lineTo(px(history.lastIndex), size.height)
            lineTo(px(0), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fill, Color.Transparent)))
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * المشهد الحي: نقاط تتدفق من الخادم إلى الهاتف بسرعة تتناسب مع السرعة الحقيقية،
 * وعددها مع عدد الاتصالات. عند توقف البيانات تتوقف الحركة تماماً (لا قراءات مختلقة).
 */
@Composable
fun LiveScene(speedBps: Long, connections: Int, description: String, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val flowing = speedBps > 0
    val active = flowing && !reduce
    // زمن عبور الجسيم: 2.6 ث (بطيء) حتى 0.7 ث (سريع جداً) بمقياس لوغاريتمي للسرعة الحقيقية
    val mbps = speedBps / 1_048_576.0
    val period = (2600 - (kotlin.math.ln(1 + mbps) / kotlin.math.ln(101.0) * 1900)).toInt().coerceIn(700, 2600)
    // الحركة موجودة فقط أثناء تدفق بيانات فعلي؛ عند التوقف لا إطارات ولا رسم متكرر
    val t = if (active) {
        val anim = rememberInfiniteTransition(label = "scene")
        anim.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "flow").value
    } else 0f
    val lanes = if (flowing) connections.coerceIn(1, 8) else 3
    val primary = MaterialTheme.colorScheme.primary
    val accent = T.extra.chartLine
    val idle = T.extra.hairline
    val dark = isDark()
    Row(modifier.fillMaxWidth().height(88.dp).semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        // في RTL يكون الخادم يميناً والهاتف يساراً: البيانات «تتدفق» باتجاه القراءة
        SceneNode(androidx.compose.material.icons.Icons.Rounded.Dns, primary, dark)
        Canvas(Modifier.weight(1f).fillMaxSize()) {
            val y0 = size.height / 2
            val spread = minOf(size.height * 0.42f, 34.dp.toPx())
            fun lanePoint(lane: Int, p: Float): Offset {
                // منحنى تربيعي ينحني بعيداً عن المنتصف حسب ترتيب المسار
                val k = if (lanes == 1) 0f else (lane - (lanes - 1) / 2f) / ((lanes - 1) / 2f)
                val cy = y0 + k * spread
                val x = size.width * p
                val y = (1 - p) * (1 - p) * y0 + 2 * (1 - p) * p * cy + p * p * y0
                return Offset(x, y)
            }
            for (lane in 0 until lanes) {
                val path = Path()
                for (i in 0..24) { val o = lanePoint(lane, i / 24f); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, if (flowing) Brush.horizontalGradient(listOf(accent.copy(alpha = 0.35f), primary.copy(alpha = 0.35f))) else Brush.horizontalGradient(listOf(idle, idle)),
                    style = Stroke(if (flowing) 2.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
            if (!active) {
                // تقليل الحركة: تدفق ظاهر بنقاط ثابتة بلا حركة؛ والتوقف: لا شيء
                if (flowing) for (lane in 0 until lanes) drawCircle(accent, 3.dp.toPx(), lanePoint(lane, 0.5f))
                return@Canvas
            }
            for (lane in 0 until lanes) for (d in 0 until 3) {
                val p = (t + d / 3f + lane * 0.137f) % 1f
                // من الخادم إلى الهاتف: في LTR من اليسار، وفي RTL من اليمين (Canvas لا يُعكس تلقائياً)
                val o = lanePoint(lane, if (layoutDirection == LayoutDirection.Rtl) 1f - p else p)
                val fade = 1f - kotlin.math.abs(p - 0.5f) * 1.6f
                drawCircle(accent.copy(alpha = 0.18f * fade), 7.dp.toPx(), o)
                drawCircle(accent.copy(alpha = 0.5f + 0.5f * fade), 3.dp.toPx(), o)
            }
        }
        SceneNode(androidx.compose.material.icons.Icons.Rounded.PhoneAndroid, accent, dark)
    }
}

@Composable
private fun SceneNode(icon: ImageVector, c: Color, dark: Boolean) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(c.copy(alpha = if (dark) 0.18f else 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c, modifier = Modifier.size(24.dp))
    }
}

/** قراءة بنظرة واحدة (بروح «طاقتي»): أيقونة بلون القسم، الرقم كبيراً، والتسمية خفيفة. */
@Composable
fun Kpi(icon: ImageVector, tint: Tint, label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    val c = if (isDark()) tint.dark else tint.light
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = if (isDark()) 0.12f else 0.07f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = if (emphasize) c else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
    }
}

/** شارة حالة: رمز + نص + لون (لا يعتمد اللون وحده). */
@Composable
fun StatusChip(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = if (isDark()) 0.16f else 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, tint: Color? = null) {
    // بلون الحالة/الفئة: نقطة ملونة دائماً، وخلفية باستيل بلونها عند الاختيار
    val bg = when { selected && tint != null -> tint.copy(alpha = if (isDark()) 0.22f else 0.14f); selected -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant }
    val fg = when { selected && tint != null -> tint; selected -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tint != null) Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        if (count != null && count > 0) Text(com.tadaffuq.app.core.Fmt.iso(count.toString()), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
    }
}

/** لون قسم دلالي: خلفية باستيل خفيفة وأيقونة مشبعة (التحميل أزرق، الشبكة نعناعي، التخزين كهرماني…). */
enum class Tint(val light: Color, val dark: Color) {
    BLUE(Color(0xFF1E5FB8), Color(0xFF6CB2FF)),
    MINT(Color(0xFF0B7F63), Color(0xFF34D6AE)),
    AMBER(Color(0xFF9A5B00), Color(0xFFFFC266)),
    CORAL(Color(0xFFB3401E), Color(0xFFFF9B7A)),
    VIOLET(Color(0xFF5B3FC4), Color(0xFFB39DFF)),
    SLATE(Color(0xFF4A5563), Color(0xFFAAB4C0)),
}

@Composable
fun isDark() = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }

/** أيقونة في مربع باستيل؛ ثابتة الحجم 40dp ومنطقة اللمس للصف كله. */
@Composable
fun TintIcon(icon: ImageVector, tint: Tint) {
    val c = if (isDark()) tint.dark else tint.light
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = if (isDark()) 0.16f else 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c, modifier = Modifier.size(22.dp))
    }
}

/** مدخل في مركز الإعدادات: أيقونة ملونة، عنوان، قيمة مختصرة، وسهم. */
@Composable
fun HubRow(icon: ImageVector, tint: Tint, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = title, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintIcon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = T.extra.secondaryText)
    }
}

/** اختيار من قيم قليلة بنقرة واحدة (بدل منزلق دقيق): عنوان + قيمة + سطر شرح اختياري. */
@Composable
fun <V> ChoiceRow(title: String, description: String?, options: List<Pair<V, String>>, selected: V, onSelect: (V) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (v, label) ->
                val on = v == selected
                val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(bg)
                        .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClickLabel = label) { onSelect(v) }
                        .semantics { this.selected = on }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg) }
            }
        }
    }
}

/** «كيف يعمل؟»: الشرح الطويل مطوي خلف سطر واحد، لا بجانب كل مفتاح. */
@Composable
fun HowItWorks(text: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(androidx.compose.ui.res.stringResource(com.tadaffuq.app.R.string.how_it_works), style = MaterialTheme.typography.labelLarge)
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }
}
