package io.github.fgozxy.await.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nlf.calendar.util.LunarUtil
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.data.LunarDates
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDatePicker(
    date: LocalDate,
    isLunar: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, Boolean) -> Unit
) {
    var lunarMode by remember { mutableStateOf(isLunar) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.toEpochDay() * 86_400_000L,
        yearRange = 1900..2999
    )
    val selected = state.selectedDateMillis?.let { LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = selected != null, onClick = {
                selected?.let { onConfirm(it, lunarMode) }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        Column {
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !lunarMode, onClick = { lunarMode = false }, label = { Text("公历") })
                FilterChip(selected = lunarMode, onClick = { lunarMode = true }, label = { Text("农历") })
            }
            if (lunarMode) {
                val current = selected ?: date
                LunarDateFields(current) { value -> state.selectedDateMillis = value.toEpochDay() * 86_400_000L }
            } else {
                DatePicker(state = state, title = null)
            }
        }
    }
}

@Composable
private fun LunarDateFields(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val lunar = remember(date) { LunarDates.fromSolar(date) }
    val months = remember(lunar.year) { LunarDates.months(lunar.year) }
    val dayCount = months.first { it.month == lunar.month }.dayCount
    fun select(year: Int = lunar.year, month: Int = lunar.month, day: Int = lunar.day) {
        val available = LunarDates.months(year)
        val target = available.firstOrNull { it.month == month }
            ?: available.first { it.month == kotlin.math.abs(month) }
        onChange(LunarDates.toSolar(year, target.month, minOf(day, target.dayCount))
            .coerceIn(LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)))
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("选择农历日期", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("年份", "${lunar.year}年", (1899..2999).toList(), { "${it}年" }, Modifier.weight(1.2f)) {
                select(year = it)
            }
            DateField("月份", "${lunar.monthInChinese}月", months.map { it.month }, {
                "${if (it < 0) "闰" else ""}${LunarUtil.MONTH[kotlin.math.abs(it)]}月"
            }, Modifier.weight(1f)) { select(month = it) }
            DateField("日期", lunar.dayInChinese, (1..dayCount).toList(), {
                LunarUtil.DAY[it]
            }, Modifier.weight(1f)) { select(day = it) }
        }
        Text(LunarDates.text(date), style = MaterialTheme.typography.titleMedium)
        Text("对应公历 $date", style = MaterialTheme.typography.bodyMedium)
        Text("闰月会列在对应普通月之后；切换年份或月份时，超出的日期自动取该月最后一天。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DateField(
    label: String, value: String, options: List<Int>, text: (Int) -> String,
    modifier: Modifier, onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(value)
            }
            if (expanded) {
                val scroll = rememberLazyListState(
                    initialFirstVisibleItemIndex = options.indexOfFirst { text(it) == value }.coerceAtLeast(0)
                )
                AlertDialog(
                    onDismissRequest = { expanded = false },
                    title = { Text("选择$label") },
                    text = {
                        LazyColumn(state = scroll, modifier = Modifier.heightIn(max = 320.dp)) {
                            items(options) { option ->
                                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                    onSelect(option)
                                    expanded = false
                                }) { Text(text(option)) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } }
                )
            }
        }
    }
}
