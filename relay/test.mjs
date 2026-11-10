// End-to-end check of the relay against a throwaway database:
//   node --disable-warning=ExperimentalWarning test.mjs
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pushPayload } from './push.mjs';

const PORT = 18788;
const base = `http://127.0.0.1:${PORT}`;
// Short timers so the 60-second offline alarm and the re-push can be watched in a few seconds.
const OFFLINE_NAV_MS = 1500, ESCALATE_AFTER_MS = 1200;
const server = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', new URL('./server.mjs', import.meta.url).pathname], {
  env: {
    ...process.env, PORT: String(PORT), DB_PATH: join(tmpdir(), `sentra-relay-test-${process.pid}.db`),
    OFFLINE_NAV_MS: String(OFFLINE_NAV_MS), OFFLINE_IDLE_MS: '600000', TICK_MS: '200',
    ESCALATE_AFTER_MS: String(ESCALATE_AFTER_MS), FIREBASE_KEY: '/nonexistent',
  },
  stdio: ['ignore', 'pipe', 'inherit'],
});
await new Promise((ok) => server.stdout.once('data', ok));

async function api(method, path, token, body) {
  const r = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, json: await r.json() };
}

/** Collects SSE messages from the parent stream. */
async function listen(token, lastEventId) {
  const ctrl = new AbortController();
  const r = await fetch(base + '/v1/stream', {
    headers: { Authorization: `Bearer ${token}`, ...(lastEventId ? { 'Last-Event-ID': String(lastEventId) } : {}) },
    signal: ctrl.signal,
  });
  const got = [];
  (async () => {
    let buf = '';
    try {
      for await (const chunk of r.body) {
        buf += Buffer.from(chunk).toString();
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i); buf = buf.slice(i + 2);
          const type = /^event: (.+)$/m.exec(block)?.[1];
          const data = /^data: (.+)$/m.exec(block)?.[1];
          if (type) got.push({ type, data: JSON.parse(data) });
        }
      }
    } catch { /* aborted */ }
  })();
  return { got, of: (type) => got.filter((m) => m.type === type), close: () => ctrl.abort() };
}
const wait = (ms) => new Promise((ok) => setTimeout(ok, ms));
/** Polls until [check] returns something truthy. */
async function until(check, what, timeoutMs = 4000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = check();
    if (v) return v;
    if (Date.now() > end) assert.fail(`timed out waiting for ${what}`);
    await wait(50);
  }
}
const DEVICE = 'AA:BB:CC:00:11:22';
const event = (seq, type, extra = {}) => ({
  deviceAddress: DEVICE, seq, type, batteryPct: 80, occurredAt: Date.now(), message: `${type} — test`, ...extra,
});
// Around Gedung Sate, Bandung, as in the team's exercise sheet.
const fix = (at, i) => ({ at, lat: -6.9025 - i * 0.0001, lon: 107.6188, acc: 6, speed: 1.2, bearing: 180 });

