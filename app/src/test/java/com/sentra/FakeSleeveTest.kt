package com.sentra

import com.sentra.ble.FakeSleeve
import com.sentra.ble.Intensity
import com.sentra.ble.LinkState
import com.sentra.ble.SleeveCommand
import com.sentra.ble.SleeveMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pretend sleeve must behave like the firmware of §7.5, or tests against it prove nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeSleeveTest {
    private fun TestScope.sleeve(): Pair<FakeSleeve, MutableList<SleeveMessage>> {
        val s = FakeSleeve(backgroundScope) { testScheduler.currentTime }
        val got = mutableListOf<SleeveMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.messages.collect { got += it } }
        return s to got
    }

    private fun TestScope.connected(): Pair<FakeSleeve, MutableList<SleeveMessage>> {
        val pair = sleeve()
        pair.first.connect()
        advanceTimeBy(FakeSleeve.CONNECT_DELAY_MS + 1)
        return pair
    }

    @Test fun connectsThenReportsDistanceFourTimesASecond() = runTest {
        val (s, got) = sleeve()
        assertFalse(s.send(SleeveCommand.TEST)) // not connected yet
        s.connect()
        assertEquals(LinkState.CONNECTING, s.link.value)
        advanceTimeBy(FakeSleeve.CONNECT_DELAY_MS + 1)
        assertEquals(LinkState.CONNECTED, s.link.value)
        assertEquals(SleeveMessage.Ready, got.first())
        advanceTimeBy(1000)
        assertEquals(4, got.count { it is SleeveMessage.Distance })
        assertTrue((got.last() as SleeveMessage.Distance).clear)
    }

    @Test fun acksEveryCommandExceptPing() = runTest {
        val (s, got) = connected()
        assertTrue(s.send(SleeveCommand.LEFT))
        assertTrue(SleeveMessage.Ack("LEFT") in got)
        assertEquals(SleeveCommand.LEFT, s.pattern.value)
        got.clear()
        assertTrue(s.send(SleeveCommand.PING))
        assertTrue(got.none { it is SleeveMessage.Ack })
    }

    @Test fun unknownTextGetsErr() = runTest {
        val (s, got) = connected()
        s.receive("hello")
        assertTrue(SleeveMessage.Error("HELLO") in got)
    }

    @Test fun lowerCaseIsAccepted() = runTest {
        val (s, got) = connected()
        s.receive(" right\n")
        assertTrue(SleeveMessage.Ack("RIGHT") in got)
    }

    @Test fun navigationStopsAfterSixSecondsWithoutAMessage() = runTest {
        val (s, _) = connected()
        s.send(SleeveCommand.FORWARD)
        advanceTimeBy(5_500)
        assertEquals(SleeveCommand.FORWARD, s.pattern.value)
        advanceTimeBy(1_000)
        assertNull(s.pattern.value)
    }

    @Test fun pingEveryTwoSecondsKeepsNavigationAlive() = runTest {
        val (s, _) = connected()
        s.send(SleeveCommand.RIGHT)
        repeat(6) {
            advanceTimeBy(2_000)
            s.send(SleeveCommand.PING)
        }
        assertEquals(SleeveCommand.RIGHT, s.pattern.value)
    }

    @Test fun arrivedAndIdleEndThePattern() = runTest {
        val (s, _) = connected()
        s.send(SleeveCommand.STOP)
        assertEquals(SleeveCommand.STOP, s.pattern.value)
        s.send(SleeveCommand.ARRIVED)
        assertNull(s.pattern.value)
        s.send(SleeveCommand.LEFT)
        s.send(SleeveCommand.IDLE)
        assertNull(s.pattern.value)
    }

    @Test fun intensityByCommandAndByLongPress() = runTest {
        val (s, got) = connected()
        s.send(SleeveCommand.LOW)
        assertEquals(Intensity.LOW, s.intensity.value)
        s.pressButton(long = true)
        assertEquals(Intensity.HIGH, s.intensity.value)
        assertTrue(SleeveMessage.Button(long = true, intensity = Intensity.HIGH) in got)
        s.pressButton(long = false)
        assertTrue(SleeveMessage.Button(long = false, intensity = null) in got)
    }

    @Test fun aSetObstacleIsReported() = runTest {
        val (s, got) = connected()
        s.obstacleCm = 42
        advanceTimeBy(300)
        assertEquals(SleeveMessage.Distance(42), got.last())
    }

    @Test fun disconnectDropsThePatternAndRefusesCommands() = runTest {
        val (s, _) = connected()
        s.send(SleeveCommand.LEFT)
        s.disconnect()
        assertEquals(LinkState.DISCONNECTED, s.link.value)
        assertNull(s.pattern.value)
        assertFalse(s.send(SleeveCommand.LEFT))
    }
}
