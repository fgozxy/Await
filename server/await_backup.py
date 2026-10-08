"""Optional, authenticated, encrypted backups of complete Android export bundles."""
import json
import os
import re
import uuid
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def backup_enabled(value):
    normalized = value.strip().lower()
    if normalized not in {"true", "false", "1", "0"}:
        raise ValueError("AWAIT_BACKUP_ENABLED must be true or false")
    return normalized in {"true", "1"}


def validate_bundle(body):
    # Only the export format is accepted: service credentials/settings cannot be uploaded accidentally.
    fields = {"app", "formatVersion", "exportedAt", "appVersion", "eventCount", "events", "groups", "mergeGroups"}
    if not isinstance(body, dict) or set(body) != fields or body["app"] != "Await":
        raise ValueError("Invalid backup bundle")
    if type(body["formatVersion"]) is not int or body["formatVersion"] != 1:
        raise ValueError("Unsupported backup format")
    for field in ("exportedAt", "appVersion"):
        if not isinstance(body[field], str) or len(body[field]) > 100:
            raise ValueError("Invalid backup metadata")
    events = body["events"]
    if not isinstance(events, list) or len(events) > 10000:
        raise ValueError("Invalid backup events")
    if type(body["eventCount"]) is not int or body["eventCount"] != len(events):
        raise ValueError("Invalid backup event count")
    event_fields = {"id", "title", "dateEpochDay", "note", "pinned", "colorIndex", "remindDaysBefore",
                    "remindHour", "remindMinute", "repeatSpec", "repeatCycle", "repeatEveryDays",
                    "repeatYearly", "groupName"}
    ids = set()
    for event in events:
        if not isinstance(event, dict) or not set(event) <= event_fields:
            raise ValueError("Invalid backup event fields")
        if type(event.get("id")) is not int or not -(2**63) <= event["id"] < 2**63 or event["id"] in ids:
            raise ValueError("Invalid backup event ID")
        ids.add(event["id"])
        if not isinstance(event.get("title"), str) or not event["title"].strip():
            raise ValueError("Invalid backup event title")
        if type(event.get("dateEpochDay")) is not int or not -25567 <= event["dateEpochDay"] <= 376199:
            raise ValueError("Invalid backup event date")
        for field in ("title", "note", "repeatSpec", "repeatCycle", "groupName"):
            if field in event and event[field] is not None and not isinstance(event[field], str):
                raise ValueError("Invalid backup event text")
        for field in ("pinned", "repeatYearly"):
            if field in event and type(event[field]) is not bool:
                raise ValueError("Invalid backup event flag")
        for field, low, high in (("colorIndex", 0, 2**31 - 1), ("remindHour", 0, 23),
                                 ("remindMinute", 0, 59), ("repeatEveryDays", 0, 2**31 - 1)):
            if field in event and (type(event[field]) is not int or not low <= event[field] <= high):
                raise ValueError("Invalid backup event integer")
        reminders = event.get("remindDaysBefore", [])
        if not isinstance(reminders, list) or any(type(day) is not int or not 0 <= day <= 3650 for day in reminders):
            raise ValueError("Invalid backup event reminders")
    groups, merges = body["groups"], body["mergeGroups"]
    if not isinstance(groups, list) or len(groups) > 10000 or any(not isinstance(g, str) for g in groups):
        raise ValueError("Invalid backup groups")
    if not isinstance(merges, list) or len(merges) > 10000:
        raise ValueError("Invalid backup merge groups")
    for group in merges:
        if not isinstance(group, dict) or set(group) != {"id", "eventIds"} or type(group["id"]) is not int:
            raise ValueError("Invalid backup merge group")
        if not isinstance(group["eventIds"], list) or any(type(i) is not int or i not in ids for i in group["eventIds"]):
            raise ValueError("Invalid backup merge members")
    return body


