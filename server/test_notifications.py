import json
import unittest
from datetime import datetime, timezone

from await_server import DeliveryError, Ntfy, ScheduleStore, validate_snapshot
import test_server as fixtures
from test_server import Response, Sender, snapshot


class MultiChannelStoreTest(fixtures.StoreTest):
    def setUp(self):
        super().setUp()
        self.ntfy = Sender()
        self.store.senders['ntfy'] = self.ntfy

    def channels(self, channels, revision=1):
        body = snapshot(revision=revision)
        body['notificationChannels'] = channels
        return body

    def test_every_remote_channel_combination(self):
        for channels in ([], ['telegram'], ['ntfy'], ['telegram', 'ntfy']):
            with self.subTest(channels=channels):
                store = ScheduleStore(':memory:', {'telegram': self.sender, 'ntfy': self.ntfy}, lambda: self.now)
                self.sender.messages.clear()
                self.ntfy.messages.clear()
                self.now = datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc).timestamp()
                store.update(self.channels(channels))
                self.now += 3600
                store.tick()
                self.assertEqual(int('telegram' in channels), len(self.sender.messages))
                self.assertEqual(int('ntfy' in channels), len(self.ntfy.messages))
                store.db.close()

    def test_failure_in_one_channel_never_duplicates_successful_channel(self):
        self.store.update(self.channels(['telegram', 'ntfy']))
        self.sender.failure = DeliveryError('Telegram 网络失败')
        self.due()
        self.assertEqual(1, len(self.ntfy.messages))
        self.assertEqual([], self.sender.messages)
        self.store.update(self.channels(['telegram', 'ntfy'], revision=2))
        self.sender.failure = None
        self.now += 30
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))
        self.assertEqual(1, len(self.ntfy.messages))

    def test_disable_channel_cancels_its_queue(self):
        self.store.update(self.channels(['telegram', 'ntfy']))
        self.store.update(self.channels(['ntfy'], revision=2))
        self.due()
        self.assertEqual([], self.sender.messages)
        self.assertEqual(1, len(self.ntfy.messages))

    def test_local_only_cancels_all_cloud_jobs(self):
        self.store.update(self.channels(['telegram', 'ntfy']))
        self.store.update(self.channels([], revision=2))
        self.due()
        self.assertEqual([], self.sender.messages)
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(0, self.store.status()['pending'])

    def test_ntfy_only_server_accepts_ntfy_and_rejects_unconfigured_telegram(self):
        self.store.senders = {'ntfy': self.ntfy}
        self.store.update(self.channels(['ntfy']))
        with self.assertRaises(ValueError):
            self.store.update(self.channels(['telegram'], revision=2))
        self.assertEqual(1, self.store.status()['revision'])
        self.due()
        self.assertEqual(1, len(self.ntfy.messages))

    def test_unknown_channels_are_rejected(self):
        for channels in (['local'], ['unknown'], 'telegram', [None], [True], {}):
            with self.subTest(channels=channels), self.assertRaises(ValueError):
                validate_snapshot(self.channels(channels))

    def test_legacy_database_upgrade_preserves_sent_history(self):
        self.store.update(snapshot())
        self.due()
        old = self.store.snapshot()
        old.pop('notificationChannels')
        self.store.db.execute('UPDATE snapshot SET body=?', (json.dumps(old),))
        self.store.db.execute('ALTER TABLE jobs DROP COLUMN channel')
        self.store.db.commit()
        self.store.db.close()
        self.store = ScheduleStore(self.path, {'telegram': self.sender, 'ntfy': self.ntfy}, lambda: self.now)
        self.store.update(snapshot())  # Identical legacy revision remains idempotent.
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))
        self.assertEqual([], self.ntfy.messages)
        self.assertEqual(['telegram'], self.store.snapshot()['notificationChannels'])

    def test_pending_legacy_database_upgrade_still_delivers(self):
        self.store.update(snapshot())
        old = self.store.snapshot()
        old.pop('notificationChannels')
        self.store.db.execute('UPDATE snapshot SET body=?', (json.dumps(old),))
        self.store.db.execute('ALTER TABLE jobs DROP COLUMN channel')
        self.store.db.commit()
        self.store.db.close()
        self.store = ScheduleStore(self.path, {'telegram': self.sender, 'ntfy': self.ntfy}, lambda: self.now)
        self.due()
        self.assertEqual(1, len(self.sender.messages))
        self.assertEqual([], self.ntfy.messages)


