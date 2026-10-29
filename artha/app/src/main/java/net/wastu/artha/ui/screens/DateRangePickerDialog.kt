package net.wastu.artha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import net.wastu.artha.core.model.AccountingTime

private const val PICKER_MONTHS = 36

/** Full-screen range picker: tap a start day, then an end day. Days after today cannot be picked. */
@Composable
fun DateRangePickerDialog(
    initial: Pair<Long?, Long?>?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (startMillis: Long, endMillis: Long) -> Unit,
) {
    fun day(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).atZone(AccountingTime.zone).toLocalDate() }
    fun millis(day: LocalDate) = day.atStartOfDay(AccountingTime.zone).toInstant().toEpochMilli()

    var start by remember { mutableStateOf(day(initial?.first)) }
    var end by remember { mutableStateOf(day(initial?.second ?: initial?.first)) }

    fun pick(date: LocalDate) {
        val from = start
        when {
            from == null || end != null && from != end -> { start = date; end = null }
            end == null && date.isBefore(from) -> { end = from; start = date }
            else -> end = date
        }
    }
    // A lone first tap counts as a single day.
    val effectiveEnd = end ?: start

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text("Select dates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(enabled = start != null, onClick = { onConfirm(millis(start!!), millis(effectiveEnd!!)) }) { Text("Save") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    DateReadout("Start", start, Modifier.weight(1f))
                    DateReadout("End", if (end == null) null else end, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TransactionPeriod.THIS_MONTH, TransactionPeriod.LAST_MONTH, TransactionPeriod.LAST_30_DAYS).forEach { period ->
                        val range = period.range(today)!!
                        FilterChip(
                            selected = start != null && start == day(range.first) && effectiveEnd == day(range.second),
                            onClick = { start = day(range.first); end = day(range.second) },
                            label = { Text(period.label) },
                        )
                    }
                }
                WeekdayHeader()
                HorizontalDivider()
                val months = remember(today) { List(PICKER_MONTHS) { YearMonth.from(today).minusMonths((PICKER_MONTHS - 1 - it).toLong()) } }
                val target = remember { months.indexOf(start?.let(YearMonth::from)).takeIf { it >= 0 } ?: months.lastIndex }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = target)
                LazyColumn(Modifier.weight(1f), state = listState) {
                    itemsIndexed(months) { _, month ->
                        MonthGrid(month, today, start, effectiveEnd, ::pick)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateReadout(label: String, date: LocalDate?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)) ?: "Pick a day",
            style = MaterialTheme.typography.titleMedium,
            color = if (date == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        weekdays.forEach {
            Text(
                it.getDisplayName(TextStyle.NARROW, Locale.US),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, start: LocalDate?, end: LocalDate?, onPick: (LocalDate) -> Unit) {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val cells = leading + month.lengthOfMonth()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        )
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayOfMonth = week * 7 + column - leading + 1
                    if (dayOfMonth < 1 || dayOfMonth > month.lengthOfMonth()) {
                        Box(Modifier.weight(1f).height(44.dp))
                    } else {
                        DayCell(month.atDay(dayOfMonth), today, start, end, onPick, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, today: LocalDate, start: LocalDate?, end: LocalDate?, onPick: (LocalDate) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val isStart = date == start
    val isEnd = date == end
    val selected = isStart || isEnd
    val ranged = start != null && end != null && start != end
    val inside = ranged && date.isAfter(start) && date.isBefore(end)
    val future = date.isAfter(today)
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        if (ranged && (inside || isStart || isEnd)) {
            Box(
                Modifier
                    .fillMaxWidth(if (inside) 1f else 0.5f)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .align(if (isStart) Alignment.CenterEnd else if (isEnd) Alignment.CenterStart else Alignment.Center)
                    .background(colors.secondaryContainer),
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent)
                .then(if (date == today && !selected) Modifier.border(1.dp, colors.outline, CircleShape) else Modifier)
                .clickable(enabled = !future) { onPick(date) }
                .alpha(if (future) 0.38f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> colors.onPrimary
                    inside -> colors.onSecondaryContainer
                    else -> colors.onSurface
                },
            )
        }
    }
}
