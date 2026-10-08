import copy
import json
import tempfile
import unittest
from pathlib import Path

from await_backup import BackupStore, backup_enabled
from await_server import ScheduleStore
import test_server as fixtures

KEY = 'synthetic-backup-key-at-least-32-characters'


def bundle():
    return dict(app='Await', formatVersion=1, exportedAt='2026-10-07 09:00:00', appVersion='2.3.0',
                eventCount=1, events=[dict(id=123, title='备份完整日程', dateEpochDay=20700,
                note='保留备注', pinned=True, colorIndex=3, groupName='生日',
                remindDaysBefore=[0, 1, 7], remindHour=8, remindMinute=30, repeatSpec='YEAR:1',
                repeatEveryDays=0, repeatYearly=False)], groups=['生日', '空分组'], mergeGroups=[])


def upload(data=None, client='test-phone', keep=10):
    return dict(clientId=client, keepCount=keep, backup=bundle() if data is None else data)


class BackupStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / 'backups.sqlite3')
        self.store = ScheduleStore(self.path, {}, lambda: 1791388800)
        self.store.backups = BackupStore(self.store, KEY, enabled=True)
        self.backups = self.store.backups

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def test_roundtrip_preserves_ui_fields_empty_groups_and_merge_groups(self):
        data = bundle()
        data['events'].append(dict(data['events'][0], id=456))
        data['eventCount'] = 2
        data['mergeGroups'] = [dict(id=7, eventIds=[123, 456])]
        result = self.backups.save(upload(data))
        self.assertEqual(data, self.backups.fetch(result['id']))
        self.assertEqual(data, self.backups.fetch('latest'))
        self.assertEqual(2, self.backups.list()['backups'][0]['eventCount'])
        self.assertEqual([], self.store.db.execute('SELECT * FROM jobs').fetchall())
        self.assertIsNone(self.store.snapshot())

    def test_backup_contents_are_encrypted_and_survive_restart(self):
        saved = self.backups.save(upload())
        self.store.db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        self.assertNotIn('备份完整日程'.encode(), Path(self.path).read_bytes())
        self.assertNotIn('保留备注'.encode(), Path(self.path).read_bytes())
        self.assertNotIn(KEY.encode(), Path(self.path).read_bytes())
        self.store.db.close()
        self.store = ScheduleStore(self.path, {})
        self.store.backups = BackupStore(self.store, KEY, enabled=True)
        self.assertEqual(bundle(), self.store.backups.fetch(saved['id']))
        self.assertEqual('test-phone', self.store.status()['clientId'])

    def test_incorrect_key_does_not_read_or_overwrite_old_backups(self):
        self.backups.save(upload())
        with self.assertRaisesRegex(ValueError, 'original AWAIT_API_KEY'):
            BackupStore(self.store, KEY + '-wrong', enabled=True)
        self.assertEqual(bundle(), self.backups.fetch('latest'))
        self.assertEqual(1, len(self.backups.list()['backups']))

    def test_modified_ciphertext_is_rejected(self):
        result = self.backups.save(upload())
        blob = self.store.db.execute('SELECT ciphertext FROM cloud_backups').fetchone()[0]
        with self.store.db:
            self.store.db.execute('UPDATE cloud_backups SET ciphertext=?', (blob[:-1] + bytes([blob[-1] ^ 1]),))
        with self.assertRaises(ValueError):
            self.backups.fetch(result['id'])

    def test_same_second_uploads_are_unique_and_retention_keeps_newest(self):
        results = [self.backups.save(upload(keep=5)) for _ in range(8)]
        self.assertEqual(8, len({r['name'] for r in results}))
        self.assertEqual([r['id'] for r in reversed(results[-5:])], [r['id'] for r in self.backups.list()['backups']])
        with self.assertRaises(FileNotFoundError):
            self.backups.fetch(results[0]['id'])

    def test_disabled_restarted_service_preserves_backups_for_reenable(self):
        self.backups.save(upload())
        self.store.backups = BackupStore(self.store, KEY, enabled=False)
        self.assertFalse(self.store.status()['backupEnabled'])
        self.store.backups = BackupStore(self.store, KEY, enabled=True)
        self.assertTrue(self.store.status()['backupEnabled'])
        self.assertEqual(bundle(), self.store.backups.fetch('latest'))

    def test_invalid_bundles_and_credentials_do_not_prune_working_history(self):
        saved = self.backups.save(upload())
        variants = [dict(bundle(), apiKey='synthetic-do-not-store'), dict(bundle(), eventCount=99),
                    dict(bundle(), formatVersion=2), dict(bundle(), groups=[{'token': 'synthetic'}])]
        for key, value in [('botToken', 'synthetic'), ('remindDaysBefore', [{'token': 'synthetic'}]),
                           ('pinned', {'token': 'synthetic'}), ('id', True), ('dateEpochDay', -999999)]:
            data = bundle()
            data['events'][0][key] = value
            variants.append(data)
        for data in variants:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    self.backups.save(upload(data, keep=1))
        self.assertEqual([saved], self.backups.list()['backups'])

    def test_invalid_retention_and_clients_do_not_change_binding(self):
        for count in [0, 101, True, '10']:
            with self.assertRaises(ValueError):
                self.backups.save(upload(keep=count))
        with self.assertRaises(ValueError):
            self.backups.save(upload(client='../other'))
        self.assertEqual('', self.backups.owner())

    def test_empty_calendar_is_a_valid_backup(self):
        data = dict(bundle(), events=[], eventCount=0, groups=['空分组'])
        self.backups.save(upload(data))
        self.assertEqual(data, self.backups.fetch('latest'))

    def test_missing_and_traversal_ids_do_not_resolve(self):
        for identity in ['latest', '../channel_settings', 'a' * 32, 'latest/../latest']:
            with self.assertRaises(FileNotFoundError):
                self.backups.fetch(identity)

    def test_backup_first_binds_schedule_and_rejects_another_writer(self):
        self.backups.save(upload())
        snap = fixtures.snapshot()
        snap['notificationChannels'] = []
        foreign = dict(snap, clientId='other-phone')
        with self.assertRaises(PermissionError):
            self.store.update(foreign)
        with self.assertRaises(PermissionError):
            self.backups.save(upload(client='other-phone'))
        self.store.update(snap)
        self.assertEqual('test-phone', self.store.status()['clientId'])

    def test_schedule_first_binds_backups_without_changing_notification_jobs(self):
        self.store.senders['telegram'] = fixtures.Sender()
        self.store.update(fixtures.snapshot())
        before = [tuple(r) for r in self.store.db.execute('SELECT * FROM jobs')]
        with self.assertRaises(PermissionError):
            self.backups.save(upload(client='other-phone'))
        self.backups.save(upload())
        self.assertEqual(before, [tuple(r) for r in self.store.db.execute('SELECT * FROM jobs')])

    def test_deployment_flag_defaults_are_explicit_and_invalid_values_rejected(self):
        for value in ['false', '0', ' FALSE ']:
            self.assertFalse(backup_enabled(value))
        for value in ['true', '1', ' TRUE ']:
            self.assertTrue(backup_enabled(value))
        for value in ['', 'yes', 'typo']:
            with self.assertRaises(ValueError):
                backup_enabled(value)


