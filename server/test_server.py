import copy
import io
import json
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib import error, request
from zoneinfo import ZoneInfo

from await_server import (DeliveryError, ScheduleStore, Telegram, clip_message,
                          make_handler, occurrence, parse_zone, triggers, validate_snapshot)

UTC = timezone.utc


def event(event_id=1, **changes):
    result = dict(id=event_id, title="房租", note="不要忘记", date="2026-10-02",
                  remindDaysBefore=[0], remindHour=9, remindMinute=0, cycle="NONE", repeatN=1)
    result.update(changes)
    return result


def snapshot(events=None, revision=1, groups=None, zone="Asia/Shanghai"):
    return dict(clientId="test-phone", revision=revision, timezone=zone,
                events=[event()] if events is None else events, mergeGroups=groups or [])


class Sender:
    def __init__(self):
        self.messages, self.failure = [], None

    def send(self, text):
        if self.failure:
            raise self.failure
        self.messages.append(text)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.sender = Sender()
        self.now = datetime(2026, 10, 2, 0, 0, tzinfo=UTC).timestamp()
        self.path = str(Path(self.temp.name) / "schedule.sqlite3")
        self.store = ScheduleStore(self.path, self.sender, lambda: self.now)

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def due(self):
        self.now = datetime(2026, 10, 2, 1, 0, tzinfo=UTC).timestamp()
        self.store.tick()

    def test_server_sends_without_any_phone_request_at_due_time(self):
        self.store.update(snapshot())
        self.due()
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))
        self.assertIn("就是今天", self.sender.messages[0])

    def test_restart_preserves_delivered_and_pending_jobs(self):
        self.store.update(snapshot())
        self.store.db.close()
        self.store = ScheduleStore(self.path, self.sender, lambda: self.now)
        self.due()
        self.store.db.close()
        self.store = ScheduleStore(self.path, self.sender, lambda: self.now)
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))

    def test_transient_failure_retries_and_survives_unchanged_sync(self):
        self.store.update(snapshot())
        self.sender.failure = DeliveryError("网络失败")
        self.due()
        self.assertEqual(1, self.store.status()["pending"])
        self.store.update(snapshot(revision=2))
        self.sender.failure = None
        self.now += 30
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))

    def test_deletion_and_edit_cancel_queued_reminders(self):
        self.store.update(snapshot())
        self.store.update(snapshot([event(date="2026-10-03")], revision=2))
        self.due()
        self.assertEqual([], self.sender.messages)
        self.store.update(snapshot([], revision=3))
        self.now += 86400
        self.store.tick()
        self.assertEqual([], self.sender.messages)

    def test_sync_is_idempotent_and_stale_writers_are_rejected(self):
        body = snapshot(revision=5)
        self.store.update(body)
        self.store.update(body)
        with self.assertRaises(ValueError):
            self.store.update(snapshot(revision=4))
        with self.assertRaises(ValueError):
            self.store.update(snapshot([event(title="已改变")], revision=5))
        other = copy.deepcopy(body)
        other["clientId"] = "other-phone"
        with self.assertRaises(PermissionError):
            self.store.update(other)

    def test_legacy_group_changes_at_same_revision_do_not_change_individual_jobs(self):
        events = [event(), event(2, title="订阅")]
        self.store.update(snapshot(events, groups=[dict(eventIds=[1, 2])]))
        self.store.update(snapshot(events))
        self.due()
        self.assertEqual(2, len(self.sender.messages))

    def test_same_day_events_send_separate_messages_even_with_legacy_groups(self):
        self.store.update(snapshot([event(), event(2, title="订阅")], groups=[dict(eventIds=[1, 2])]))
        self.due()
        self.assertEqual(2, len(self.sender.messages))
        self.assertEqual(1, sum("房租" in text for text in self.sender.messages))
        self.assertEqual(1, sum("订阅" in text for text in self.sender.messages))
        self.assertTrue(all(not ("房租" in text and "订阅" in text) for text in self.sender.messages))

    def test_disabled_reminders_never_send(self):
        self.store.update(snapshot([event(remindDaysBefore=[])]))
        self.due()
        self.assertEqual([], self.sender.messages)

    def test_restart_after_multiple_days_catches_up_only_last_day(self):
        self.store.update(snapshot([event(cycle="DAY")]))
        self.now += 5 * 86400 + 3600
        self.store.tick()
        self.assertEqual(2, len(self.sender.messages))  # Inclusive previous/current 09:00 boundary.
        self.assertGreater(self.store.status()["failed"], 0)

    def test_new_import_does_not_send_past_reminders(self):
        self.now += 86400
        self.store.update(snapshot())
        self.store.tick()
        self.assertEqual([], self.sender.messages)

    def test_timezone_affects_due_time(self):
        self.store.update(snapshot(zone="America/Phoenix"))
        self.due()
        self.assertEqual([], self.sender.messages)
        self.now = datetime(2026, 10, 2, 16, 0, tzinfo=UTC).timestamp()
        self.store.tick()
        self.assertEqual(1, len(self.sender.messages))


