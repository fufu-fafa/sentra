package com.sentra

import com.sentra.ble.BleScanner
import com.sentra.ble.Intensity
import com.sentra.ble.SentraProtocol
import com.sentra.ble.SleeveCommand
import com.sentra.ble.SleeveMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Bab 8 contract: every message of Tabel 19 and Tabel 20, exactly as the firmware has it. */
class SentraProtocolTest {
    @Test fun uuidsMatchTabel18() {
        assertEquals("3d0dc036-a075-4f06-ba4a-9991669c7a8c", SentraProtocol.SERVICE.toString())
        assertEquals("003abf21-74a3-465f-b192-017cea87380b", SentraProtocol.CMD.toString())
        assertEquals("c8ae039a-04b6-42d5-9b16-4ea56ce8eb1b", SentraProtocol.STATUS.toString())
    }

    @Test fun commandsAreTheTextsOfTabel19() {
        assertEquals(
            listOf("FORWARD", "LEFT", "RIGHT", "STOP", "ARRIVED", "IDLE", "TEST", "LOW", "HIGH", "PING"),
            SleeveCommand.entries.map { String(SentraProtocol.encode(it), Charsets.US_ASCII) },
        )
    }

    @Test fun everyCommandFitsOnePacket() {
        SleeveCommand.entries.forEach { assertTrue(SentraProtocol.encode(it).size <= SentraProtocol.MAX_MESSAGE_LENGTH) }
        assertArrayEquals("PING".toByteArray(), SentraProtocol.encode(SleeveCommand.PING))
    }

    @Test fun distance() {
        assertEquals(SleeveMessage.Distance(85), SentraProtocol.parse("D:85"))
        assertFalse((SentraProtocol.parse("D:85") as SleeveMessage.Distance).clear)
        assertTrue((SentraProtocol.parse("D:999") as SleeveMessage.Distance).clear)
        assertEquals(SleeveMessage.Distance(3), SentraProtocol.parse("D:3".toByteArray()))
    }

    @Test fun ackAndError() {
        assertEquals(SleeveMessage.Ack("LEFT"), SentraProtocol.parse("ACK:LEFT"))
        assertEquals(SleeveMessage.Error("HELLO"), SentraProtocol.parse("ERR:HELLO"))
    }

    @Test fun button() {
        assertEquals(SleeveMessage.Button(long = false, intensity = null), SentraProtocol.parse("BTN:SHORT"))
        assertEquals(SleeveMessage.Button(long = true, intensity = Intensity.HIGH), SentraProtocol.parse("BTN:LONG,HIGH"))
        assertEquals(SleeveMessage.Button(long = true, intensity = Intensity.LOW), SentraProtocol.parse("BTN:LONG,LOW"))
    }

    @Test fun readyIsTheValueBeforeTheFirstNotification() {
        assertEquals(SleeveMessage.Ready, SentraProtocol.parse("READY"))
    }

    @Test fun caseWhitespaceAndTrailingZerosDoNotMatter() {
        assertEquals(SleeveMessage.Ack("STOP"), SentraProtocol.parse(" ack:stop\r\n"))
        assertEquals(SleeveMessage.Distance(120), SentraProtocol.parse("D:120\u0000\u0000"))
    }

    @Test fun anythingElseIsUnknownNotACrash() {
        assertEquals(SleeveMessage.Unknown("D:abc"), SentraProtocol.parse("D:abc"))
        assertEquals(SleeveMessage.Unknown("D:-5"), SentraProtocol.parse("D:-5"))
        assertEquals(SleeveMessage.Unknown(""), SentraProtocol.parse(""))
        assertEquals(SleeveMessage.Unknown("BTN:LONG"), SentraProtocol.parse("BTN:LONG"))
    }

    @Test fun scanMatchesAnySentraSleeveOrOneByName() {
        assertTrue(BleScanner.matches("SENTRA-01", hasService = false, exactName = ""))
        assertTrue(BleScanner.matches(null, hasService = true, exactName = ""))
        assertFalse(BleScanner.matches("FallBracelet", hasService = false, exactName = ""))
        assertTrue(BleScanner.matches("SENTRA-01", hasService = true, exactName = "sentra-01"))
        assertFalse(BleScanner.matches("SENTRA-02", hasService = true, exactName = "SENTRA-01"))
        assertFalse(BleScanner.matches(null, hasService = true, exactName = "SENTRA-01"))
    }
}
