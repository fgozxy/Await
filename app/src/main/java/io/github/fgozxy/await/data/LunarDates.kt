package io.github.fgozxy.await.data

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarMonth
import com.nlf.calendar.LunarYear
import com.nlf.calendar.Solar
import java.time.LocalDate

/** 只转换年月日，不经由时间戳或设备时区。闰月用负月份表示。 */
object LunarDates {
    fun fromSolar(date: LocalDate): Lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar

    fun toSolar(year: Int, month: Int, day: Int): LocalDate {
        val solar = Lunar.fromYmd(year, month, day).solar
        return LocalDate.of(solar.year, solar.month, solar.day)
    }

    fun months(year: Int): List<LunarMonth> = LunarYear.fromYear(year).months.filter { it.year == year }

    fun text(date: LocalDate): String = fromSolar(date).let {
        "农历 ${it.year}年${it.monthInChinese}月${it.dayInChinese}"
    }

    /** 每次从原始农历日期推进；小月截到月末，下一大月仍恢复原日。 */
    fun occurrence(anchor: LocalDate, cycle: Cycle, amount: Int): LocalDate {
        val lunar = fromSolar(anchor)
        val month = if (cycle == Cycle.YEAR) {
            val year = Math.addExact(lunar.year, amount)
            require(year in 1899..2999)
            LunarMonth.fromYm(year, lunar.month) ?: LunarMonth.fromYm(year, kotlin.math.abs(lunar.month))
        } else {
            require(amount in 0..15000)
            LunarMonth.fromYm(lunar.year, lunar.month).next(amount)
        }
        require(month.year in 1899..2999)
        return toSolar(month.year, month.month, minOf(lunar.day, month.dayCount))
    }
}
