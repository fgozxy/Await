"""The phone resolves defaults before upload; both cloud channels use those effective times."""
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from await_server import ScheduleStore
from test_server import Sender, event, snapshot


class EffectiveTimeTest(unittest.TestCase):
    def test_changing_default_cancels_old_pending_time_and_keeps_precise_time_for_both_channels(self):
        with tempfile.TemporaryDirectory() as directory:
            now = [datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc).timestamp()]
            senders = {'telegram': Sender(), 'ntfy': Sender()}
            store = ScheduleStore(str(Path(directory) / 'schedule.sqlite3'), senders, lambda: now[0])
            try:
                first = snapshot([event(title='默认', remindHour=9), event(2, title='精准', remindHour=14, remindMinute=25)])
                first['notificationChannels'] = list(senders)
                store.update(first)
                changed = dict(first, revision=2, events=[event(title='默认', remindHour=11, remindMinute=45), first['events'][1]])
                store.update(changed)
                # Old default (09:00 Shanghai) is cancelled after sync.
                now[0] += 3600
                store.tick()
                for sender in senders.values():
                    self.assertEqual([], sender.messages)
                now[0] = datetime(2026, 10, 2, 3, 45, tzinfo=timezone.utc).timestamp()
                store.tick()
                for sender in senders.values():
                    self.assertEqual(1, len(sender.messages))
                    self.assertIn('默认', sender.messages[0])
                now[0] = datetime(2026, 10, 2, 6, 25, tzinfo=timezone.utc).timestamp()
                store.tick()
                store.tick()
                for sender in senders.values():
                    self.assertEqual(2, len(sender.messages))
                    self.assertIn('精准', sender.messages[1])
            finally:
                store.db.close()


if __name__ == '__main__':
    unittest.main()
