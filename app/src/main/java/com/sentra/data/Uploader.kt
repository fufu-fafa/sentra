package com.sentra.data

/** Where queued events and fixes go so the parents see them (the relay). */
interface TelemetrySink {
    /** False until a relay address is set and this phone has registered. */
    val configured: Boolean

    /** True once the relay has the event. Uploading the same (deviceAddress, seq) twice is harmless. */
    suspend fun upload(event: EventEntity): Boolean

    /** True once the relay has the whole batch. Uploading the same fix twice is harmless. */
    suspend fun upload(locations: List<LocationEntity>): Boolean
}

/** What hasn't reached the relay yet. Everything is written here first, so nothing is lost offline. */
interface Outbox {
    /** Oldest first. */
    suspend fun pendingEvents(): List<EventEntity>
    suspend fun markEventUploaded(id: Long, at: Long)

    /** Oldest first, at most [limit]. */
    suspend fun pendingLocations(limit: Int): List<LocationEntity>
    suspend fun deleteLocationsThrough(id: Long)
}

/** Sends the outbox to the relay, oldest first, stopping at the first failure so order is kept. */
class Uploader(private val outbox: Outbox, private val sink: TelemetrySink) {
    data class Result(val events: Int, val locations: Int, val complete: Boolean)

    suspend fun flush(now: () -> Long = System::currentTimeMillis): Result {
        if (!sink.configured) return Result(0, 0, false)
        var events = 0
        for (e in outbox.pendingEvents()) {
            if (!sink.upload(e)) return Result(events, 0, false)
            outbox.markEventUploaded(e.id, now())
            events++
        }
        var locations = 0
        while (true) {
            val batch = outbox.pendingLocations(BATCH)
            if (batch.isEmpty()) break
            if (!sink.upload(batch)) return Result(events, locations, false)
            outbox.deleteLocationsThrough(batch.last().id)
            locations += batch.size
            if (batch.size < BATCH) break
        }
        return Result(events, locations, true)
    }

    companion object {
        /** Fixes per request; the relay takes up to 500. */
        const val BATCH = 200
    }
}
