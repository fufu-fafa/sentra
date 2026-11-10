package com.sentra.relay

import com.sentra.data.AppSettings
import com.sentra.data.EventEntity
import com.sentra.data.LocationEntity
import com.sentra.data.SettingsRepository
import com.sentra.data.TelemetrySink
import org.json.JSONArray
import org.json.JSONObject

data class LinkedParent(val id: Long, val name: String, val linkedAt: Long)
data class Invite(val code: String, val expiresAt: Long)
data class Ack(val seq: Long, val by: String, val at: Long)
data class HeartbeatReply(val acks: List<Ack>, val parents: Int)

/**
 * The child phone's side of the relay: registration, event and location upload, heartbeats,
 * invites. The relay's API keeps fall-bracelet's names: the child's phone is a "wearer" there and
 * a parent a "caregiver".
 */
class ChildRelay(
    private val settings: SettingsRepository,
    private val current: () -> AppSettings,
) : TelemetrySink {

    override val configured: Boolean
        get() = current().relayUrl.isNotBlank() && current().wearerToken != null

    private fun client(): RelayClient {
        val s = current()
        return RelayClient(s.relayUrl, s.wearerToken ?: throw RelayException(401, "belum terdaftar di relay"))
    }

    /** Registers this phone as a child's phone the first time a relay address is set. */
    suspend fun ensureRegistered() {
        val s = current()
        if (s.relayUrl.isBlank() || s.wearerToken != null) return
        val r = RelayClient(s.relayUrl, null).post("/v1/wearers", JSONObject().put("name", s.childName.ifBlank { "Anak" }))
        settings.setWearerToken(r.getString("token"))
    }

    suspend fun rename(name: String) {
        if (configured && name.isNotBlank()) client().put("/v1/wearers/me", JSONObject().put("name", name))
    }

    override suspend fun upload(event: EventEntity): Boolean = runCatching {
        client().post("/v1/events", eventJson(event))
    }.onFailure { forgetIfUnknown(it) }.isSuccess

    override suspend fun upload(locations: List<LocationEntity>): Boolean = runCatching {
        client().post("/v1/locations", locationsJson(locations))
    }.onFailure { forgetIfUnknown(it) }.isSuccess

    /** Tells the parents this phone is alive and what it is doing; the reply has acknowledgements made since [acksSince]. */
    suspend fun heartbeat(status: JSONObject, acksSince: Long): HeartbeatReply {
        val r = try {
            client().post("/v1/heartbeat", JSONObject().put("status", status).put("acksSince", acksSince))
        } catch (e: RelayException) {
            forgetIfUnknown(e)
            throw e
        }
        val acks = r.getJSONArray("acks")
        return HeartbeatReply(
            acks = List(acks.length()) { i ->
                val a = acks.getJSONObject(i)
                Ack(a.getLong("seq"), a.getString("acked_by"), a.getLong("acked_at"))
            },
            parents = r.optInt("caregivers"),
        )
    }

    suspend fun invite(): Invite {
        val r = client().post("/v1/invites")
        return Invite(r.getString("code"), r.getLong("expiresAt"))
    }

    suspend fun parents(): List<LinkedParent> {
        val a = client().get("/v1/caregivers").getJSONArray("caregivers")
        return List(a.length()) { i ->
            val c = a.getJSONObject(i)
            LinkedParent(c.getLong("id"), c.getString("name"), c.getLong("linkedAt"))
        }
    }

    suspend fun removeParent(id: Long) {
        client().delete("/v1/caregivers/$id")
    }

    /**
     * 401 means this relay doesn't know our key: its database was reset, or the address now leads
     * to a different relay. Drop the key so the next round registers again instead of failing forever.
     */
    private suspend fun forgetIfUnknown(error: Throwable) {
        if ((error as? RelayException)?.status == 401) settings.setWearerToken(null)
    }

    companion object {
        fun eventJson(e: EventEntity): JSONObject = JSONObject()
            .put("deviceAddress", e.deviceAddress)
            .put("seq", e.id)
            .put("type", e.type)
            .put("batteryPct", e.batteryPct)
            .put("occurredAt", e.occurredAt)
            .put("message", e.message)
            .apply { if (e.lat != null && e.lon != null) put("lat", e.lat).put("lon", e.lon) }

        fun locationsJson(locations: List<LocationEntity>): JSONObject = JSONObject().put(
            "points",
            JSONArray().apply {
                for (l in locations) {
                    put(
                        JSONObject().put("at", l.at).put("lat", l.lat).put("lon", l.lon)
                            .put("acc", l.acc.toDouble()).put("speed", l.speed.toDouble()).put("bearing", l.bearing.toDouble())
                    )
                }
            },
        )
    }
}