class DateTest(unittest.TestCase):
    def test_month_end_and_leap_day_keep_original_anchor(self):
        self.assertEqual("2026-03-31", occurrence(event(date="2026-01-31", cycle="MONTH"), 2).isoformat())
        self.assertEqual("2028-02-29", occurrence(event(date="2024-02-29", cycle="YEAR"), 4).isoformat())

    def test_android_fixed_timezone_offsets_are_supported(self):
        self.assertEqual(8 * 3600, datetime(2026, 1, 1, tzinfo=parse_zone("GMT+08:00")).utcoffset().total_seconds())
        self.assertEqual(-7 * 3600, datetime(2026, 1, 1, tzinfo=parse_zone("-07:00")).utcoffset().total_seconds())
        validate_snapshot(snapshot(zone="GMT+08:00"))
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot(zone="GMT+18:30"))

    def test_old_daily_repeat_and_overlapping_offsets(self):
        start = datetime(2026, 10, 2, 0, 0, tzinfo=UTC)
        points = triggers(event(date="2000-01-01", cycle="DAY", remindDaysBefore=[0, 1]),
                          ZoneInfo("Asia/Shanghai"), start, start.replace(hour=23))
        self.assertEqual(1, len(points))
        self.assertEqual(datetime(2026, 10, 2, 1, 0, tzinfo=UTC).timestamp(), points[0][0])

    def test_malformed_payload_is_rejected(self):
        for changes in (dict(remindHour=24), dict(remindDaysBefore=[-1]), dict(repeatN=0), dict(date="nonsense")):
            with self.assertRaises(ValueError):
                validate_snapshot(snapshot([event(**changes)]))
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot(zone="Unknown/Timezone"))

    def test_long_emoji_message_is_valid_utf16(self):
        text = clip_message("😺" * 4096)
        self.assertLessEqual(len(text.encode("utf-16-le")), 8192)
        self.assertTrue(text.endswith("…"))


class Response(io.BytesIO):
    def __init__(self, code, body):
        super().__init__(json.dumps(body).encode())
        self.code = code


class TelegramTest(unittest.TestCase):
    def client(self, opener):
        return Telegram("123:synthetic-test-token", "456", opener)

    def test_request_uses_post_and_plain_text(self):
        def opener(req, timeout):
            self.assertEqual("POST", req.get_method())
            self.assertEqual(15, timeout)
            body = json.loads(req.data)
            self.assertEqual("456", body["chat_id"])
            self.assertEqual("<中文> _原样_", body["text"])
            self.assertNotIn("parse_mode", body)
            return Response(200, dict(ok=True))
        self.client(opener).send("<中文> _原样_")

    def test_rate_limit_is_retried_without_leaking_remote_description(self):
        sender = self.client(lambda *_args, **_kw: Response(429, dict(
            ok=False, error_code=429, description="secret-url", parameters=dict(retry_after=120))))
        with self.assertRaises(DeliveryError) as caught:
            sender.send("test")
        self.assertTrue(caught.exception.retryable)
        self.assertEqual(120, caught.exception.retry_after)
        self.assertNotIn("secret-url", str(caught.exception))

    def test_invalid_chat_is_permanent_failure(self):
        sender = self.client(lambda *_args, **_kw: Response(400, dict(ok=False, error_code=400)))
        with self.assertRaises(DeliveryError) as caught:
            sender.send("test")
        self.assertFalse(caught.exception.retryable)


class APITest(unittest.TestCase):
    def setUp(self):
        self.sender = Sender()
        self.store = ScheduleStore(":memory:", self.sender)
        self.key = "synthetic-api-key-for-local-tests-0000"
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.store, self.key))
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.http.server_port}"

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join()
        self.store.db.close()

    def call(self, path, method="GET", body=None, authenticated=True):
        headers = {"Authorization": "Bearer " + self.key} if authenticated else {}
        req = request.Request(self.url + path, method=method,
                              data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            response = request.urlopen(req, timeout=5)
        except error.HTTPError as exc:
            response = exc
        with response:
            return response.code, json.loads(response.read())

    def test_authenticated_sync_status_and_test_push(self):
        self.assertEqual(401, self.call("/v1/status", authenticated=False)[0])
        self.assertEqual(200, self.call("/v1/schedule", "PUT", snapshot())[0])
        self.assertEqual(1, self.call("/v1/status")[1]["eventCount"])
        self.assertEqual(200, self.call("/v1/test", "POST", {})[0])
        self.assertEqual(1, len(self.sender.messages))

    def test_invalid_sync_does_not_replace_existing_events(self):
        self.call("/v1/schedule", "PUT", snapshot())
        self.assertEqual(400, self.call("/v1/schedule", "PUT", snapshot([event(remindHour=99)], revision=2))[0])
        self.assertEqual(1, self.call("/v1/status")[1]["revision"])


if __name__ == "__main__":
    unittest.main()
