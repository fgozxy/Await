"""Single-writer Await schedule API and durable per-channel delivery queues."""
import calendar
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
import time
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import error, request, parse
from zoneinfo import ZoneInfo

UTC = timezone.utc
MAX_BODY = 4 * 1024 * 1024
REMOTE_CHANNELS = {"telegram", "ntfy"}


def validate_channels(value):
    if not isinstance(value, list) or any(not isinstance(c, str) or c not in REMOTE_CHANNELS for c in value):
        raise ValueError("Invalid notification channels")
    return sorted(set(value))


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ValueError("Invalid integer")
    return value


def parse_zone(value):
    # Android may return GMT+08:00 when the user selects a fixed offset manually.
    match = re.fullmatch(r"(?:GMT|UTC)?([+-])(\d{2}):(\d{2})", value)
    if match:
        sign, hours, minutes = match.groups()
        hours, minutes = int(hours), int(minutes)
        if hours > 18 or minutes > 59 or (hours == 18 and minutes):
            raise ValueError("Invalid timezone offset")
        offset = timedelta(hours=hours, minutes=minutes)
        return timezone(offset if sign == "+" else -offset)
    return ZoneInfo(value)


def validate_snapshot(raw):
    if not isinstance(raw, dict):
        raise ValueError("Invalid snapshot")
    client = raw.get("clientId")
    if not isinstance(client, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,64}", client):
        raise ValueError("Invalid client ID")
    revision = integer(raw.get("revision"), 1, 2**63 - 1)
    zone = raw.get("timezone")
    if not isinstance(zone, str):
        raise ValueError("Invalid timezone")
    try:
        parse_zone(zone)
    except (KeyError, ValueError):
        raise ValueError("Unknown timezone") from None
    events = raw.get("events")
    if not isinstance(events, list) or len(events) > 10000:
        raise ValueError("Invalid events")
    normalized, ids = [], set()
    for event in events:
        if not isinstance(event, dict):
            raise ValueError("Invalid event")
        event_id = integer(event.get("id"), -(2**63), 2**63 - 1)
        if event_id in ids:
            raise ValueError("Duplicate event ID")
        ids.add(event_id)
        title, note = event.get("title"), event.get("note", "")
        if not isinstance(title, str) or not title.strip() or len(title) > 4096:
            raise ValueError("Invalid title")
        if not isinstance(note, str) or len(note) > 65536:
            raise ValueError("Invalid note")
        try:
            event_date = date.fromisoformat(event["date"])
        except (KeyError, TypeError, ValueError):
            raise ValueError("Invalid date") from None
        if not 1900 <= event_date.year <= 2999:
            raise ValueError("Invalid date year")
        offsets = event.get("remindDaysBefore")
        if not isinstance(offsets, list) or len(offsets) > 3651:
            raise ValueError("Invalid reminder offsets")
        offsets = sorted({integer(n, 0, 3650) for n in offsets})
        cycle = event.get("cycle", "NONE")
        if cycle not in ("NONE", "DAY", "WEEK", "MONTH", "YEAR"):
            raise ValueError("Invalid cycle")
        normalized.append(dict(
            id=event_id, title=title.strip(), note=note, date=event_date.isoformat(),
            remindDaysBefore=offsets, remindHour=integer(event.get("remindHour"), 0, 23),
            remindMinute=integer(event.get("remindMinute"), 0, 59), cycle=cycle,
            repeatN=integer(event.get("repeatN", 1), 1, 3650)))
    groups, claimed = [], set()
    raw_groups = raw.get("mergeGroups", [])
    if not isinstance(raw_groups, list) or len(raw_groups) > 5000:
        raise ValueError("Invalid merge groups")
    for group in raw_groups:
        if not isinstance(group, dict) or not isinstance(group.get("eventIds"), list):
            raise ValueError("Invalid merge group")
        members = sorted({integer(n, -(2**63), 2**63 - 1) for n in group["eventIds"]})
        if len(members) < 2 or not set(members) <= ids or claimed.intersection(members):
            raise ValueError("Invalid merge membership")
        claimed.update(members)
        groups.append(members)
    channels = validate_channels(raw.get("notificationChannels", ["telegram"]))
    return dict(clientId=client, revision=revision, timezone=zone, notificationChannels=channels,
                events=sorted(normalized, key=lambda e: e["id"]), mergeGroups=sorted(groups))


