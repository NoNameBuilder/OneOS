package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneUiVersionTest {
    @Test
    fun decodesMajorMinorPatch() {
        assertEquals("9.0", OneUiVersion.parse("90000").toString())
        assertEquals("8.5", OneUiVersion.parse("80500").toString())
        assertEquals("6.1.1", OneUiVersion.parse("60101").toString())
        assertEquals("10.0", OneUiVersion.parse("100000").toString())
        assertEquals(OneUiVersion(9, 0, 0), OneUiVersion.parse(" 90000\n"))
    }

    @Test
    fun rawRoundTrips() {
        assertEquals(90000, OneUiVersion.parse("90000")!!.raw)
        assertEquals(80500, OneUiVersion(8, 5, 0).raw)
    }

    @Test
    fun rejectsMissingOrMalformed() {
        assertNull(OneUiVersion.parse(null))
        assertNull(OneUiVersion.parse(""))
        assertNull(OneUiVersion.parse("abc"))
        assertNull(OneUiVersion.parse("9.0"))
        assertNull(OneUiVersion.parse("-90000"))
        assertNull(OneUiVersion.parse("9000"))
        assertNull(OneUiVersion.parse("123456789012"))
    }
}

class ModelFamilyTest {
    @Test
    fun prefixes() {
        assertEquals(ModelFamily.Z_FOLD, ModelFamily.of("SM-F976B"))
        assertEquals(ModelFamily.Z_FOLD, ModelFamily.of("sm-f956u1"))
        assertEquals(ModelFamily.Z_FLIP, ModelFamily.of("SM-F766B"))
        assertEquals(ModelFamily.S_ULTRA, ModelFamily.of("SM-S948B"))
        assertEquals(ModelFamily.S_ULTRA, ModelFamily.of("SM-S9380"))
        assertEquals(ModelFamily.S_ULTRA, ModelFamily.of("SM-S928U1"))
        assertEquals(ModelFamily.S_SERIES, ModelFamily.of("SM-S931B"))
        assertEquals(ModelFamily.OTHER, ModelFamily.of("SM-A566B"))
        assertEquals(ModelFamily.OTHER, ModelFamily.of("SM-X910"))
        assertEquals(ModelFamily.OTHER, ModelFamily.of("Pixel 10 Pro Fold"))
        assertEquals(ModelFamily.OTHER, ModelFamily.of(""))
    }

    @Test
    fun scopeIsFoldFlipUltra() {
        assertTrue(ModelFamily.Z_FOLD.inScope)
        assertTrue(ModelFamily.Z_FLIP.inScope)
        assertTrue(ModelFamily.S_ULTRA.inScope)
        assertFalse(ModelFamily.S_SERIES.inScope)
        assertFalse(ModelFamily.OTHER.inScope)
    }
}

class FormFactorRulesTest {
    private fun resolve(
        model: String,
        panels: List<Panel>,
        complete: Boolean = true,
        hinge: Boolean? = null,
        inputs: List<String> = emptyList(),
    ) = FormFactorRules.resolve(FormSignals(model, panels, complete, hinge, inputs))

    @Test
    fun f8uIsFold() {
        val r = resolve(
            "SM-F976B", listOf(Fixtures.F8U_INNER, Fixtures.F8U_COVER),
            hinge = true, inputs = listOf("flip", "certify_hall"),
        )
        assertEquals(FormFactor.FOLD, r.form)
        assertEquals(FormFactor.FOLD, r.hardwareForm)
        assertTrue(r.foldableHardware)
        assertFalse(r.mismatch)
    }

    @Test
    fun smallCoverIsFlip() {
        val main = Panel(0, "main", 1080, 2520, 400f, 400f)
        val cover = Panel(1, "cover", 948, 1048, 340f, 340f)
        assertTrue(cover.diagonalInches!! < FormFactorRules.FLIP_COVER_MAX_INCHES)
        val r = resolve("SM-F766B", listOf(main, cover), hinge = true, inputs = listOf("flip"))
        assertEquals(FormFactor.FLIP, r.form)
        assertFalse(r.mismatch)
    }

