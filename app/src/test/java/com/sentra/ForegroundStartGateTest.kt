package com.sentra

import com.sentra.care.ForegroundStartGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stop during startup must wait for startForeground(), or Android kills the app. */
class ForegroundStartGateTest {
    private val gate = ForegroundStartGate()

    @Test fun stopDuringStartupIsLeftToTheService() {
        gate.onStartRequested()
        assertFalse(gate.onStopRequested())
        assertTrue(gate.onStartFinished())
    }

    @Test fun stopAfterStartupGoesAheadAtOnce() {
        gate.onStartRequested()
        assertFalse(gate.onStartFinished())
        assertTrue(gate.onStopRequested())
    }

    @Test fun startAfterAPendingStopKeepsTheService() {
        gate.onStartRequested()
        gate.onStopRequested()
        gate.onStartRequested()
        assertFalse(gate.onStartFinished())
        assertFalse(gate.onStartFinished())
    }

    @Test fun onlyTheLastOfSeveralStartsCarriesOutTheStop() {
        gate.onStartRequested()
        gate.onStartRequested()
        assertFalse(gate.onStopRequested())
        assertFalse(gate.onStartFinished())
        assertTrue(gate.onStartFinished())
    }

    @Test fun stopWithoutAStartIsSafe() {
        assertTrue(gate.onStopRequested())
    }
}