def occurrence(event, index):
    anchor = date.fromisoformat(event["date"])
    amount, cycle = index * event["repeatN"], event["cycle"]
    if cycle == "NONE":
        return anchor
    if cycle in ("DAY", "WEEK"):
        return anchor + timedelta(days=amount * (7 if cycle == "WEEK" else 1))
    months = anchor.year * 12 + anchor.month - 1 + amount * (12 if cycle == "YEAR" else 1)
    year, month0 = divmod(months, 12)
    return date(year, month0 + 1, min(anchor.day, calendar.monthrange(year, month0 + 1)[1]))


def first_index(event, threshold):
    anchor = date.fromisoformat(event["date"])
    if anchor >= threshold or event["cycle"] == "NONE":
        return 0
    cycle = event["cycle"]
    if cycle in ("DAY", "WEEK"):
        units = (threshold - anchor).days // (7 if cycle == "WEEK" else 1)
    elif cycle == "MONTH":
        units = (threshold.year - anchor.year) * 12 + threshold.month - anchor.month
    else:
        units = threshold.year - anchor.year
    index = max(0, units // event["repeatN"])
    while occurrence(event, index) < threshold:
        index += 1
    return index


def triggers(event, zone, start, end):
    """Anchor-based repeats, including leap days/month ends and per-device timezone."""
    result = {}
    for offset in event["remindDaysBefore"]:
        threshold = start.astimezone(zone).date() + timedelta(days=offset)
        index = first_index(event, threshold)
        while True:
            try:
                target = occurrence(event, index)
                day = target - timedelta(days=offset)
                local = datetime.combine(day, datetime.min.time()).replace(
                    hour=event["remindHour"], minute=event["remindMinute"], tzinfo=zone)
                due = local.astimezone(UTC)
            except (OverflowError, ValueError):
                break
            if due > end:
                break
            if due >= start:
                # Overlapping repeat/offset rules share one delivery at the same instant.
                result.setdefault(int(due.timestamp()), target)
            if event["cycle"] == "NONE":
                break
            index += 1
    return sorted(result.items())


def clip_message(text):
    # Conservative UTF-16 limit; never split an emoji surrogate pair.
    encoded = text.encode("utf-16-le")
    if len(encoded) <= 4096 * 2:
        return text
    return encoded[:4095 * 2].decode("utf-16-le", errors="ignore") + "…"


def clip_ntfy_message(text):
    encoded = text.encode("utf-8")
    return text if len(encoded) <= 4096 else encoded[:4093].decode("utf-8", errors="ignore") + "…"


class DeliveryError(Exception):
    def __init__(self, message, retryable=True, retry_after=0):
        super().__init__(message)
        self.retryable = retryable
        self.retry_after = retry_after


class Telegram:
    def __init__(self, token, chat_id, opener=request.urlopen):
        if not re.fullmatch(r"[0-9]+:[a-zA-Z0-9_-]+", token):
            raise ValueError("Invalid TELEGRAM_BOT_TOKEN")
        if not re.fullmatch(r"-?[0-9]+|@[a-zA-Z0-9_]+", chat_id):
            raise ValueError("Invalid TELEGRAM_CHAT_ID")
        self._token, self._chat_id, self._opener = token, chat_id, opener

    def send(self, text):
        payload = json.dumps(dict(chat_id=self._chat_id, text=clip_message(text))).encode()
        req = request.Request("https://api.telegram.org/bot" + self._token + "/sendMessage",
                              data=payload, headers={"Content-Type": "application/json"})
        try:
            try:
                response = self._opener(req, timeout=15)
            except error.HTTPError as exc:
                response = exc
            with response:
                code = response.code
                raw = response.read(65536)
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                raise DeliveryError("Telegram 返回无效响应") from None
            if not isinstance(body, dict):
                raise DeliveryError("Telegram 返回无效响应")
            if code == 200 and body.get("ok") is True:
                return
            api_code = body.get("error_code", code)
            retry = api_code == 429 or code >= 500 or api_code >= 500
            after = body.get("parameters", {}).get("retry_after", 0)
            after = max(0, min(after, 86400)) if type(after) is int else 0
            # Do not echo remote descriptions/exceptions: they may contain the token URL.
            raise DeliveryError(f"Telegram 推送失败（HTTP {code}）", retry, after)
        except DeliveryError:
            raise
        except Exception:
            raise DeliveryError("无法连接 Telegram，请检查服务器网络") from None


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Ntfy:
    def __init__(self, url, topic, token="", opener=None):
        uri = parse.urlsplit(url)
        if (any(ord(c) <= 32 for c in url) or uri.scheme != "https" or not uri.hostname or uri.username or uri.password or
                uri.query or uri.fragment or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", topic)):
            raise ValueError("Invalid NTFY_URL or NTFY_TOPIC")
        if token and (not token.isascii() or any(ord(c) < 33 or ord(c) > 126 for c in token)):
            raise ValueError("Invalid NTFY_TOKEN")
        self._url, self._topic, self._token = url.rstrip('/'), topic, token
        self._opener = opener or request.build_opener(NoRedirect()).open

    def send(self, text):
        # JSON publishing preserves Unicode titles and avoids header encoding issues.
        payload = json.dumps(dict(topic=self._topic, title="Await 日程提醒", message=clip_ntfy_message(text)),
                             ensure_ascii=False).encode()
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if self._token:
            headers["Authorization"] = "Bearer " + self._token
        req = request.Request(self._url, data=payload, headers=headers)
        try:
            try:
                response = self._opener(req, timeout=15)
            except error.HTTPError as exc:
                response = exc
            with response:
                code = response.code
                raw = response.read(65536)
                retry_after = response.headers.get("Retry-After", "0") if hasattr(response, "headers") else "0"
            if 200 <= code < 300:
                try:
                    body = json.loads(raw)
                except (ValueError, UnicodeDecodeError):
                    raise DeliveryError("ntfy 返回无效响应") from None
                if isinstance(body, dict) and body.get("id") and body.get("event") == "message":
                    return
                raise DeliveryError("ntfy 返回无效响应")
            after = min(int(retry_after), 86400) if retry_after.isdigit() else 0
            raise DeliveryError(f"ntfy 推送失败（HTTP {code}）", code == 429 or code >= 500, after)
        except DeliveryError:
            raise
        except Exception:
            raise DeliveryError("无法连接 ntfy，请检查服务器网络") from None


class ScheduleStore:
    def __init__(self, path, sender, clock=time.time):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS snapshot (id INTEGER PRIMARY KEY, body TEXT NOT NULL, synced_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, due INTEGER NOT NULL, members TEXT NOT NULL,
                body TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0,
                error TEXT NOT NULL DEFAULT '', sent_at INTEGER NOT NULL DEFAULT 0);
        """)
        # Migrate existing queues without changing Telegram job IDs or sent history.
        if "channel" not in {row[1] for row in self.db.execute("PRAGMA table_info(jobs)")}:
            self.db.execute("ALTER TABLE jobs ADD COLUMN channel TEXT NOT NULL DEFAULT 'telegram'")
            self.db.commit()
        self.lock, self.clock = threading.RLock(), clock
        self.senders = sender if isinstance(sender, dict) else {"telegram": sender}
        self._last_generated = None

    def snapshot(self):
        row = self.db.execute("SELECT body FROM snapshot WHERE id=1").fetchone()
        if not row:
            return None
        body = json.loads(row["body"])
        body.setdefault("notificationChannels", ["telegram"])
        return body

    def update(self, raw):
        body = validate_snapshot(raw)
        if not set(body["notificationChannels"]) <= self.senders.keys():
            raise ValueError("Selected channel is not configured on server")
        with self.lock, self.db:
            old = self.snapshot()
            if old and body["clientId"] != old["clientId"]:
                raise PermissionError("服务端已绑定另一台手机")
            if old and body["revision"] < old["revision"]:
                raise ValueError("Stale revision")
            if old and body["revision"] == old["revision"]:
                if body != old:
                    raise ValueError("Revision conflict")
                return self.status()
            now = int(self.clock())
            self.db.execute("INSERT OR REPLACE INTO snapshot VALUES (1,?,?)", (canonical(body), now))
            # Remove deleted/changed reminders, preserving retries for unchanged events.
            valid_ids = self._generate(body, now - 86400, end_at=now + 2 * 86400, insert=False)
            for job in self.db.execute("SELECT id FROM jobs WHERE state='pending'").fetchall():
                if job["id"] not in valid_ids:
                    self.db.execute("DELETE FROM jobs WHERE id=?", (job["id"],))
            self._generate(body, now)
            self._last_generated = now
            return self.status()

    def _generate(self, snapshot, now, end_at=None, insert=True):
        zone = parse_zone(snapshot["timezone"])
        start = datetime.fromtimestamp(now, UTC)
        end = datetime.fromtimestamp(end_at, UTC) if end_at is not None else start + timedelta(days=2)
        group_by_id = {member: tuple(group) for group in snapshot["mergeGroups"] for member in group}
        buckets = {}
        for event in snapshot["events"]:
            for due, target in triggers(event, zone, start, end):
                # Different dates/rules inside an old group must never be merged.
                signature = (target, tuple(event["remindDaysBefore"]), event["remindHour"],
                             event["remindMinute"])
                key = (due, group_by_id.get(event["id"], (event["id"],)), signature)
                buckets.setdefault(key, []).append((event, target))
        valid_ids = set()
        for (due, _, _), events in buckets.items():
            members = sorted(digest([event, snapshot["timezone"]]) for event, _ in events)
            text = "⏳ Await 日程提醒\n\n" + "\n\n".join(
                event["title"] + "\n" + target.isoformat() + " · " +
                ("就是今天！" if (target - datetime.fromtimestamp(due, zone).date()).days == 0
                 else f"还有 {(target - datetime.fromtimestamp(due, zone).date()).days} 天") +
                ("\n" + event["note"] if event["note"] else "") for event, target in events)
            for channel in snapshot.get("notificationChannels", ["telegram"]):
                job_id = digest([due, members]) if channel == "telegram" else digest([due, members, channel])
                valid_ids.add(job_id)
                if insert:
                    self.db.execute("INSERT OR IGNORE INTO jobs (id,due,members,body,channel) VALUES (?,?,?,?,?)",
                                    (job_id, due, canonical(members), clip_message(text), channel))
        return valid_ids

    def tick(self):
        with self.lock, self.db:
            now = int(self.clock())
            snapshot = self.snapshot()
            if snapshot and (self._last_generated is None or now < self._last_generated or
                             now - self._last_generated >= 60):
                synced_at = self.db.execute("SELECT synced_at FROM snapshot WHERE id=1").fetchone()[0]
                self._generate(snapshot, max(synced_at, now - 86400), end_at=now + 2 * 86400)
                self._last_generated = now
            # Limit catch-up to 24 hours, retaining a visible failure state afterwards.
            self.db.execute("UPDATE jobs SET state='failed',error='提醒超过 24 小时未送达' "
                            "WHERE state='pending' AND due<?", (now - 86400,))
            jobs = self.db.execute("SELECT * FROM jobs WHERE state='pending' AND due<=? "
                                   "AND retry_at<=? ORDER BY due LIMIT 20", (now, now)).fetchall()
            for job in jobs:
                try:
                    sender = self.senders.get(job["channel"])
                    if sender is None:
                        raise DeliveryError("通知渠道未配置", retryable=False)
                    sender.send(job["body"])
                except DeliveryError as exc:
                    attempts = job["attempts"] + 1
                    delay = max(exc.retry_after, min(3600, 30 * 2 ** min(attempts - 1, 7)))
                    self.db.execute("UPDATE jobs SET attempts=?,retry_at=?,error=?,state=? WHERE id=?",
                                    (attempts, now + delay, str(exc),
                                     "pending" if exc.retryable else "failed", job["id"]))
                else:
                    self.db.execute("UPDATE jobs SET state='sent',sent_at=?,error='' WHERE id=?",
                                    (now, job["id"]))
            self.db.execute("DELETE FROM jobs WHERE state!='pending' AND due<?", (now - 30 * 86400,))

    def status(self):
        with self.lock:
            snapshot = self.snapshot()
            counts = {row["state"]: row["n"] for row in self.db.execute(
                "SELECT state,count(*) AS n FROM jobs GROUP BY state")}
            last = self.db.execute("SELECT error FROM jobs WHERE error!='' ORDER BY due DESC LIMIT 1").fetchone()
            return dict(revision=snapshot["revision"] if snapshot else 0,
                        notificationProtocol=1, availableChannels=sorted(self.senders),
                        notificationChannels=snapshot["notificationChannels"] if snapshot else [],
                        clientId=snapshot["clientId"] if snapshot else "",
                        eventCount=len(snapshot["events"]) if snapshot else 0,
                        pending=counts.get("pending", 0), failed=counts.get("failed", 0),
                        lastError=last["error"] if last else "",
                        lastSentAt=self.db.execute("SELECT coalesce(max(sent_at),0) FROM jobs").fetchone()[0])


def make_handler(store, api_key):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(20)

        def log_message(self, *_):
            pass  # Never log request headers, bodies or credentials.

        def reply(self, code, body):
            data = canonical(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            given = self.headers.get("Authorization", "").encode()
            if not hmac.compare_digest(given, ("Bearer " + api_key).encode()):
                self.reply(401, dict(error="Unauthorized"))
                return False
            return True

        def do_GET(self):
            if self.path == "/healthz":
                self.reply(200, dict(ok=True))
            elif self.authorized():
                if self.path == "/v1/status":
                    self.reply(200, store.status())
                else:
                    self.reply(404, dict(error="Not found"))

        def do_PUT(self):
            if not self.authorized():
                return
            if self.path != "/v1/schedule":
                self.reply(404, dict(error="Not found"))
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY:
                    self.reply(413, dict(error="Invalid body size"))
                    return
                body = json.loads(self.rfile.read(length))
                self.reply(200, store.update(body))
            except PermissionError:
                self.reply(409, dict(error="Different client"))
            except (ValueError, TypeError, OverflowError, RecursionError):
                self.reply(400, dict(error="Invalid snapshot or revision conflict"))

        def do_POST(self):
            if not self.authorized():
                return
            if self.path != "/v1/test":
                self.reply(404, dict(error="Not found"))
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY:
                    self.reply(413, dict(error="Invalid body size"))
                    return
                body = json.loads(self.rfile.read(length))
                channels = validate_channels(body.get("notificationChannels", ["telegram"]))
                if not channels or not set(channels) <= store.senders.keys():
                    raise ValueError("Unavailable channel")
                results = {}
                with store.lock:
                    for channel in channels:
                        try:
                            store.senders[channel].send("✅ Await 测试推送\n服务器通知链路正常。")
                            results[channel] = "已发送"
                        except DeliveryError as exc:
                            results[channel] = str(exc)
                self.reply(200, dict(ok=all(v == "已发送" for v in results.values()), results=results))
            except (ValueError, TypeError, AttributeError):
                self.reply(400, dict(error="Invalid or unavailable notification channels"))
    return Handler


def main():
    os.umask(0o077)
    required = ("AWAIT_API_KEY",)
    if any(not os.environ.get(name) for name in required):
        raise SystemExit("Required environment variables: " + ", ".join(required))
    key = os.environ["AWAIT_API_KEY"]
    if len(key) < 32 or not key.isascii() or any(c.isspace() for c in key):
        raise SystemExit("AWAIT_API_KEY must contain at least 32 non-whitespace ASCII characters")
    senders = {}
    if os.environ.get("TELEGRAM_BOT_TOKEN") or os.environ.get("TELEGRAM_CHAT_ID"):
        senders["telegram"] = Telegram(os.environ.get("TELEGRAM_BOT_TOKEN", ""), os.environ.get("TELEGRAM_CHAT_ID", ""))
    if os.environ.get("NTFY_URL") or os.environ.get("NTFY_TOPIC"):
        senders["ntfy"] = Ntfy(os.environ.get("NTFY_URL", ""), os.environ.get("NTFY_TOPIC", ""),
                               os.environ.get("NTFY_TOKEN", ""))
    path = os.environ.get("AWAIT_DB", "/data/await.sqlite3")
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    store = ScheduleStore(path, senders)
    stop = threading.Event()

    def scheduler():
        while not stop.is_set():
            try:
                store.tick()
            except Exception:
                print("Scheduler iteration failed; retrying", flush=True)
            stop.wait(1)

    threading.Thread(target=scheduler, daemon=True).start()
    http = ThreadingHTTPServer((os.environ.get("AWAIT_BIND", "0.0.0.0"),
                               int(os.environ.get("AWAIT_PORT", "8080"))), make_handler(store, key))
    try:
        http.serve_forever()
    finally:
        stop.set()
        http.server_close()


if __name__ == "__main__":
    main()