    @Test
    fun ultraIsBarAndCoverHallDoesNotCount() {
        val r = resolve(
            "SM-S948B", listOf(Panel(0, "main", 1440, 3120, 500f, 500f)),
            hinge = false, inputs = listOf("hall", "certify_hall", "sec_touchscreen"),
        )
        assertEquals(FormFactor.BAR, r.form)
        assertEquals(FormFactor.BAR, r.hardwareForm)
        assertFalse(r.foldableHardware)
        assertFalse(r.mismatch)
    }

    @Test
    fun lidWithOneActiveDisplayFallsBackToPrefixForSubtype() {
        val r = resolve("SM-F976B", listOf(Fixtures.F8U_INNER), complete = false, inputs = listOf("flip"))
        assertNull(r.hardwareForm)
        assertTrue(r.foldableHardware)
        assertEquals(FormFactor.FOLD, r.form)
        assertFalse(r.mismatch)

        val flip = resolve("SM-F766B", listOf(Fixtures.F8U_COVER), complete = false, inputs = listOf("flip"))
        assertEquals(FormFactor.FLIP, flip.form)
    }

    @Test
    fun twoPanelsWithoutDpiFallBackToPrefix() {
        val a = Panel(0, "a", 1080, 2520, 0f, 0f)
        val b = Panel(1, "b", 948, 1048, 0f, 0f)
        val r = resolve("SM-F766B", listOf(a, b))
        assertNull(r.hardwareForm)
        assertEquals(FormFactor.FLIP, r.form)
    }

    @Test
    fun hardwareBarButFoldPrefixIsMismatchAndHardwareWins() {
        val r = resolve("SM-F976B", listOf(Fixtures.F8U_INNER), hinge = false)
        assertEquals(FormFactor.BAR, r.form)
        assertEquals(FormFactor.FOLD, r.prefixForm)
        assertTrue(r.mismatch)
    }

    @Test
    fun foldableHardwareButUltraPrefixIsMismatch() {
        val r = resolve("SM-S948B", listOf(Fixtures.F8U_INNER), complete = false, hinge = true)
        assertNull(r.hardwareForm)
        assertEquals(FormFactor.FOLD, r.form)
        assertTrue(r.mismatch)
    }

    @Test
    fun nothingKnownUsesPrefixWithoutMismatch() {
        val r = resolve("SM-F766B", listOf(Fixtures.F8U_INNER), complete = false)
        assertNull(r.hardwareForm)
        assertEquals(FormFactor.FLIP, r.form)
        assertFalse(r.mismatch)

        val other = resolve("SM-A566B", listOf(Fixtures.F8U_INNER), complete = false)
        assertEquals(FormFactor.BAR, other.form)
        assertFalse(other.mismatch)
    }

    @Test
    fun foldableUnknownModelDefaultsToFold() {
        val r = resolve("Foo", listOf(Fixtures.F8U_INNER), complete = false, inputs = listOf("flip"))
        assertEquals(FormFactor.FOLD, r.form)
        assertFalse(r.mismatch)
    }

    @Test
    fun lidNameMustBeExactlyFlip() {
        assertTrue(FormFactorRules.hasFoldLid(listOf(" Flip ")))
        assertFalse(FormFactorRules.hasFoldLid(listOf("flip_cover", "certify_hall", "hall")))
        assertFalse(FormFactorRules.hasFoldLid(emptyList()))
    }

    @Test
    fun diagonalFromDpi() {
        assertEquals(8.0, Fixtures.F8U_INNER.diagonalInches!!, 0.1)
        assertEquals(6.5, Fixtures.F8U_COVER.diagonalInches!!, 0.1)
        assertNull(Panel(0, "x", 1080, 2520, 0f, 0f).diagonalInches)
    }
}

class SocRulesTest {
    @Test
    fun f8uIsSnapdragon8EliteGen5() {
        val s = SocRules.resolve(Fixtures.F8U_SOC)
        assertEquals(SocVendor.QUALCOMM, s.vendor)
        assertEquals("SM8850", s.model)
        assertEquals("Snapdragon 8 Elite Gen 5", s.marketingName)
        assertEquals("Snapdragon 8 Elite Gen 5 (SM8850, canoe)", s.display)
    }

