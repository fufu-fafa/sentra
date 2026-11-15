// SENTRA relay: the child's phone (mode Anak) uploads events, GPS fixes and heartbeats; the
// parents' phones (mode Orang tua, "SENTRA Care") get them at once over a live Server-Sent Events
// stream and, for alarms, by Firebase Cloud Messaging push (push.mjs). Forked from the
// fall-bracelet relay. Node 22+; the only dependency is firebase-admin, and without it (or its
// key) the relay works stream-only.
//
//   node --disable-warning=ExperimentalWarning server.mjs      (PORT, DB_PATH env vars optional)
//
// Naming: a "child" is the child's phone (mode Anak), a "parent" a parent's phone (SENTRA Care).
// Auth: every phone gets a random bearer token when it registers or links; only its SHA-256 is
// stored. The child's phone makes short-lived invite codes; a parent types one to link.

import http from 'node:http';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { initPush, push } from './push.mjs';

const PORT = Number(process.env.PORT ?? 8787);
// Local network only by default: requests from public addresses are refused. LAN_ONLY=0 lifts it
// once the relay sits behind HTTPS on the VPS.
const LAN_ONLY = process.env.LAN_ONLY !== '0';
const DB_PATH = process.env.DB_PATH ?? new URL('./relay.db', import.meta.url).pathname;
const WEB_PAGE = new URL('./web/index.html', import.meta.url);
// While a trip is running the phone reports every few seconds, so a minute of silence is an alarm.
const OFFLINE_NAV_MS = Number(process.env.OFFLINE_NAV_MS ?? 60_000);
// Idle phones only send a heartbeat every 5 min.
const OFFLINE_IDLE_MS = Number(process.env.OFFLINE_IDLE_MS ?? 15 * 60_000);
const TICK_MS = Number(process.env.TICK_MS ?? 5_000);
const ESCALATE_AFTER_MS = Number(process.env.ESCALATE_AFTER_MS ?? 2 * 60_000); // re-push an unacknowledged alarm
const MAX_ESCALATIONS = 5;
const INVITE_TTL_MS = 15 * 60_000;
const REPLAY_WINDOW_MS = 24 * 3600_000; // what a reconnecting parent can catch up on
const LOCATION_TTL_MS = 30 * 24 * 3600_000; // privacy: fixes older than 30 days are deleted
const KEEPALIVE_MS = 25_000;
const MAX_POINTS_PER_POST = 500;
/** Full-screen alarms on the parents' phones, re-pushed until someone acknowledges. */
const ALARM_TYPES = ['KELUAR_RUTE', 'TERPUTUS', 'OFFLINE'];
const isAlarm = (type) => ALARM_TYPES.includes(type);
/** A later event that shows an alarm is over, so it stops ringing by itself. */
const RESOLVES = {
  TERSAMBUNG: { types: ['TERPUTUS'], by: 'otomatis: sleeve tersambung lagi' },
  KEMBALI_RUTE: { types: ['KELUAR_RUTE'], by: 'otomatis: kembali ke rute' },
  ARRIVED: { types: ['KELUAR_RUTE'], by: 'otomatis: sudah sampai' },
};

// --- Storage ----------------------------------------------------------------------------------

