package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Screen lock -> finding -> verdict (M1: advisory WARN, never BLOCK). */
class ScreenLockRuleTest {
    @Test
    fun findingPerKeyguardAnswer() {
        val set = ScreenLockRule.finding(deviceSecure = true)
        assertEquals(ScreenLockRule.ID_SET, set.id)
        assertEquals(Level.WARN, set.level)
        assertEquals("Screen lock is set - remove it before switching", set.title)

        val off = ScreenLockRule.finding(deviceSecure = false)
        assertEquals(ScreenLockRule.ID_OFF, off.id)
        assertEquals(Level.OK, off.level)

        val unknown = ScreenLockRule.finding(deviceSecure = null)
        assertEquals(ScreenLockRule.ID_UNKNOWN, unknown.id)
        assertEquals(Level.WARN, unknown.level)
    }

    @Test
    fun screenLockSetMakesAnOtherwiseCompatibleF8uWarning() {
        val r = CompatEvaluator.evaluate(Fixtures.facts(screenLock = true))
        assertEquals(Verdict.WARNING, r.verdict)
        assertEquals(Level.WARN, r.find(ScreenLockRule.ID_SET)?.level)
        assertEquals(1, r.count(Level.WARN))
        // M1 is advisory: the lock is BLOCK only at switch time (gate G16, M7).
        assertEquals(0, r.count(Level.BLOCK))
        assertNull(r.find(ScreenLockRule.ID_OFF))
    }

    @Test
    fun noScreenLockKeepsF8uCompatible() {
        val r = CompatEvaluator.evaluate(Fixtures.facts(screenLock = false))
        assertEquals(Verdict.COMPATIBLE, r.verdict)
        assertEquals(Level.OK, r.find(ScreenLockRule.ID_OFF)?.level)
        assertNull(r.find(ScreenLockRule.ID_SET))
    }

    @Test
    fun unreadableScreenLockWarns() {
        val r = CompatEvaluator.evaluate(Fixtures.facts(screenLock = null))
        assertEquals(Verdict.WARNING, r.verdict)
        assertEquals(Level.WARN, r.find(ScreenLockRule.ID_UNKNOWN)?.level)
    }

    @Test
    fun blockStillWinsOverTheScreenLockWarning() {
        val r = CompatEvaluator.evaluate(Fixtures.facts(screenLock = true, abis64 = emptyList()))
        assertEquals(Verdict.NOT_SUPPORTED, r.verdict)
        assertEquals(Level.BLOCK, r.sorted.first().level)
        assertEquals(Level.WARN, r.find(ScreenLockRule.ID_SET)?.level)
    }
}