class BackupAPITest(fixtures.APITest):
    def setUp(self):
        super().setUp()
        self.store.backups = BackupStore(self.store, self.key, enabled=True)

    def test_backup_endpoints_require_authentication(self):
        for path, method, body in [('/v1/backups', 'GET', None), ('/v1/backups/latest', 'GET', None),
                                   ('/v1/backups', 'POST', upload())]:
            self.assertEqual(401, self.call(path, method, body, authenticated=False)[0])
        self.assertEqual([], self.call('/v1/backups')[1]['backups'])

    def test_upload_list_latest_and_historical_download(self):
        code, saved = self.call('/v1/backups', 'POST', upload())
        self.assertEqual(201, code)
        self.assertEqual([saved], self.call('/v1/backups')[1]['backups'])
        self.assertEqual(bundle(), self.call('/v1/backups/latest')[1])
        self.assertEqual(bundle(), self.call('/v1/backups/' + saved['id'])[1])
        status = self.call('/v1/status')[1]
        self.assertTrue(status['backupEnabled'])
        self.assertEqual(1, status['backupProtocol'])

    def test_disabled_feature_neither_reads_nor_accepts_uploads(self):
        self.call('/v1/backups', 'POST', upload())
        self.store.backups.enabled = False
        self.assertFalse(self.call('/v1/status')[1]['backupEnabled'])
        self.assertEqual(0, self.call('/v1/status')[1]['backupProtocol'])
        self.assertEqual(404, self.call('/v1/backups')[0])
        self.assertEqual(404, self.call('/v1/backups/latest')[0])
        self.assertEqual(404, self.call('/v1/backups', 'POST', upload())[0])
        self.store.backups.enabled = True
        self.assertEqual(bundle(), self.call('/v1/backups/latest')[1])

    def test_different_phone_can_read_for_recovery_but_cannot_overwrite(self):
        self.call('/v1/backups', 'POST', upload())
        self.assertEqual(409, self.call('/v1/backups', 'POST', upload(client='other-phone'))[0])
        self.assertEqual(200, self.call('/v1/backups/latest')[0])
        self.assertEqual(1, len(self.call('/v1/backups')[1]['backups']))

    def test_invalid_input_never_echoes_private_contents(self):
        code, body = self.call('/v1/backups', 'POST', dict(upload(), token='synthetic-private-value'))
        self.assertEqual(400, code)
        self.assertNotIn('synthetic-private-value', json.dumps(body))
        self.assertEqual(400, self.call('/v1/backups', 'POST', [upload()])[0])
        self.assertEqual(404, self.call('/v1/backups/unknown')[0])
        self.assertEqual(404, self.call('/v1/backups/latest')[0])

    def test_corrupt_backup_download_fails_without_revealing_data(self):
        self.call('/v1/backups', 'POST', upload())
        with self.store.lock, self.store.db:
            self.store.db.execute("UPDATE cloud_backups SET ciphertext=x'0001'")
        code, body = self.call('/v1/backups/latest')
        self.assertEqual(500, code)
        self.assertNotIn('备份完整日程', json.dumps(body))


if __name__ == '__main__':
    unittest.main()
