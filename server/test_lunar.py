"""Lunar conversion, anchored repeats, delivery and encrypted-backup compatibility."""
import unittest
import tempfile
from pathlib import Path
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo
from lunar_python import Lunar
from await_server import ScheduleStore, first_index, occurrence, triggers, validate_snapshot
from await_backup import validate_bundle
from test_server import event, snapshot, Sender
from test_backups import bundle


def solar(year, month, day):
    return date.fromisoformat(Lunar.fromYmd(year, month, day).getSolar().toYmd())


class LunarTest(unittest.TestCase):
    def lunar_event(self, year, month, day, cycle='YEAR', repeatN=1):
        return event(date=solar(year, month, day).isoformat(), calendarType='LUNAR', cycle=cycle, repeatN=repeatN)

    def test_known_dates_yearly_interval_and_threshold(self):
        self.assertEqual(date(2026, 2, 17), solar(2026, 1, 1))
        self.assertEqual(date(2026, 9, 25), solar(2026, 8, 15))
        annual = self.lunar_event(2026, 1, 1)
        self.assertEqual(date(2027, 2, 6), occurrence(annual, 1))
        self.assertEqual(1, first_index(annual, date(2027, 2, 6)))
        self.assertEqual(2, first_index(annual, date(2027, 2, 7)))
        self.assertEqual(solar(2028, 1, 1), occurrence(dict(annual, repeatN=2), 1))

    def test_monthly_includes_leap_month_and_restores_day_thirty(self):
        monthly = self.lunar_event(2023, 2, 30, 'MONTH')
        self.assertEqual(date(2023, 3, 22), solar(2023, -2, 1))
        self.assertEqual(solar(2023, -2, 29), occurrence(monthly, 1))
        self.assertEqual(solar(2023, 3, 29), occurrence(monthly, 2))
        self.assertEqual(solar(2023, 5, 30), occurrence(monthly, 4))
        self.assertEqual(1, first_index(monthly, solar(2023, -2, 1)))
        self.assertEqual(solar(2023, 3, 29), occurrence(dict(monthly, repeatN=2), 1))

    def test_leap_birthday_fallback_preserves_anchor(self):
        annual = self.lunar_event(2023, -2, 1)
        self.assertEqual(solar(2024, 2, 1), occurrence(annual, 1))
        self.assertEqual(solar(2042, -2, 1), occurrence(annual, 19))
        self.assertEqual(19, first_index(annual, solar(2042, 2, 2)))

    def test_triggers_respect_offsets_and_device_timezone(self):
        annual = dict(self.lunar_event(2026, 1, 1), remindDaysBefore=[0, 7])
        for zone_name in ('Asia/Shanghai', 'America/Phoenix'):
            zone = ZoneInfo(zone_name)
            result = triggers(annual, zone, datetime(2027, 1, 1, tzinfo=timezone.utc),
                              datetime(2027, 2, 7, tzinfo=timezone.utc))
            self.assertEqual([date(2027, 2, 6)] * 2, [target for _, target in result])
            self.assertEqual([datetime(2027, 1, 30, 9, tzinfo=zone), datetime(2027, 2, 6, 9, tzinfo=zone)],
                             [datetime.fromtimestamp(stamp, zone) for stamp, _ in result])

    def test_snapshot_and_backup_preserve_calendar_and_reject_unknown_values(self):
        annual = self.lunar_event(2026, 1, 1)
        self.assertEqual('LUNAR', validate_snapshot(snapshot([annual]))['events'][0]['calendarType'])
        self.assertNotIn('calendarType', validate_snapshot(snapshot())['events'][0])
        data = bundle()
        data['events'][0]['calendarType'] = 'LUNAR'
        self.assertEqual(data, validate_bundle(data))
        data['events'][0]['calendarType'] = 'UNKNOWN'
        with self.assertRaises(ValueError):
            validate_bundle(data)
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot([dict(annual, calendarType='UNKNOWN')]))

    def test_lunar_schedule_survives_restart_and_delivers_once_per_channel(self):
        senders = {'telegram': Sender(), 'ntfy': Sender()}
        now = datetime(2027, 2, 6, 0, tzinfo=timezone.utc).timestamp()
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'lunar.sqlite3')
            store = ScheduleStore(path, senders, lambda: now)
            data = snapshot([self.lunar_event(2026, 1, 1)])
            data['notificationChannels'] = list(senders)
            store.update(data)
            self.assertEqual(1, store.status()['lunarCalendarProtocol'])
            store.db.close()
            now += 3600
            store = ScheduleStore(path, senders, lambda: now)
            store.tick()
            store.tick()
            for sender in senders.values():
                self.assertEqual(1, len(sender.messages))
                self.assertIn('2027-02-06', sender.messages[0])
            store.db.close()

    def test_daily_weekly_and_nonrepeat_unchanged(self):
        anchor = solar(2023, 2, 30)
        self.assertEqual(anchor, occurrence(self.lunar_event(2023, 2, 30, 'NONE'), 1))
        from datetime import timedelta
        self.assertEqual(anchor + timedelta(days=7), occurrence(self.lunar_event(2023, 2, 30, 'WEEK'), 1))
        self.assertEqual(anchor + timedelta(days=3), occurrence(self.lunar_event(2023, 2, 30, 'DAY', 3), 1))


if __name__ == '__main__':
    unittest.main()