    @Test
    fun exynosFromManufacturerOrPlatform() {
        val a = SocRules.resolve(SocProps(manufacturer = "Samsung", model = "s5e9945", platform = "s5e9945", egl = "mali"))
        assertEquals(SocVendor.EXYNOS, a.vendor)
        assertEquals("Exynos 2400", a.marketingName)

        val b = SocRules.resolve(SocProps(platform = "erd9955"))
        assertEquals(SocVendor.EXYNOS, b.vendor)

        val c = SocRules.resolve(SocProps(chipname = "exynos2200"))
        assertEquals(SocVendor.EXYNOS, c.vendor)
    }

    @Test
    fun qualcommFromPlatformWhenModelEmpty() {
        val s = SocRules.resolve(SocProps(platform = "sun"))
        assertEquals(SocVendor.QUALCOMM, s.vendor)
        assertEquals("SM8750", s.model)
        assertEquals("Snapdragon 8 Elite", s.marketingName)

        assertEquals(SocVendor.QUALCOMM, SocRules.resolve(SocProps(hardware = "qcom")).vendor)
        assertEquals(SocVendor.QUALCOMM, SocRules.resolve(SocProps(platform = "sm8650")).vendor)
        assertEquals(SocVendor.QUALCOMM, SocRules.resolve(SocProps(egl = "adreno")).vendor)
    }

    @Test
    fun mediatekAndUnknown() {
        assertEquals(SocVendor.MEDIATEK, SocRules.resolve(SocProps(platform = "mt6989")).vendor)
        assertEquals(SocVendor.MEDIATEK, SocRules.resolve(SocProps(manufacturer = "Mediatek")).vendor)
        val u = SocRules.resolve(SocProps())
        assertEquals(SocVendor.UNKNOWN, u.vendor)
        assertEquals("Unknown", u.display)
    }
}

class RootRulesTest {
    @Test
    fun grantedWhenUidZero() {
        val r = RootRules.parseIdOutput("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0\n", 0)
        assertEquals(SuState.GRANTED, r.state)
        assertEquals(0, r.uid)
        assertEquals("u:r:su:s0", r.context)
    }

    @Test
    fun deniedWhenNotRootOrNonZeroExit() {
        assertEquals(SuState.DENIED, RootRules.parseIdOutput("uid=10234(u0_a234) gid=10234", 0).state)
        val d = RootRules.parseIdOutput("Permission denied", 1)
        assertEquals(SuState.DENIED, d.state)
        assertNull(d.uid)
        assertEquals(SuState.DENIED, RootRules.parseIdOutput("uid=0(root)", 1).state)
    }

    @Test
    fun outputIsCapped() {
        assertEquals(500, RootRules.parseIdOutput("x".repeat(2000), 1).output.length)
    }

    @Test
    fun kindFromManagerAppThenContextThenSu() {
        assertEquals(RootKind.KERNELSU, RootRules.kind(RootSignals(listOf("me.weishu.kernelsu"))))
        assertEquals(RootKind.APATCH, RootRules.kind(RootSignals(listOf("com.example", "me.bmax.apatch"))))
        assertEquals(RootKind.MAGISK, RootRules.kind(RootSignals(listOf("com.topjohnwu.magisk"))))
        val magiskCtx = SuProbeResult(SuState.GRANTED, 0, "u:r:magisk:s0", "")
        assertEquals(RootKind.MAGISK, RootRules.kind(RootSignals(probe = magiskCtx)))
        assertEquals(RootKind.OTHER, RootRules.kind(RootSignals(suPaths = listOf("/system/bin/su"))))
        assertEquals(RootKind.NONE, RootRules.kind(RootSignals()))
    }
}

class TextTest {
    @Test
    fun getpropDump() {
        val dump = """
            [ro.product.model]: [SM-F976B]
            [ro.build.version.oneui]: [90000]
            [ro.empty]: []
            garbage line
            [ro.soc.model]: [SM8850]
        """.trimIndent()
        val m = Getprop.parse(dump)
        assertEquals("SM-F976B", m["ro.product.model"])
        assertEquals("90000", m["ro.build.version.oneui"])
        assertEquals("", m["ro.empty"])
        assertEquals("SM8850", m["ro.soc.model"])
        assertEquals(4, m.size)
    }

    @Test
    fun bytes() {
        assertEquals("407 GB", Units.bytes(407_000_000_000))
        assertEquals("21.3 GB", Units.bytes(21_300_000_000))
        assertEquals("512 MB", Units.bytes(512_000_000))
        assertEquals("12 B", Units.bytes(12))
    }
}
