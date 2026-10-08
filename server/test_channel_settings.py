import json
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone

from await_server import ChannelSettings, DeliveryError, ScheduleStore
import test_server as fixtures
from test_server import Sender, snapshot

KEY = 'synthetic-cloud-config-key-for-tests-0000'
TELEGRAM = dict(botToken='123:synthetic-bot-token', chatId='456')
NTFY = dict(url='https://ntfy.example.test', topic='await-tests', token='synthetic-ntfy-token')


def factory(channel, config):
    ChannelSettings.sender(channel, config)  # Validate real protocol fields, without making network requests.
    return Sender()


class ChannelSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / 'settings.sqlite3')
        self.store = ScheduleStore(self.path, {})
        self.settings = ChannelSettings(self.store, KEY, factory)
        self.store.channel_settings = self.settings

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def test_fresh_deployment_allows_configuration_without_any_channel(self):
        self.assertTrue(self.store.status()['channelConfiguration'])
        self.assertEqual([], self.store.status()['availableChannels'])
        self.assertFalse(self.settings.status()['channels']['telegram']['configured'])

    def test_credentials_are_encrypted_in_database_and_never_returned(self):
        for channel, config in (('telegram', TELEGRAM), ('ntfy', NTFY)):
            response = self.settings.save(channel, config)
            serialized = json.dumps(response)
            self.assertNotIn(config.get('botToken', config.get('token')), serialized)
            self.assertTrue(response['channels'][channel]['configured'])
        self.store.db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        data = Path(self.path).read_bytes()
        self.assertNotIn(TELEGRAM['botToken'].encode(), data)
        self.assertNotIn(NTFY['token'].encode(), data)
        self.assertEqual(0, self.store.status()['pending'])
        self.assertEqual(TELEGRAM['chatId'], self.settings.status()['channels']['telegram']['chatId'])

    def test_saved_channels_survive_restart_and_can_send_after_sync(self):
        self.settings.save('telegram', TELEGRAM)
        self.settings.save('ntfy', NTFY)
        self.store.db.close()
        self.store = ScheduleStore(self.path, {})
        self.settings = ChannelSettings(self.store, KEY, factory)
        self.store.channel_settings = self.settings
        self.assertEqual(['ntfy', 'telegram'], self.store.status()['availableChannels'])
        self.assertEqual(TELEGRAM, self.settings.configs['telegram'])
        self.assertEqual(NTFY, self.settings.configs['ntfy'])

    def test_modified_ciphertext_and_wrong_key_are_rejected(self):
        self.settings.save('telegram', TELEGRAM)
        with self.assertRaises(ValueError) as caught:
            ChannelSettings(self.store, KEY + '-different', factory)
        self.assertNotIn(TELEGRAM['botToken'], str(caught.exception))
        with self.store.db:
            blob = self.store.db.execute('SELECT ciphertext FROM channel_settings').fetchone()[0]
            self.store.db.execute('UPDATE channel_settings SET ciphertext=?', (blob[:-1] + bytes([blob[-1] ^ 1]),))
        with self.assertRaises(ValueError):
            ChannelSettings(self.store, KEY, factory)

    def test_blank_tokens_preserve_existing_and_ntfy_clear_is_explicit(self):
        self.settings.save('telegram', TELEGRAM)
        self.settings.save('telegram', dict(botToken='', chatId='789'))
        self.assertEqual(TELEGRAM['botToken'], self.settings.configs['telegram']['botToken'])
        self.settings.save('ntfy', NTFY)
        self.settings.save('ntfy', dict(url=NTFY['url'], topic='new-topic', token=''))
        self.assertEqual(NTFY['token'], self.settings.configs['ntfy']['token'])
        self.settings.save('ntfy', dict(url=NTFY['url'], topic='new-topic', token='', clearToken=True))
        self.assertEqual('', self.settings.configs['ntfy']['token'])

    def test_environment_credentials_are_preserved_during_first_mobile_edit(self):
        settings = ChannelSettings(self.store, KEY, factory, defaults={'telegram': TELEGRAM})
        self.store.senders['telegram'] = Sender()
        self.assertEqual('environment', settings.status()['channels']['telegram']['source'])
        settings.save('telegram', dict(botToken='', chatId='789'))
        self.assertEqual(TELEGRAM['botToken'], settings.configs['telegram']['botToken'])

    def test_invalid_configuration_does_not_replace_working_sender(self):
        self.settings.save('telegram', TELEGRAM)
        sender = self.store.senders['telegram']
        with self.assertRaises(ValueError):
            self.settings.save('telegram', dict(botToken='bad-token', chatId='456'))
        self.assertIs(sender, self.store.senders['telegram'])
        self.assertEqual(TELEGRAM, self.settings.configs['telegram'])

    def test_testing_draft_does_not_persist_or_change_existing_configuration(self):
        self.settings.save('ntfy', NTFY)
        sender = self.store.senders['ntfy']
        self.settings.test('ntfy', dict(NTFY, topic='draft-topic'))
        self.assertEqual(NTFY, self.settings.configs['ntfy'])
        self.assertIs(sender, self.store.senders['ntfy'])
        self.assertEqual([], sender.messages)

    def test_each_save_uses_new_nonce_and_never_stores_plaintext(self):
        self.settings.save('ntfy', NTFY)
        first = self.store.db.execute('SELECT ciphertext FROM channel_settings').fetchone()[0]
        self.settings.save('ntfy', NTFY)
        second = self.store.db.execute('SELECT ciphertext FROM channel_settings').fetchone()[0]
        self.assertNotEqual(first[:12], second[:12])
        self.assertNotIn(NTFY['token'].encode(), second)


