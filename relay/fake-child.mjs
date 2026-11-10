// A pretend child phone for trying the relay and SENTRA Care without the app, a sleeve or GPS.
// Registers as a child, prints an invite code, then walks a route around Gedung Sate (Bandung)
// sending what the real app sends: fixes every second, a heartbeat every 20 s and the trip events.
//
//   node fake-child.mjs [relay URL]            default http://127.0.0.1:8787
//   node fake-child.mjs --offroute             leaves the route half-way (KELUAR_RUTE alarm), then returns
//   node fake-child.mjs --drop                 sleeve drops for 15 s mid-trip (TERPUTUS alarm)
//   node fake-child.mjs --silent               stops sending mid-trip (the relay raises OFFLINE after 60 s)
//
// The token is kept in .fake-child.json so a rerun is the same child and keeps its linked parents.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const args = process.argv.slice(2);
const flag = (f) => args.includes(f);
const base = (args.find((a) => !a.startsWith('--')) ?? 'http://127.0.0.1:8787').replace(/\/$/, '');
const STATE = new URL('./.fake-child.json', import.meta.url);
const ROUTE = [
  [-6.90250, 107.61880], [-6.90250, 107.61960], [-6.90320, 107.61960], [-6.90320, 107.62040], [-6.90390, 107.62040],
];
const SPEED = 4; // m/s: faster than a child, so a demo takes about a minute and a half

async function api(method, path, token, body) {
  const r = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`${method} ${path}: ${json.error ?? r.status}`);
  return json;
}
const wait = (ms) => new Promise((ok) => setTimeout(ok, ms));
const metres = (a, b) => Math.hypot((b[1] - a[1]) * 111320 * Math.cos(((a[0] + b[0]) / 2) * Math.PI / 180), (b[0] - a[0]) * 110540);

let state = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {};
if (state.base !== base || !state.token) {
  const w = await api('POST', '/v1/wearers', null, { name: 'Anak (simulasi)' });
  state = { base, token: w.token, seq: 0 };
}
const save = () => writeFileSync(STATE, JSON.stringify(state));
save();
const token = state.token;

const invite = await api('POST', '/v1/invites', token);
console.log(`\nKode undangan: ${invite.code}   (berlaku 15 menit)`);
console.log(`Buka ${base}/ di browser, atau mode Orang tua di aplikasi, lalu ketik kodenya.\n`);

let pos = ROUTE[0], target = 1, navState = 'NAVIGATING', sleeve = true;
const status = (navigating = true) => ({
  navigating, sleeveConnected: sleeve, navState: navigating ? navState : 'IDLE', routeName: 'Keliling Gedung Sate',
  route: navigating ? ROUTE : undefined, nextPoint: target + 1, totalPoints: ROUTE.length,
  distanceM: Math.round(metres(pos, ROUTE[Math.min(target, ROUTE.length - 1)])), batteryPct: 76, obstacleCm: 999, simulated: true,
});
const event = (type, message) => {
  console.log(`  ${type}: ${message}`);
  return api('POST', '/v1/events', token, {
    deviceAddress: 'SIMULASI', seq: ++state.seq, type, batteryPct: 76, occurredAt: Date.now(), lat: pos[0], lon: pos[1], message,
  }).then(save);
};
const heartbeat = (navigating) => api('POST', '/v1/heartbeat', token, { status: status(navigating) });

console.log('Menunggu 15 detik supaya sempat menghubungkan…');
await heartbeat(false);
await wait(15_000);

await event('NAV_START', 'Mulai jalan di rute Keliling Gedung Sate.');
await heartbeat(true);
let lastBeat = Date.now(), detour = flag('--offroute') ? 'pending' : null, drop = flag('--drop') ? 'pending' : null;

while (target < ROUTE.length) {
  const goal = ROUTE[target];
  const d = metres(pos, goal);
  if (d < 6) {
    if (target === ROUTE.length - 1) break;
    const turn = ['BELOK_KANAN', 'BELOK_KIRI'][target % 2]; // the sample route zigzags
    await event(turn, `Belok ${turn === 'BELOK_KANAN' ? 'kanan' : 'kiri'} di titik ${target + 1}.`);
    target++;
    continue;
  }
  const step = Math.min(SPEED, d) / d;
  pos = [pos[0] + (goal[0] - pos[0]) * step, pos[1] + (goal[1] - pos[1]) * step];

  // Half-way along the second leg: the scenarios the parents' alarms exist for.
  if (target === 2 && d < 45) {
    if (detour === 'pending') {
      detour = 'out';
      navState = 'OFF_ROUTE';
      for (let i = 0; i < 8; i++) { pos = [pos[0], pos[1] - 0.00005]; await api('POST', '/v1/locations', token, { points: [fix()] }); await wait(1000); }
      await event('KELUAR_RUTE', 'Keluar rute, sekitar 40 m dari jalur. Sleeve memberi tanda berhenti.');
      await heartbeat(true);
      await wait(20_000);
      for (let i = 0; i < 8; i++) { pos = [pos[0], pos[1] + 0.00005]; await api('POST', '/v1/locations', token, { points: [fix()] }); await wait(1000); }
      navState = 'NAVIGATING';
      await event('KEMBALI_RUTE', 'Sudah kembali ke rute.');
    }
    if (drop === 'pending') {
      drop = 'done';
      sleeve = false;
      await event('TERPUTUS', 'Sleeve terputus dari HP saat navigasi berjalan.');
      await heartbeat(true);
      await wait(15_000);
      sleeve = true;
      await event('TERSAMBUNG', 'Sleeve tersambung lagi.');
    }
    if (flag('--silent')) {
      console.log('  (diam: tidak mengirim apa pun; relay akan membunyikan OFFLINE setelah 60 detik)');
      await wait(90_000);
      console.log('  (kembali mengirim)');
    }
  }

  await api('POST', '/v1/locations', token, { points: [fix()] });
  if (Date.now() - lastBeat > 20_000) { await heartbeat(true); lastBeat = Date.now(); }
  await wait(1000);
}

navState = 'ARRIVED';
await event('ARRIVED', 'Sudah sampai di tujuan rute Keliling Gedung Sate.');
await heartbeat(false);
console.log('\nSelesai.');

function fix() {
  return { at: Date.now(), lat: pos[0], lon: pos[1], acc: 5 + Math.random() * 3, speed: SPEED, bearing: 0 };
}