class BackupStore:
    def __init__(self, store, api_key, enabled=False):
        self.store, self.enabled = store, enabled
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=b"Await backups v1",
                   info=b"AES-256-GCM backup bundles").derive(api_key.encode())
        self.cipher = AESGCM(key)
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS cloud_backups (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL,
                    event_count INTEGER NOT NULL, size INTEGER NOT NULL, ciphertext BLOB NOT NULL);
                CREATE TABLE IF NOT EXISTS backup_owner (id INTEGER PRIMARY KEY CHECK(id=1), client_id TEXT NOT NULL);
            """)
            if enabled and store.db.execute("SELECT 1 FROM cloud_backups LIMIT 1").fetchone():
                self.fetch("latest")  # Fail closed on an incorrect key; do not overwrite old backups.

    def owner(self):
        row = self.store.db.execute("SELECT client_id FROM backup_owner WHERE id=1").fetchone()
        return row[0] if row else ""

    def list(self):
        with self.store.lock:
            return dict(backups=[dict(id=r["id"], name=r["name"], createdAt=r["created_at"],
                                      eventCount=r["event_count"], size=r["size"])
                                 for r in self.store.db.execute(
                                     "SELECT id,name,created_at,event_count,size FROM cloud_backups ORDER BY created_at DESC, rowid DESC")])

    def save(self, raw):
        if not isinstance(raw, dict) or set(raw) != {"clientId", "keepCount", "backup"}:
            raise ValueError("Invalid backup request")
        client, keep = raw["clientId"], raw["keepCount"]
        if not isinstance(client, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,64}", client):
            raise ValueError("Invalid client ID")
        if type(keep) is not int or not 1 <= keep <= 100:
            raise ValueError("Invalid backup retention")
        bundle = validate_bundle(raw["backup"])
        data = json.dumps(bundle, ensure_ascii=False, separators=(",", ":")).encode()
        if len(data) > 4 * 1024 * 1024:
            raise ValueError("Backup too large")
        identity = uuid.uuid4().hex
        now = int(self.store.clock() * 1000)
        stamp = datetime.fromtimestamp(now / 1000, timezone.utc).strftime("%Y%m%d-%H%M%S")
        name = f"Await-backup-{stamp}-{identity[:8]}.json"
        nonce = os.urandom(12)
        encrypted = nonce + self.cipher.encrypt(nonce, data, identity.encode())
        with self.store.lock, self.store.db:
            snapshot = self.store.snapshot()
            owner = snapshot["clientId"] if snapshot else self.owner()
            if owner and owner != client:
                raise PermissionError("Different client")
            self.store.db.execute("INSERT OR IGNORE INTO backup_owner VALUES (1,?)", (client,))
            self.store.db.execute("INSERT INTO cloud_backups VALUES (?,?,?,?,?,?)",
                                  (identity, name, now, len(bundle["events"]), len(data), encrypted))
            self.store.db.execute("""DELETE FROM cloud_backups WHERE id IN (
                SELECT id FROM cloud_backups ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET ?)""", (keep,))
        return dict(id=identity, name=name, createdAt=now, eventCount=len(bundle["events"]), size=len(data))

    def fetch(self, identity):
        if identity != "latest" and not re.fullmatch(r"[a-f0-9]{32}", identity):
            raise FileNotFoundError("Backup not found")
        with self.store.lock:
            row = self.store.db.execute(
                "SELECT id,ciphertext FROM cloud_backups ORDER BY created_at DESC, rowid DESC LIMIT 1"
                if identity == "latest" else "SELECT id,ciphertext FROM cloud_backups WHERE id=?",
                () if identity == "latest" else (identity,)).fetchone()
            if not row:
                raise FileNotFoundError("Backup not found")
            blob = row["ciphertext"]
            try:
                return json.loads(self.cipher.decrypt(blob[:12], blob[12:], row["id"].encode()))
            except Exception:
                raise ValueError("Unable to decrypt backup; restore the original AWAIT_API_KEY") from None