class ChannelAPITest(fixtures.APITest):
    def setUp(self):
        super().setUp()
        self.settings = ChannelSettings(self.store, self.key, factory)
        self.store.channel_settings = self.settings

    def test_channel_configuration_requires_authentication(self):
        self.assertEqual(401, self.call('/v1/channels', authenticated=False)[0])
        self.assertEqual(401, self.call('/v1/channels/telegram', 'PUT', TELEGRAM, authenticated=False)[0])
        self.assertEqual(401, self.call('/v1/channels/telegram/test', 'POST', TELEGRAM, authenticated=False)[0])
        self.assertEqual({}, self.settings.configs)

    def test_mobile_configures_and_tests_each_channel_without_restart(self):
        for channel, config in (('telegram', TELEGRAM), ('ntfy', NTFY)):
            self.assertEqual(200, self.call('/v1/channels/' + channel, 'PUT', config)[0])
            self.assertEqual(200, self.call('/v1/test', 'POST', dict(notificationChannels=[channel]))[0])
            self.assertEqual(1, len(self.store.senders[channel].messages))
        self.assertEqual(['ntfy', 'telegram'], self.call('/v1/status')[1]['availableChannels'])
        self.assertNotIn(NTFY['token'], json.dumps(self.call('/v1/channels')[1]))

    def test_bad_channel_fields_and_unknown_routes_rejected_without_token_echo(self):
        code, body = self.call('/v1/channels/telegram', 'PUT', dict(botToken='synthetic-private-value', chatId='bad-id'))
        self.assertEqual(400, code)
        self.assertNotIn('synthetic-private-value', json.dumps(body))
        self.assertEqual(404, self.call('/v1/channels/unknown', 'PUT', {})[0])
        self.assertEqual(400, self.call('/v1/channels/ntfy', 'PUT', [NTFY])[0])

    def test_failed_draft_push_is_reported_and_does_not_save(self):
        def failing_factory(channel, config):
            sender = factory(channel, config)
            sender.failure = DeliveryError('ntfy 拒绝发布', retryable=False)
            return sender
        self.settings.factory = failing_factory
        code, body = self.call('/v1/channels/ntfy/test', 'POST', NTFY)
        self.assertEqual(502, code)
        self.assertEqual({}, self.settings.configs)
        self.assertNotIn(NTFY['token'], json.dumps(body))


class ChannelDeliveryTest(unittest.TestCase):
    def test_mobile_configuration_and_sync_send_to_both_channels_at_due_time(self):
        now = datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc).timestamp()
        store = ScheduleStore(':memory:', {}, lambda: now)
        settings = ChannelSettings(store, KEY, factory)
        store.channel_settings = settings
        body = snapshot()
        body['notificationChannels'] = []
        store.update(body)  # Deploy and connect first, with no channels configured.
        self.assertEqual(0, store.status()['pending'])
        settings.save('telegram', TELEGRAM)
        settings.save('ntfy', NTFY)
        self.assertEqual(0, store.status()['pending'])  # Saving parameters never enables notifications.
        body['revision'] = 2
        body['notificationChannels'] = ['telegram', 'ntfy']
        store.update(body)
        now += 3600
        store.tick()
        self.assertEqual(1, len(store.senders['telegram'].messages))
        self.assertEqual(1, len(store.senders['ntfy'].messages))
        store.tick()
        self.assertEqual(1, len(store.senders['telegram'].messages))
        store.db.close()

    def test_corrected_credentials_retry_only_recent_failed_channel(self):
        now = datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc).timestamp()
        store = ScheduleStore(':memory:', {}, lambda: now)
        settings = ChannelSettings(store, KEY, factory)
        settings.save('telegram', TELEGRAM)
        settings.save('ntfy', NTFY)
        body = snapshot()
        body['notificationChannels'] = ['telegram', 'ntfy']
        store.update(body)
        store.senders['ntfy'].failure = DeliveryError('ntfy 权限错误', retryable=False)
        now += 3600
        store.tick()
        telegram = store.senders['telegram']
        self.assertEqual(1, len(telegram.messages))
        self.assertEqual(1, store.status()['failed'])
        settings.save('ntfy', dict(NTFY, token='synthetic-corrected-token'))
        store.tick()
        self.assertEqual(1, len(store.senders['ntfy'].messages))
        self.assertEqual(1, len(telegram.messages))
        self.assertEqual(0, store.status()['failed'])
        store.db.close()


if __name__ == '__main__':
    unittest.main()