try {
  // --- Registration, linking and the web page: unchanged from fall-bracelet ---------------------
  const page = await fetch(base + '/');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /text\/html/);
  assert.match(await page.text(), /SENTRA Care/, 'web page served');

  const health = (await api('GET', '/v1/health')).json;
  assert.equal(health.service, 'sentra-relay');
  assert.match(health.relayId, /^[0-9a-f]{16}$/);

  const w = (await api('POST', '/v1/wearers', null, { name: 'Anak 1' })).json;
  assert.ok(w.token);

  assert.equal((await api('POST', '/v1/link', null, { code: '000000', name: 'X' })).status, 404, 'bad code refused');
  const invite = (await api('POST', '/v1/invites', w.token)).json;
  const c = (await api('POST', '/v1/link', null, { code: invite.code, name: 'Ibu' })).json;
  assert.ok(c.token);
  assert.equal(c.wearer.name, 'Anak 1');
  assert.equal((await api('POST', '/v1/link', null, { code: invite.code, name: 'Y' })).status, 404, 'invite is single use');
  assert.equal((await api('GET', '/v1/caregivers', w.token)).json.caregivers.length, 1);
  assert.equal((await api('GET', '/v1/dashboard', w.token)).status, 401, 'child token is not a parent token');

  // --- Events: idempotent, alarms flagged, acknowledgements reach the child ----------------------
  const s = await listen(c.token);
  await wait(200);
  const start = (await api('POST', '/v1/events', w.token, event(1, 'NAV_START'))).json;
  assert.equal(start.duplicate, false);
  assert.equal((await api('POST', '/v1/events', w.token, event(1, 'NAV_START'))).json.duplicate, true, 'resend is a duplicate');
  const off = (await api('POST', '/v1/events', w.token, event(2, 'KELUAR_RUTE', { lat: -6.9031, lon: 107.6189 }))).json;
  await api('POST', '/v1/heartbeat', w.token, {
    status: { navigating: true, sleeveConnected: true, navState: 'OFF_ROUTE', routeName: 'Rumah ke sekolah', nextPoint: 2, distanceM: 31 },
  });
  await until(() => s.of('status').length, 'the status message');
  assert.equal(s.of('event').length, 2, 'parent told once per event, not twice');
  assert.equal(s.of('event')[0].data.alarm, false, 'NAV_START is not an alarm');
  const alarm = s.of('event')[1].data;
  assert.equal(alarm.alarm, true, 'KELUAR_RUTE is an alarm');
  assert.equal(alarm.wearerName, 'Anak 1');
  assert.equal(alarm.lat, -6.9031);
  assert.equal(s.of('status')[0].data.status.routeName, 'Rumah ke sekolah');
  assert.equal((await api('POST', '/v1/events', w.token, event(3, 'OFFLINE'))).status, 400, 'a phone cannot raise OFFLINE');

  await api('POST', `/v1/events/${off.id}/ack`, c.token);
  await until(() => s.of('ack').some((m) => m.data.by === 'Ibu'), 'the acknowledgement');
  const hb = (await api('POST', '/v1/heartbeat', w.token, { status: { navigating: true }, acksSince: 0 })).json;
  assert.equal(hb.acks[0].acked_by, 'Ibu', 'child phone learns who saw it');
  assert.equal(hb.caregivers, 1);

  // --- Locations: stored once, newest one fanned out, latest fix on the dashboard ----------------
  const t0 = Date.now() - 10_000;
  const batch = [fix(t0, 0), fix(t0 + 1000, 1), fix(t0 + 2000, 2)];
  assert.equal((await api('POST', '/v1/locations', w.token, { points: batch })).json.stored, 3);
  assert.equal((await api('POST', '/v1/locations', w.token, { points: batch })).json.stored, 0, 'resent fixes are not stored twice');
  assert.equal((await api('POST', '/v1/locations', w.token, { points: [{ at: t0, lat: 123, lon: 0 }, fix(t0 + 3000, 3)] })).json.stored, 1, 'a bad fix is skipped, the rest kept');
  assert.equal((await api('POST', '/v1/locations', w.token, { points: [] })).status, 400);
  assert.equal((await api('POST', '/v1/locations', c.token, { points: batch })).status, 401, 'only the child phone uploads');
  await until(() => s.of('location').length === 2, 'the location messages');
  assert.equal(s.of('location')[0].data.at, t0 + 2000, 'the newest fix of the batch is the one sent live');
  assert.equal(s.of('location')[1].data.lat, fix(0, 3).lat);
  // A late batch of older fixes fills in the track but must not move the marker backwards.
  await api('POST', '/v1/locations', w.token, { points: [fix(t0 - 5000, 9)] });
  await wait(200);
  assert.equal(s.of('location').length, 2, 'older fixes are not sent live');

  let dash = (await api('GET', '/v1/dashboard', c.token)).json;
  assert.equal(dash.wearers[0].location.at, t0 + 3000, 'dashboard has the latest fix');
  assert.equal(dash.wearers[0].navigating, true);
  assert.equal(dash.wearers[0].events.find((e) => e.id === off.id).ackedBy, 'Ibu');

  const track = (await api('GET', `/v1/wearers/${w.wearerId}/track?since=${t0 - 60_000}`, c.token)).json.points;
  assert.deepEqual(track.map((p) => p.at), [t0 - 5000, t0, t0 + 1000, t0 + 2000, t0 + 3000], 'track is oldest first');
  assert.equal((await api('GET', `/v1/wearers/${w.wearerId}/track?limit=2`, c.token)).json.points[1].at, t0 + 3000, 'limit keeps the newest part');
  assert.equal((await api('GET', `/v1/wearers/${w.wearerId}/track`, w.token)).status, 401);
  assert.equal((await api('GET', '/v1/wearers/999/track', c.token)).status, 404, 'no track for a child you do not follow');

  // --- Alarms that end by themselves ---------------------------------------------------------
  const lost = (await api('POST', '/v1/events', w.token, event(4, 'TERPUTUS'))).json;
  await api('POST', '/v1/events', w.token, event(5, 'TERSAMBUNG'));
  await until(() => s.of('ack').some((m) => m.data.eventId === lost.id), 'TERPUTUS to be resolved by TERSAMBUNG');
  assert.match(s.of('ack').find((m) => m.data.eventId === lost.id).data.by, /tersambung/);

  // --- Re-push: an alarm nobody acknowledges is sent again -----------------------------------
  const off2 = (await api('POST', '/v1/events', w.token, event(6, 'KELUAR_RUTE'))).json;
  const again = await until(() => s.of('event').find((m) => m.data.id === off2.id && m.data.escalated), 'the re-push');
  assert.equal(again.data.escalations, 1);
  await api('POST', '/v1/events', w.token, event(7, 'KEMBALI_RUTE'));
  await until(() => s.of('ack').some((m) => m.data.eventId === off2.id), 'KELUAR_RUTE to be resolved by KEMBALI_RUTE');

  // --- 60-second offline alarm (1.5 s here): silence during a trip -----------------------------
  await api('POST', '/v1/heartbeat', w.token, { status: { navigating: true, sleeveConnected: true } });
  const silentSince = Date.now();
  const offline = await until(() => s.of('event').find((m) => m.data.type === 'OFFLINE'), 'the OFFLINE alarm', 5000);
  assert.ok(Date.now() - silentSince >= OFFLINE_NAV_MS, 'not raised before the limit');
  assert.equal(offline.data.alarm, true);
  assert.equal(offline.data.lat, fix(0, 3).lat, 'carries the last known position');
  assert.ok(s.of('offline').some((m) => m.data.navigating === true));
  assert.equal((await api('GET', '/v1/dashboard', c.token)).json.wearers[0].offline, true);
  await wait(700);
  assert.equal(s.of('event').filter((m) => m.data.type === 'OFFLINE' && !m.data.escalated).length, 1, 'raised once per outage');

  // Back in touch: the parents are told, and the alarm stops by itself.
  await api('POST', '/v1/locations', w.token, { points: [fix(Date.now(), 4)] });
  await until(() => s.of('online').length, 'the online message');
  await until(() => s.of('ack').some((m) => m.data.eventId === offline.data.id), 'OFFLINE to be resolved');
  assert.equal((await api('GET', '/v1/dashboard', c.token)).json.wearers[0].offline, false);

  // An idle phone (no trip) that goes quiet is not an alarm within the trip limit.
  await api('POST', '/v1/heartbeat', w.token, { status: { navigating: false, sleeveConnected: false } });
  const before = s.of('offline').length;
  await wait(OFFLINE_NAV_MS + 600);
  assert.equal(s.of('offline').length, before, 'idle silence uses the 15-minute limit');
  s.close();

  // --- Missed while disconnected: replayed with Last-Event-ID ----------------------------------
  const arrived = (await api('POST', '/v1/events', w.token, event(8, 'ARRIVED'))).json;
  const s2 = await listen(c.token, off2.id);
  await until(() => s2.of('event').some((m) => m.data.id === arrived.id && m.data.replay), 'the missed event');
  s2.close();

  // --- Push rules -------------------------------------------------------------------------------
  const pushed = (type, alarmFlag) => pushPayload('event', { id: 1, wearerId: 1, type, alarm: alarmFlag, message: 'x' });
  assert.equal(pushed('KELUAR_RUTE', true).urgent, true);
  assert.equal(pushed('TERPUTUS', true).urgent, true);
  assert.equal(pushed('OFFLINE', true).urgent, true);
  assert.equal(pushed('ARRIVED', false).urgent, false, 'ARRIVED is normal priority');
  assert.equal(pushed('BELOK_KIRI', false), null, 'turns do not wake the phone');
  assert.equal(pushPayload('location', { wearerId: 1 }), null, 'locations only go down the stream');

  await api('DELETE', `/v1/caregivers/${c.caregiverId}`, w.token);
  assert.equal((await api('GET', '/v1/dashboard', c.token)).json.wearers.length, 0, 'unlinked parent sees nothing');

  console.log('relay tests passed');
} finally {
  server.kill();
}
