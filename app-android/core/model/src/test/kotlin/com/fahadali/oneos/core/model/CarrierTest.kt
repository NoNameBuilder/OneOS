package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierTest {
    @Test
    fun theFourBundles() {
        val f8u = Bundles.F8U.facts.carrier!!
        assertEquals(CarrierInfo("KSA", "KSA", "SA", listOf("sa", "sa")), f8u)
        assertEquals(false, CarrierRules.voLteOnly(f8u))

        // AT&T on the S26 Ultra: VoLTE only (one SIM, in slot 2).
        val s26 = Bundles.S26U.facts.carrier!!
        assertEquals(CarrierInfo("ATT", "ATT", "US", listOf("", "us")), s26)
        assertEquals(true, CarrierRules.voLteOnly(s26))

        val s25 = Bundles.S25U.facts.carrier!!
        assertEquals(CarrierInfo("EUX", "INS", "DE", listOf("ru", "")), s25)
        assertEquals(false, CarrierRules.voLteOnly(s25))

        val s25Base = Bundles.S25.facts.carrier!!
        assertEquals(CarrierInfo("INS", "INS", "IN", listOf("fr")), s25Base)
        assertEquals(false, CarrierRules.voLteOnly(s25Base))
    }

    @Test
    fun rule() {
        fun volte(sales: String = "", boot: String = "", iso: String = "", sims: List<String> = emptyList()) =
            CarrierRules.voLteOnly(CarrierInfo(sales, boot, iso, sims))

        // Any ro.csc.countryiso_code=US.
        assertEquals(true, volte(sales = "XAR", iso = "US"))
        assertEquals(true, volte(iso = "us"))
        // A US carrier code with a US SIM, through either prop.
        for (code in CarrierRules.US_VOLTE_ONLY_SALES_CODES) {
            assertEquals(code, true, volte(sales = code, sims = listOf("us")))
            assertEquals(code, true, volte(boot = code, sims = listOf("", "US")))
        }
        // A US carrier phone with a foreign SIM and a non-US CSC country.
        assertEquals(false, volte(sales = "TMB", iso = "CA", sims = listOf("ca")))
        // A US carrier code, no SIM, no country: cannot tell.
        assertNull(volte(sales = "VZW"))
        // Nothing readable: cannot tell.
        assertNull(volte())
        assertEquals(false, volte(sales = "KSA", iso = "SA", sims = listOf("sa")))
    }

    @Test
    fun findingsAndTicks() {
        val s26 = Bundles.S26U.facts.carrier
        val f = CarrierRules.finding(s26)
        assertEquals(Level.WARN, f.level)
        assertEquals(CarrierRules.ID, f.id)
        assertEquals(CarrierRules.R_VOLTE_ONLY, f.reason)
        // The tick names the system; without one, "the other system" (never a fixed system name).
        assertEquals(
            "I understand phone calls, including emergency calls, won't work in the other system",
            CarrierRules.ackText(s26),
        )
        assertEquals(
            "I understand phone calls, including emergency calls, won't work in Pixel 11 Fold",
            CarrierRules.ackText(s26, "Pixel 11 Fold"),
        )
        assertFalse(CarrierRules.finding(s26, "Pixel 11 Fold").detail.contains("ColorOS"))
        assertTrue(CarrierRules.finding(s26, "Pixel 11 Fold").detail.contains("Pixel 11 Fold cannot use One UI's VoLTE"))

        assertEquals(Level.WARN, CarrierRules.finding(null).level)
        assertEquals(CarrierRules.R_UNKNOWN, CarrierRules.finding(null).reason)
        assertEquals(CarrierRules.maybeCallsAck(), CarrierRules.ackText(null))
        assertEquals(CarrierRules.maybeCallsAck(), CarrierRules.ackText(CarrierInfo()))
        assertTrue(CarrierRules.finding(null).detail.startsWith("Could not read the carrier. The other system cannot"))

        val ksa = Bundles.F8U.facts.carrier
        assertEquals(Level.INFO, CarrierRules.finding(ksa).level)
        assertNull(CarrierRules.ackText(ksa))
    }

    @Test
    fun deviceReportWarnsOnlyForVoLteOnly() {
        assertEquals(Level.WARN, CompatEvaluator.evaluate(Bundles.S26U.facts).find(CompatEvaluator.ID_CARRIER)?.level)
        assertNull(CompatEvaluator.evaluate(Bundles.F8U.facts).find(CompatEvaluator.ID_CARRIER))
        assertNull(CompatEvaluator.evaluate(Bundles.S25U.facts).find(CompatEvaluator.ID_CARRIER))
        assertNull(CompatEvaluator.evaluate(Bundles.S25.facts).find(CompatEvaluator.ID_CARRIER))
        // Not read (FactProbe without carrier props): no finding in the report.
        assertNull(CompatEvaluator.evaluate(Fixtures.facts()).find(CompatEvaluator.ID_CARRIER))
    }
}
