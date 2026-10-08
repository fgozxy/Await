"""Upgrade tests construct the real legacy snapshot and queue schema; no network push."""
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from await_server import (ChannelSettings, DeliveryError, ScheduleStore, canonical, digest, reminder_id, validate_snapshot)
from test_channel_settings import factory, NTFY, KEY
from test_server import Sender, event, snapshot


class IndividualUpgradeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / 'legacy.sqlite3')
        self.now = int(datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc).timestamp())
        self.due = self.now + 3600
        self.telegram, self.ntfy = Sender(), Sender()
        self.senders = {'telegram': self.telegram, 'ntfy': self.ntfy}
        self.store = ScheduleStore(self.path, self.senders, lambda: self.now)
        self.raw = snapshot([event(), event(2, title='订阅')], groups=[dict(eventIds=[1, 2])])
        self.raw['notificationChannels'] = ['telegram', 'ntfy']
        self.body = validate_snapshot(self.raw)
        self.members = sorted(digest([e, self.body['timezone']]) for e in self.body['events'])

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def legacy(self, states=None, retry_at=0, attempts=2, events=None, channels=None):
        body = dict(self.body, mergeGroups=[[1, 2]])  # Actual old on-disk normalized format.
        if events is not None:
            normalized = validate_snapshot(dict(self.raw, events=events))
            body['events'] = normalized['events']
        if channels is not None:
            body['notificationChannels'] = channels
        with self.store.db:
            self.store.db.execute('INSERT OR REPLACE INTO snapshot VALUES (1,?,?)', (canonical(body), self.now))
            for channel, state in (states or {'telegram': 'pending', 'ntfy': 'pending'}).items():
                identity = reminder_id(self.due, self.members, channel)
                self.store.db.execute('''INSERT INTO jobs
                    (id,due,members,body,state,attempts,retry_at,error,sent_at,channel)
                    VALUES (?,?,?,?,?,?,?,?,?,?)''',
                    (identity, self.due, canonical(self.members), 'OLD MERGED: 房租 / 订阅', state, attempts,
                     retry_at, 'synthetic retry failure' if state != 'sent' else '',
                     self.due if state == 'sent' else 0, channel))

    def restart(self):
        self.store.db.close()
        self.store = ScheduleStore(self.path, self.senders, lambda: self.now)

    def test_pending_legacy_queues_split_per_event_per_channel_without_phone_sync(self):
        self.legacy()
        self.restart()
        jobs = self.store.db.execute('SELECT * FROM jobs').fetchall()
        self.assertEqual(4, len(jobs))
        self.assertTrue(all(len(json.loads(row['members'])) == 1 for row in jobs))
        self.assertTrue(all(row['attempts'] == 2 for row in jobs))
        self.assertTrue(all('OLD MERGED' not in row['body'] for row in jobs))
        self.store.update(self.raw)  # Same revision and old group remains idempotent.
        self.now = self.due
        self.store.tick()
        self.store.tick()
        for sender in self.senders.values():
            self.assertEqual(2, len(sender.messages))
            self.assertEqual(1, sum('房租' in text for text in sender.messages))
            self.assertEqual(1, sum('订阅' in text for text in sender.messages))
            self.assertTrue(all(not ('房租' in text and '订阅' in text) for text in sender.messages))
        self.assertEqual([], self.store.snapshot()['mergeGroups'])

    def test_successful_legacy_merged_delivery_is_not_sent_again_after_upgrade_or_sync(self):
        self.legacy(states={'telegram': 'sent', 'ntfy': 'sent'})
        self.now = self.due + 10
        self.restart()
        self.store.tick()
        self.store.update(dict(self.raw, revision=2, mergeGroups=[]))
        self.restart()
        self.store.tick()
        self.assertEqual([], self.telegram.messages)
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(4, self.store.db.execute("SELECT count(*) FROM jobs WHERE state='sent'").fetchone()[0])

    def test_successful_channel_is_not_repeated_while_other_channel_waits_for_retry(self):
        self.legacy(states={'telegram': 'sent', 'ntfy': 'pending'}, retry_at=self.due + 30)
        self.now = self.due
        self.restart()
        self.store.tick()
        self.assertEqual([], self.ntfy.messages)
        self.now += 30
        self.store.tick()
        self.assertEqual(2, len(self.ntfy.messages))
        self.assertEqual([], self.telegram.messages)
        self.store.tick()
        self.assertEqual(2, len(self.ntfy.messages))

    def test_failed_legacy_channel_keeps_failure_until_credentials_are_corrected(self):
        self.legacy(states={'telegram': 'sent', 'ntfy': 'failed'})
        self.now = self.due + 1
        self.restart()
        self.store.tick()
        self.assertEqual(2, self.store.status()['failed'])
        self.assertEqual([], self.telegram.messages)
        self.assertEqual([], self.ntfy.messages)
        settings = ChannelSettings(self.store, KEY, factory)
        self.store.channel_settings = settings
        settings.save('ntfy', NTFY)
        self.store.tick()
        self.assertEqual(2, len(self.store.senders['ntfy'].messages))
        self.assertTrue(all('OLD MERGED' not in text for text in self.store.senders['ntfy'].messages))
        self.assertEqual([], self.telegram.messages)

    def test_deleted_member_is_cancelled_while_other_member_still_sends(self):
        self.legacy(events=[event()])
        self.restart()
        self.now = self.due
        self.store.tick()
        for sender in self.senders.values():
            self.assertEqual(1, len(sender.messages))
            self.assertIn('房租', sender.messages[0])
            self.assertNotIn('订阅', sender.messages[0])

    def test_edited_member_does_not_keep_old_merged_message(self):
        self.legacy(events=[event(title='新房租'), event(2, title='订阅')])
        self.restart()
        self.now = self.due
        self.store.tick()
        for sender in self.senders.values():
            self.assertEqual(2, len(sender.messages))
            self.assertTrue(any('新房租' in text for text in sender.messages))
            self.assertTrue(all('OLD MERGED' not in text for text in sender.messages))

    def test_disabled_channels_never_recreate_old_pending_merged_jobs(self):
        self.legacy(channels=[])
        self.restart()
        self.now = self.due
        self.store.tick()
        self.assertEqual(0, self.store.status()['pending'])
        self.assertEqual([], self.telegram.messages)
        self.assertEqual([], self.ntfy.messages)

    def test_sent_merge_record_supersedes_an_existing_individual_pending_record(self):
        self.legacy(states={'telegram': 'sent', 'ntfy': 'sent'})
        member = self.members[0]
        with self.store.db:
            self.store.db.execute('INSERT INTO jobs (id,due,members,body,channel) VALUES (?,?,?,?,?)',
                (reminder_id(self.due, [member], 'telegram'), self.due, canonical([member]), 'individual pending', 'telegram'))
        self.now = self.due + 1
        self.restart()
        self.store.tick()
        self.assertEqual([], self.telegram.messages)
        self.assertEqual(4, self.store.db.execute("SELECT count(*) FROM jobs WHERE state='sent'").fetchone()[0])

    def test_pre_multichannel_database_preserves_sent_telegram_merge(self):
        self.legacy(states={'telegram': 'sent'})
        with self.store.db:
            body = dict(self.body, mergeGroups=[[1, 2]])
            body.pop('notificationChannels')
            self.store.db.execute('UPDATE snapshot SET body=?', (canonical(body),))
            self.store.db.execute('ALTER TABLE jobs DROP COLUMN channel')
        self.now = self.due + 1
        self.restart()
        self.store.tick()
        self.assertEqual([], self.telegram.messages)
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(2, self.store.db.execute("SELECT count(*) FROM jobs WHERE state='sent'").fetchone()[0])

    def test_obsolete_group_changes_and_malformed_group_metadata_are_ignored(self):
        for obsolete in [[], [dict(eventIds=[1, 2])], None, {'obsolete': True}, [dict(eventIds=[999])]]:
            self.store.update(dict(self.raw, mergeGroups=obsolete))
        self.now = self.due
        self.store.tick()
        self.assertEqual(2, len(self.telegram.messages))
        self.assertEqual(2, len(self.ntfy.messages))

    def test_recent_overdue_legacy_members_catch_up_once_after_upgrade(self):
        self.legacy()
        self.now = self.due + 3600
        self.restart()
        self.store.tick()
        self.restart()
        self.store.tick()
        self.assertEqual(2, len(self.telegram.messages))
        self.assertEqual(2, len(self.ntfy.messages))

    def test_legacy_members_older_than_24_hours_are_not_pushed(self):
        self.legacy()
        self.now = self.due + 86401
        self.restart()
        self.store.tick()
        self.assertEqual([], self.telegram.messages)
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(4, self.store.status()['failed'])
        self.assertEqual('提醒超过 24 小时未送达', self.store.status()['lastError'])

    def test_failure_of_one_individual_event_does_not_repeat_successful_members(self):
        class SelectiveSender(Sender):
            reject = True

            def send(self, text):
                if self.reject and '房租' in text:
                    raise DeliveryError('Synthetic temporary failure')
                super().send(text)

        sender = SelectiveSender()
        self.senders['telegram'] = sender
        self.legacy()
        self.now = self.due
        self.restart()
        self.store.tick()
        self.assertEqual(1, len(sender.messages))
        self.assertIn('订阅', sender.messages[0])
        self.assertEqual(2, len(self.ntfy.messages))
        sender.reject = False
        self.now += 60
        self.store.tick()
        self.assertEqual(1, len(sender.messages))
        self.now += 60  # attempts=2 increments to 3, retaining the 120-second backoff.
        self.store.tick()
        self.assertEqual(2, len(sender.messages))
        self.assertEqual(1, sum('订阅' in text for text in sender.messages))
        self.assertEqual(2, len(self.ntfy.messages))


if __name__ == '__main__':
    unittest.main()