const db = new DatabaseSync(DB_PATH);
// Databases made before the rename used fall-bracelet's names (wearers, caregivers); move them over.
if (db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'wearers'").get()) {
  const hasColumn = (table, column) => db.prepare(`PRAGMA table_info(${table})`).all().some((c) => c.name === column);
  db.exec('ALTER TABLE wearers RENAME TO children; ALTER TABLE caregivers RENAME TO parents;');
  for (const t of ['links', 'invites', 'events', 'locations']) {
    if (hasColumn(t, 'wearer_id')) db.exec(`ALTER TABLE ${t} RENAME COLUMN wearer_id TO child_id`);
  }
  for (const t of ['links', 'push_tokens']) {
    if (hasColumn(t, 'caregiver_id')) db.exec(`ALTER TABLE ${t} RENAME COLUMN caregiver_id TO parent_id`);
  }
}
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS children (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL, last_heartbeat INTEGER, offline INTEGER NOT NULL DEFAULT 0,
    navigating INTEGER NOT NULL DEFAULT 0, status TEXT);
  CREATE TABLE IF NOT EXISTS parents (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS links (
    child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL, PRIMARY KEY (child_id, parent_id));
  CREATE TABLE IF NOT EXISTS invites (
    code TEXT PRIMARY KEY, child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY, child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    device_address TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, battery_pct INTEGER,
    occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL, lat REAL, lon REAL,
    message TEXT NOT NULL, acked_by TEXT, acked_at INTEGER,
    escalations INTEGER NOT NULL DEFAULT 0,
    UNIQUE (child_id, device_address, seq));
  CREATE TABLE IF NOT EXISTS locations (
    id INTEGER PRIMARY KEY, child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    at INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, speed REAL, bearing REAL,
    received_at INTEGER NOT NULL,
    UNIQUE (child_id, at));
  CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS push_tokens (
    token TEXT PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
    updated_at INTEGER NOT NULL);
  PRAGMA foreign_keys = ON;
`);
// Identifies this relay across address changes (e.g. a new hotspot), so phones keep their links.
db.prepare("INSERT OR IGNORE INTO meta (key, value) VALUES ('relay_id', ?)").run(crypto.randomBytes(8).toString('hex'));
const RELAY_ID = db.prepare("SELECT value FROM meta WHERE key = 'relay_id'").get().value;

const now = () => Date.now();
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = () => crypto.randomBytes(32).toString('base64url');
const marks = (list) => list.map(() => '?').join(', ');

const q = {
  childByToken: db.prepare('SELECT * FROM children WHERE token_hash = ?'),
  childById: db.prepare('SELECT * FROM children WHERE id = ?'),
  parentByToken: db.prepare('SELECT * FROM parents WHERE token_hash = ?'),
  addChild: db.prepare('INSERT INTO children (name, token_hash, created_at) VALUES (?, ?, ?)'),
  renameChild: db.prepare('UPDATE children SET name = ? WHERE id = ?'),
  addParent: db.prepare('INSERT INTO parents (name, token_hash, created_at) VALUES (?, ?, ?)'),
  addInvite: db.prepare('INSERT INTO invites (code, child_id, expires_at) VALUES (?, ?, ?)'),
  takeInvite: db.prepare('DELETE FROM invites WHERE code = ? AND expires_at > ? RETURNING child_id'),
  purgeInvites: db.prepare('DELETE FROM invites WHERE expires_at <= ?'),
  link: db.prepare('INSERT OR IGNORE INTO links (child_id, parent_id, created_at) VALUES (?, ?, ?)'),
  unlink: db.prepare('DELETE FROM links WHERE child_id = ? AND parent_id = ?'),
  parentsOf: db.prepare(`SELECT c.id, c.name, l.created_at AS linkedAt FROM links l
    JOIN parents c ON c.id = l.parent_id WHERE l.child_id = ? ORDER BY l.created_at`),
  childrenOf: db.prepare(`SELECT w.* FROM links l JOIN children w ON w.id = l.child_id
    WHERE l.parent_id = ? ORDER BY w.name`),
  isLinked: db.prepare('SELECT 1 FROM links WHERE child_id = ? AND parent_id = ?'),
  insertEvent: db.prepare(`INSERT OR IGNORE INTO events (child_id, device_address, seq, type, battery_pct,
    occurred_at, received_at, lat, lon, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
  eventByKey: db.prepare('SELECT * FROM events WHERE child_id = ? AND device_address = ? AND seq = ?'),
  eventById: db.prepare('SELECT * FROM events WHERE id = ?'),
  eventsOf: db.prepare('SELECT * FROM events WHERE child_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?'),
  eventsSince: db.prepare(`SELECT e.* FROM events e JOIN links l ON l.child_id = e.child_id
    WHERE l.parent_id = ? AND e.id > ? AND e.received_at > ? ORDER BY e.id`),
  ack: db.prepare('UPDATE events SET acked_by = ?, acked_at = ? WHERE id = ? AND acked_at IS NULL'),
  acksFor: db.prepare(`SELECT id, seq, device_address, acked_by, acked_at FROM events
    WHERE child_id = ? AND acked_at > ? ORDER BY acked_at`),
  contact: db.prepare('UPDATE children SET last_heartbeat = ?, offline = 0 WHERE id = ?'),
  heartbeat: db.prepare('UPDATE children SET last_heartbeat = ?, offline = 0, navigating = ?, status = ? WHERE id = ?'),
  stale: db.prepare(`SELECT * FROM children WHERE offline = 0 AND last_heartbeat IS NOT NULL
    AND last_heartbeat < CASE WHEN navigating = 1 THEN ? ELSE ? END`),
  markOffline: db.prepare('UPDATE children SET offline = 1 WHERE id = ?'),
  unacked: db.prepare(`SELECT * FROM events WHERE type IN (${marks(ALARM_TYPES)}) AND acked_at IS NULL
    AND escalations < ? AND received_at < ? - ? * (escalations + 1) AND received_at > ?`),
  escalate: db.prepare('UPDATE events SET escalations = escalations + 1 WHERE id = ?'),
  insertLocation: db.prepare(`INSERT OR IGNORE INTO locations (child_id, at, lat, lon, acc, speed, bearing, received_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)`),
  lastLocation: db.prepare('SELECT * FROM locations WHERE child_id = ? ORDER BY at DESC LIMIT 1'),
  // Newest first so LIMIT keeps the latest part of a long track; reversed before it is sent.
  track: db.prepare('SELECT * FROM locations WHERE child_id = ? AND at > ? ORDER BY at DESC LIMIT ?'),
  purgeLocations: db.prepare('DELETE FROM locations WHERE at < ?'),
  // One FCM token per parent phone; a token that moves to another parent follows it.
  setPushToken: db.prepare(`INSERT INTO push_tokens (token, parent_id, updated_at) VALUES (?, ?, ?)
    ON CONFLICT (token) DO UPDATE SET parent_id = excluded.parent_id, updated_at = excluded.updated_at`),
  dropPushTokensOf: db.prepare('DELETE FROM push_tokens WHERE parent_id = ? AND token != ?'),
  dropPushToken: db.prepare('DELETE FROM push_tokens WHERE token = ?'),
  pushTokensOf: db.prepare(`SELECT p.token FROM links l JOIN push_tokens p ON p.parent_id = l.parent_id
    WHERE l.child_id = ?`),
};

// --- Live streams -----------------------------------------------------------------------------

/** parentId -> Set of open SSE responses. */
const streams = new Map();

function send(res, type, data, id) {
  res.write(`${id != null ? `id: ${id}\n` : ''}event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
}

/** Send to every parent linked to this child: down open streams, and by FCM push. */
function fanOut(childId, type, data, id) {
  for (const c of q.parentsOf.all(childId)) {
    for (const res of streams.get(c.id) ?? []) send(res, type, data, id);
  }
  const tokens = q.pushTokensOf.all(childId).map((r) => r.token);
  push(tokens, type, data, (t) => { q.dropPushToken.run(t); console.log('push: dropped a dead token'); });
}

const eventJson = (e) => ({
  id: e.id, childId: e.child_id, deviceAddress: e.device_address, seq: e.seq, type: e.type,
  batteryPct: e.battery_pct, occurredAt: e.occurred_at, receivedAt: e.received_at, lat: e.lat, lon: e.lon,
  message: e.message, ackedBy: e.acked_by, ackedAt: e.acked_at, alarm: isAlarm(e.type), escalations: e.escalations,
});

const locationJson = (l) => l && ({ at: l.at, lat: l.lat, lon: l.lon, acc: l.acc, speed: l.speed, bearing: l.bearing });

const childJson = (w) => ({
  id: w.id, name: w.name, lastHeartbeat: w.last_heartbeat, offline: !!w.offline, navigating: !!w.navigating,
  status: w.status ? JSON.parse(w.status) : null,
  location: locationJson(q.lastLocation.get(w.id)) ?? null,
});

/** Acknowledges this child's open alarms of the given types on the system's behalf. */
function resolveAlarms(childId, types, by) {
  const open = db.prepare(`SELECT id FROM events WHERE child_id = ? AND acked_at IS NULL AND type IN (${marks(types)})`)
    .all(childId, ...types);
  for (const e of open) {
    if (q.ack.run(by, now(), e.id).changes) fanOut(childId, 'ack', { eventId: e.id, childId, by, at: now() });
  }
}

/** Stores one event and tells the parents. Returns [row, wasNew]. */
function addEvent(w, { device, seq, type, batteryPct, occurredAt, lat, lon, message }) {
  const r = q.insertEvent.run(w.id, device, seq, type, batteryPct, occurredAt, now(), lat, lon, message);
  const e = q.eventByKey.get(w.id, device, seq);
  if (r.changes) {
    fanOut(w.id, 'event', { ...eventJson(e), childName: w.name }, e.id);
    const done = RESOLVES[type];
    if (done) resolveAlarms(w.id, done.types, done.by);
  }
  return [e, !!r.changes];
}

/** The child's phone was heard from again after being marked offline. */
function backOnline(w) {
  fanOut(w.id, 'online', { childId: w.id, name: w.name });
  resolveAlarms(w.id, ['OFFLINE'], 'otomatis: HP anak online lagi');
}

// --- HTTP -------------------------------------------------------------------------------------

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function body(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 128_000) throw new HttpError(413, 'body too large');
  }
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { throw new HttpError(400, 'invalid JSON'); }
}

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization ?? '');
  return m ? sha(m[1]) : null;
}

function childAuth(req) {
  const h = bearer(req);
  const w = h && q.childByToken.get(h);
  if (!w) throw new HttpError(401, 'child token required');
  return w;
}

function parentAuth(req) {
  const h = bearer(req);
  const c = h && q.parentByToken.get(h);
  if (!c) throw new HttpError(401, 'parent token required');
  return c;
}

const str = (v, max = 80) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
const int = (v) => (v != null && v !== '' && Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : null);
const num = (v) => (v != null && v !== '' && Number.isFinite(Number(v)) ? Number(v) : null);
const coord = (v, limit) => { const n = num(v); return n != null && Math.abs(n) <= limit ? n : null; };
const query = (req) => new URL(req.url, 'http://x').searchParams;

function newInviteCode(childId, expiresAt) {
  // 6 digits, easy to read out loud; retried on the rare clash.
  for (;;) {
    const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
    try { q.addInvite.run(code, childId, expiresAt); return code; } catch { /* clash, try again */ }
  }
}

/** SENTRA Care in a browser (web/index.html), read per request so it can be updated in place. */
function carePage(req, p, res) {
  const html = readFileSync(WEB_PAGE);
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
  res.end(html);
}

const routes = {
  'GET /': carePage,
  'GET /care': carePage,

  /** Also how phones find a relay on the local network: they scan their WiFi for this reply. */
  'GET /v1/health': () => ({ ok: true, service: 'sentra-relay', relayId: RELAY_ID, time: now() }),

  /** The child's phone registers once and keeps the token. */
  'POST /v1/children': async (req) => {
    const b = await body(req);
    const name = str(b.name) || 'Anak';
    const token = newToken();
    const { lastInsertRowid } = q.addChild.run(name, sha(token), now());
    return { childId: Number(lastInsertRowid), token };
  },

  'PUT /v1/children/me': async (req) => {
    const w = childAuth(req);
    const name = str((await body(req)).name);
    if (name) q.renameChild.run(name, w.id);
    return { ok: true };
  },

  'POST /v1/invites': (req) => {
    const w = childAuth(req);
    q.purgeInvites.run(now());
    const expiresAt = now() + INVITE_TTL_MS;
    return { code: newInviteCode(w.id, expiresAt), expiresAt };
  },

  /** A parent links with an invite code; send an existing parent token to follow another child. */
  'POST /v1/link': async (req) => {
    const b = await body(req);
    const row = q.takeInvite.get(str(b.code, 12), now());
    if (!row) throw new HttpError(404, 'invite code not found or expired');
    let parent = bearer(req) && q.parentByToken.get(bearer(req));
    let token = null;
    if (!parent) {
      token = newToken();
      const { lastInsertRowid } = q.addParent.run(str(b.name) || 'Orang tua', sha(token), now());
      parent = { id: Number(lastInsertRowid) };
    }
    q.link.run(row.child_id, parent.id, now());
    return { parentId: parent.id, token, child: childJson(q.childById.get(row.child_id)) };
  },

  /** The parent phone's FCM token, sent after linking and whenever Firebase rotates it. */
  'PUT /v1/push-token': async (req) => {
    const c = parentAuth(req);
    const token = str((await body(req)).token, 4096);
    if (!token) throw new HttpError(400, 'token required');
    q.setPushToken.run(token, c.id, now());
    q.dropPushTokensOf.run(c.id, token); // one phone per parent key: the old token is stale
    return { ok: true };
  },

  'GET /v1/parents': (req) => ({ parents: q.parentsOf.all(childAuth(req).id) }),

  'DELETE /v1/parents/:id': (req, p) => {
    const w = childAuth(req);
    const id = int(p.id);
    q.unlink.run(w.id, id);
    for (const res of streams.get(id) ?? []) send(res, 'unlinked', { childId: w.id });
    return { ok: true };
  },

  /** Idempotent on (child, deviceAddress, seq): a retry after a lost response is harmless. */
  'POST /v1/events': async (req) => {
    const w = childAuth(req);
    const b = await body(req);
    const seq = int(b.seq), occurredAt = int(b.occurredAt);
    const device = str(b.deviceAddress, 32), type = str(b.type, 20).toUpperCase();
    if (seq == null || occurredAt == null || !device || !type) throw new HttpError(400, 'seq, deviceAddress, type, occurredAt required');
    // The relay raises OFFLINE itself; a phone can't claim it.
    if (type === 'OFFLINE') throw new HttpError(400, 'OFFLINE is raised by the relay');
    const [e, isNew] = addEvent(w, {
      device, seq, type, batteryPct: int(b.batteryPct), occurredAt,
      lat: coord(b.lat, 90), lon: coord(b.lon, 180), message: str(b.message, 500),
    });
    if (isNew) {
      q.contact.run(now(), w.id);
      if (w.offline) backOnline(w);
    }
    return { id: e.id, duplicate: !isNew };
  },

  /**
   * A batch of GPS fixes, oldest first: {points: [{at, lat, lon, acc?, speed?, bearing?}]}.
   * Idempotent on (child, at). Parents get the newest one live.
   */
  'POST /v1/locations': async (req) => {
    const w = childAuth(req);
    const points = (await body(req)).points;
    if (!Array.isArray(points) || points.length === 0) throw new HttpError(400, 'points required');
    if (points.length > MAX_POINTS_PER_POST) throw new HttpError(413, `at most ${MAX_POINTS_PER_POST} points per call`);
    let stored = 0, newest = null;
    db.exec('BEGIN');
    try {
      for (const p of points) {
        const at = int(p?.at), lat = coord(p?.lat, 90), lon = coord(p?.lon, 180);
        if (at == null || lat == null || lon == null) continue; // a bad point must not block the queue behind it
        stored += Number(q.insertLocation.run(w.id, at, lat, lon, num(p.acc), num(p.speed), num(p.bearing), now()).changes);
        if (!newest || at > newest.at) newest = { at, lat, lon, acc: num(p.acc), speed: num(p.speed), bearing: num(p.bearing) };
      }
      db.exec('COMMIT');
    } catch (err) {
      db.exec('ROLLBACK');
      throw err;
    }
    q.contact.run(now(), w.id);
    if (w.offline) backOnline(w);
    // Only a fix newer than what parents already have moves the marker (a late batch doesn't).
    const last = q.lastLocation.get(w.id);
    if (stored && newest && last && last.at === newest.at) {
      fanOut(w.id, 'location', { childId: w.id, ...newest });
    }
    return { stored };
  },

  /** Latest status: every 20 s during a trip, every 5 min when idle. The reply carries acknowledgements since `acksSince`. */
  'POST /v1/heartbeat': async (req) => {
    const w = childAuth(req);
    const b = await body(req);
    const st = b.status && typeof b.status === 'object' ? b.status : {};
    const status = JSON.stringify(st);
    if (status.length > 32_000) throw new HttpError(413, 'status too large');
    q.heartbeat.run(now(), st.navigating ? 1 : 0, status, w.id);
    if (w.offline) backOnline(w);
    fanOut(w.id, 'status', { childId: w.id, status: st, navigating: !!st.navigating, at: now() });
    return { acks: q.acksFor.all(w.id, int(b.acksSince) ?? 0), parents: q.parentsOf.all(w.id).length };
  },

  'GET /v1/dashboard': (req) => {
    const c = parentAuth(req);
    // The app and the web page ask for up to 1000 so they can page back through weeks.
    const limit = Math.min(Math.max(int(query(req).get('limit') ?? 100) || 100, 1), 1000);
    return {
      children: q.childrenOf.all(c.id).map((w) => ({
        ...childJson(w),
        events: q.eventsOf.all(w.id, limit).map(eventJson),
      })),
    };
  },

  /** Where the child has been, oldest first: ?since=<epoch ms> (default the last 2 h), ?limit= up to 5000. */
  'GET /v1/children/:id/track': (req, p) => {
    const c = parentAuth(req);
    const id = int(p.id);
    if (id == null || !q.isLinked.get(id, c.id)) throw new HttpError(404, 'not following this child');
    const since = int(query(req).get('since')) ?? now() - 2 * 3600_000;
    const limit = Math.min(Math.max(int(query(req).get('limit')) || 2000, 1), 5000);
    return { points: q.track.all(id, since, limit).reverse().map(locationJson) };
  },

  'POST /v1/events/:id/ack': (req, p) => {
    const c = parentAuth(req);
    const e = q.eventById.get(int(p.id));
    if (!e || !q.isLinked.get(e.child_id, c.id)) throw new HttpError(404, 'event not found');
    if (q.ack.run(c.name, now(), e.id).changes) {
      fanOut(e.child_id, 'ack', { eventId: e.id, childId: e.child_id, by: c.name, at: now() });
    }
    return { ok: true };
  },

  /** Live stream for a parent. Reconnect with Last-Event-ID to replay what was missed. */
  'GET /v1/stream': (req, p, res) => {
    const c = parentAuth(req);
    res.writeHead(200, {
      'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write('retry: 5000\n\n');
    send(res, 'hello', { parentId: c.id, children: q.childrenOf.all(c.id).map(childJson) });
    const lastId = int(req.headers['last-event-id']);
    if (lastId != null) {
      const names = new Map(q.childrenOf.all(c.id).map((w) => [w.id, w.name]));
      for (const e of q.eventsSince.all(c.id, lastId, now() - REPLAY_WINDOW_MS)) {
        send(res, 'event', { ...eventJson(e), childName: names.get(e.child_id), replay: true }, e.id);
      }
    }
    if (!streams.has(c.id)) streams.set(c.id, new Set());
    streams.get(c.id).add(res);
    const keepalive = setInterval(() => res.write(': keepalive\n\n'), KEEPALIVE_MS);
    req.on('close', () => {
      clearInterval(keepalive);
      streams.get(c.id)?.delete(res);
    });
    return undefined; // response stays open
  },
};

function match(method, path) {
  for (const [key, handler] of Object.entries(routes)) {
    const [m, pattern] = key.split(' ');
    if (m !== method) continue;
    const names = [];
    const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, n) => (names.push(n), '([^/]+)')) + '$');
    const hit = re.exec(path);
    if (hit) return [handler, Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(hit[i + 1])]))];
  }
  return [null, {}];
}

/** Loopback, link-local and private (RFC 1918 / ULA) addresses: the same network as the relay. */
function isLocal(addr = '') {
  const a = addr.replace(/^::ffff:/, '');
  if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(a)) return true;
  const m = /^172\.(\d+)\./.exec(a);
  if (m && Number(m[1]) >= 16 && Number(m[1]) <= 31) return true;
  return a === '::1' || /^f[cd]/i.test(a) || /^fe[89ab]/i.test(a);
}

const server = http.createServer(async (req, res) => {
  if (LAN_ONLY && !isLocal(req.socket.remoteAddress)) {
    res.writeHead(403, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'this relay only accepts phones on the local network' }));
    return;
  }
  const path = new URL(req.url, 'http://x').pathname;
  const [handler, params] = match(req.method, path);
  try {
    if (!handler) throw new HttpError(404, 'not found');
    const out = await handler(req, params, res);
    if (out !== undefined) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(out));
    }
  } catch (err) {
    const status = err.status ?? 500;
    if (status === 500) console.error(err);
    if (!res.headersSent) res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: err.message }));
  }
});

// --- Timers: silent phones, unacknowledged alarms, old locations ---------------------------------

let lastPurge = 0;

setInterval(() => {
  const t = now();
  for (const w of q.stale.all(t - OFFLINE_NAV_MS, t - OFFLINE_IDLE_MS)) {
    q.markOffline.run(w.id);
    fanOut(w.id, 'offline', { childId: w.id, name: w.name, lastHeartbeat: w.last_heartbeat, navigating: !!w.navigating });
    if (w.navigating) {
      // Mid-trip silence is an alarm in its own right, with Acknowledge and re-push like the others.
      const secs = Math.round((t - w.last_heartbeat) / 1000);
      const last = q.lastLocation.get(w.id);
      addEvent(w, {
        device: 'RELAY', seq: t, type: 'OFFLINE', batteryPct: null, occurredAt: t,
        lat: last?.lat ?? null, lon: last?.lon ?? null,
        message: `HP ${w.name} tidak mengirim kabar selama ${secs} detik saat navigasi berjalan.` +
          (last ? ' Peta menunjukkan lokasi terakhir.' : ''),
      });
    }
    console.log(`child ${w.id} offline${w.navigating ? ' during a trip' : ''}`);
  }
  for (const e of q.unacked.all(...ALARM_TYPES, MAX_ESCALATIONS, t, ESCALATE_AFTER_MS, t - REPLAY_WINDOW_MS)) {
    q.escalate.run(e.id);
    const w = q.childById.get(e.child_id);
    fanOut(e.child_id, 'event', { ...eventJson(q.eventById.get(e.id)), childName: w?.name, escalated: true }, e.id);
    console.log(`event ${e.id} escalated`);
  }
  if (t - lastPurge > 3600_000) {
    lastPurge = t;
    const { changes } = q.purgeLocations.run(t - LOCATION_TTL_MS);
    if (changes) console.log(`purged ${changes} old location fixes`);
  }
}, TICK_MS).unref();

await initPush();
server.listen(PORT, () => console.log(`relay listening on :${PORT}, db ${DB_PATH}${LAN_ONLY ? ', local network only' : ''}`));