class MultiChannelAPITest(fixtures.APITest):
    def setUp(self):
        super().setUp()
        self.ntfy = Sender()
        self.store.senders['ntfy'] = self.ntfy

    def test_status_advertises_protocol_and_configured_channels(self):
        code, body = self.call('/v1/status')
        self.assertEqual(200, code)
        self.assertEqual(1, body['notificationProtocol'])
        self.assertEqual(['ntfy', 'telegram'], body['availableChannels'])

    def test_test_push_reports_each_channel_and_attempts_both(self):
        self.sender.failure = DeliveryError('Telegram 拒绝', retryable=False)
        code, body = self.call('/v1/test', 'POST', dict(notificationChannels=['telegram', 'ntfy']))
        self.assertEqual(200, code)
        self.assertFalse(body['ok'])
        self.assertEqual('已发送', body['results']['ntfy'])
        self.assertEqual('Telegram 拒绝', body['results']['telegram'])
        self.assertEqual(1, len(self.ntfy.messages))

    def test_invalid_channel_test_has_no_side_effect(self):
        self.assertEqual(400, self.call('/v1/test', 'POST', dict(notificationChannels=['unknown']))[0])
        self.assertEqual([], self.sender.messages)
        self.assertEqual([], self.ntfy.messages)


class NtfyTest(unittest.TestCase):
    def client(self, opener, **changes):
        args = dict(url='https://ntfy.example.test', topic='await-tests', token='', opener=opener)
        args.update(changes)
        return Ntfy(**args)

    def test_unicode_json_post_and_optional_bearer_auth(self):
        def opener(req, timeout):
            self.assertEqual('POST', req.get_method())
            self.assertEqual('https://ntfy.example.test', req.full_url)
            self.assertEqual(15, timeout)
            self.assertEqual('Bearer synthetic-ntfy-token', req.get_header('Authorization'))
            body = json.loads(req.data)
            self.assertEqual('await-tests', body['topic'])
            self.assertEqual('中文 😺', body['message'])
            return Response(200, dict(id='test-message', event='message'))
        self.client(opener, token='synthetic-ntfy-token').send('中文 😺')

    def test_retryable_and_permanent_http_failures(self):
        for code, retryable in ((429, True), (503, True), (401, False), (403, False), (302, False)):
            with self.subTest(code=code):
                def opener(*_args, **_kw):
                    response = Response(code, dict(error='synthetic-sensitive-remote-text'))
                    response.headers = {'Retry-After': '120'}
                    return response
                with self.assertRaises(DeliveryError) as caught:
                    self.client(opener).send('test')
                self.assertEqual(retryable, caught.exception.retryable)
                self.assertEqual(120, caught.exception.retry_after)
                self.assertNotIn('synthetic-sensitive-remote-text', str(caught.exception))

    def test_long_unicode_message_is_clipped_to_ntfy_byte_limit(self):
        def opener(req, timeout):
            text = json.loads(req.data)['message']
            self.assertLessEqual(len(text.encode('utf-8')), 4096)
            self.assertTrue(text.endswith('…'))
            self.assertNotIn('\ufffd', text)
            return Response(200, dict(id='test-message', event='message'))
        self.client(opener).send('中文 😺' * 2000)

    def test_transport_and_invalid_response_errors_do_not_expose_credentials(self):
        def failing(*_args, **_kw):
            raise OSError('synthetic-sensitive-token')
        for opener in (failing, lambda *_args, **_kw: Response(200, {'error': 'synthetic-sensitive-token'})):
            with self.assertRaises(DeliveryError) as caught:
                self.client(opener).send('test')
            self.assertNotIn('synthetic-sensitive-token', str(caught.exception))

    def test_insecure_or_credential_bearing_urls_and_invalid_topics_rejected(self):
        for changes in (dict(url='http://ntfy.example.test'), dict(url='https://secret@ntfy.example.test'),
                        dict(url='https://ntfy.example.test?token=secret'), dict(topic='../other'),
                        dict(topic=''), dict(token='bad\nheader')):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.client(lambda *_args, **_kw: None, **changes)


if __name__ == '__main__':
    unittest.main()
