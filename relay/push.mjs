// Firebase Cloud Messaging for the parents' phones (SENTRA Care). The relay keeps sending everything
// down the Server-Sent Events stream as well, so with no key, no firebase-admin or a failed push,
// an open SENTRA Care still gets every alarm.
//
// The service-account key stays on the server (FIREBASE_KEY, default ./firebase-service-account.json,
// chmod 600) and is never logged or committed.

import { readFileSync, existsSync } from 'node:fs';

const KEY_PATH = process.env.FIREBASE_KEY ?? new URL('./firebase-service-account.json', import.meta.url).pathname;

/** Which stream messages are worth waking a phone for; the rest only update an open dashboard. */
// KELUAR_RUTE, TERPUTUS and OFFLINE are alarms (data.alarm, high priority); the others arrive as
// normal-priority notifications. Turns and reconnects only show in the history.
const PUSH_TYPES = new Set(['KELUAR_RUTE', 'TERPUTUS', 'OFFLINE', 'ARRIVED', 'NAV_START', 'TEST']);

/** Tokens FCM says will never work again; anything else (network, quota) is worth retrying later. */
const DEAD_TOKEN = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

let messaging = null;

/** Loads firebase-admin and the key. Returns false (and the relay runs SSE-only) if either is missing. */
export async function initPush() {
  if (!existsSync(KEY_PATH)) {
    console.log('push: no Firebase key, parents get alarms over the live stream only');
    return false;
  }
  try {
    const { initializeApp, cert } = await import('firebase-admin/app');
    const { getMessaging } = await import('firebase-admin/messaging');
    const app = initializeApp({ credential: cert(JSON.parse(readFileSync(KEY_PATH, 'utf8'))) });
    messaging = getMessaging(app);
    console.log('push: Firebase Cloud Messaging on');
    return true;
  } catch (err) {
    console.log(`push: Firebase unavailable (${err.code ?? err.message}), live stream only`);
    return false;
  }
}

/** FCM data payloads are string maps. */
const strings = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v != null).map(([k, v]) => [k, String(v)]));

/** The data message for one stream message, or null if it shouldn't wake a phone. */
export function pushPayload(type, data) {
  switch (type) {
    case 'event':
      if (!PUSH_TYPES.has(data.type) || data.ackedAt) return null;
      return {
        urgent: data.alarm === true,
        data: strings({
          kind: 'event', id: data.id, wearerId: data.wearerId, wearerName: data.wearerName, type: data.type,
          batteryPct: data.batteryPct, occurredAt: data.occurredAt, lat: data.lat, lon: data.lon, message: data.message,
          escalated: data.escalated ? 1 : 0, escalations: data.escalations,
        }),
      };
    case 'offline':
      // Mid-trip silence also arrives as an OFFLINE alarm event; this one only updates the status.
      return { urgent: false, data: strings({ kind: 'offline', wearerId: data.wearerId, name: data.name, navigating: data.navigating ? 1 : 0 }) };
    case 'online':
      return { urgent: false, data: strings({ kind: 'online', wearerId: data.wearerId, name: data.name }) };
    // Stops the alarm ringing on the other parents' phones. Normal priority: Android stops
    // honouring high priority for an app whose high-priority pushes don't show a notification.
    case 'ack':
      return { urgent: false, data: strings({ kind: 'ack', eventId: data.eventId, by: data.by }) };
    default:
      return null;
  }
}

/**
 * Sends one stream message to the given FCM tokens. [dropToken] is called for tokens FCM rejects
 * for good. Never throws: a failed push must not break the request that caused it.
 */
export async function push(tokens, type, data, dropToken) {
  if (!messaging || tokens.length === 0) return;
  const p = pushPayload(type, data);
  if (!p) return;
  try {
    const r = await messaging.sendEachForMulticast({
      tokens,
      data: p.data,
      android: {
        // High priority is what lets a dozing phone wake for an alarm.
        priority: p.urgent ? 'high' : 'normal',
        ttl: (p.urgent ? 3600 : 6 * 3600) * 1000,
      },
    });
    r.responses.forEach((res, i) => {
      if (res.success) return;
      const code = res.error?.code;
      if (DEAD_TOKEN.has(code)) dropToken(tokens[i]);
      else console.log(`push: ${type} not delivered (${code})`);
    });
  } catch (err) {
    console.log(`push: ${type} failed (${err.code ?? err.message})`);
  }
}
