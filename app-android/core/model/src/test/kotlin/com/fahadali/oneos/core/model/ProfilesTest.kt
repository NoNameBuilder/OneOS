package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test
    fun theFourBundlesResolveToTheirProfiles() {
        assertEquals(Profiles.F8U, Profiles.resolve(Bundles.F8U.facts).profile)
        assertEquals(Profiles.S_ULTRA_8850, Profiles.resolve(Bundles.S26U.facts).profile)
        assertEquals(Profiles.S_ULTRA_8750, Profiles.resolve(Bundles.S25U.facts).profile)
        assertEquals(Profiles.S_8750, Profiles.resolve(Bundles.S25.facts).profile)
        // The hardware says what the profile says.
        assertEquals(FormFactor.FOLD, Bundles.F8U.facts.form.form)
        assertEquals(FormFactor.BAR, Bundles.S26U.facts.form.form)
        assertEquals(FormFactor.BAR, Bundles.S25U.facts.form.form)
        assertEquals(FormFactor.BAR, Bundles.S25.facts.form.form)
        assertTrue(Bundles.S25.facts.form.evidence.contains("1 built-in display"))
    }

    @Test
    fun builtInTable() {
        val f8u = Profiles.F8U
        assertEquals(listOf("SM-F976", "q8q", "SM8850"), listOf(f8u.modelPrefix, f8u.codename, f8u.socModel))
        assertEquals(FormFactor.FOLD, f8u.form)
        assertEquals(
            listOf(
                ImageChoice("coloros17-findn6", "n6-f8u", tested = true),
                // 0.3.7: the Pixel 11 Fold image, which runs 41-44 booted on this phone.
                ImageChoice("pixel11fold-yogi", "pixel-f8u", tested = true),
            ),
            f8u.choices,
        )
        assertTrue(f8u.testedWithAnyImage && f8u.enabled)

        val s26 = Profiles.S_ULTRA_8850
        assertEquals(listOf("SM-S948", "m3q", "SM8850"), listOf(s26.modelPrefix, s26.codename, s26.socModel))
        assertEquals(FormFactor.BAR, s26.form)
        // 0.3.7: only the Find X9 Ultra CN image (the IN build is gone); tested on the S Ultras and the S25 since 0.4.7.
        val sUltra = listOf(
            ImageChoice("coloros17-findx9u", "x9u-sultra", tested = true),
        )
        assertEquals(sUltra, s26.choices)
        assertTrue(s26.testedWithAnyImage && s26.enabled)

        val s25 = Profiles.S_ULTRA_8750
        assertEquals(listOf("SM-S938", "pa3q", "SM8750"), listOf(s25.modelPrefix, s25.codename, s25.socModel))
        assertEquals(FormFactor.BAR, s25.form)
        assertEquals(sUltra, s25.choices)
        assertTrue(s25.testedWithAnyImage && s25.enabled)

        // Galaxy S25 (0.3.4): the S Ultra image and set (tested since 0.4.7), the S25 Ultra's notes.
        val s25Base = Profiles.S_8750
        assertEquals("s-8750", s25Base.id)
        assertEquals("Galaxy S25", s25Base.label)
        assertEquals(listOf("SM-S931", "pa1q", "SM8750"), listOf(s25Base.modelPrefix, s25Base.codename, s25Base.socModel))
        assertEquals(FormFactor.BAR, s25Base.form)
        assertEquals(sUltra, s25Base.choices)
        assertTrue(s25Base.testedWithAnyImage && s25Base.enabled)
        assertEquals(s25.notes, s25Base.notes)
        assertEquals(listOf("profile-vendor-older"), s25Base.notes.map { it.id })
        assertEquals(listOf(Profiles.F8U, Profiles.S_ULTRA_8850, Profiles.S_ULTRA_8750, Profiles.S_8750), Profiles.BUILT_IN)

        // Ids unique; every choice names an image and a set built from that image,
        // each image once per profile; the fold grid with a ColorOS image (an oplus
        // set) on the fold form only: the Pixel set has none (ColorOS's wallpaper app's).
        assertEquals(Profiles.BUILT_IN.size, Profiles.BUILT_IN.map { it.id }.toSet().size)
        for (p in Profiles.BUILT_IN) {
            assertTrue(p.id, p.choices.isNotEmpty())
            assertEquals(p.id, p.choices.size, p.choices.map { it.imageId }.toSet().size)
            for (c in p.choices) {
                val image = Images.byId(c.imageId)
                val set = PackageSets.byId(c.packageSetId)
                assertNotNull("${p.id} ${c.imageId}", image)
                assertNotNull("${p.id} ${c.packageSetId}", set)
                assertEquals("${p.id} ${c.imageId}", image!!.id, set!!.imageId)
                val colorOs = "oplus-services_patched.jar" in set.names
                assertEquals("${p.id} ${set.id}", p.form == FormFactor.FOLD && colorOs, PackageSets.WALLPAPER_GRID_RRO in set.names)
                assertEquals(c, p.choice(c.imageId))
            }
            assertEquals(p.choices.map { it.imageId }, p.images().map { it.id })
            assertTrue(p.id, p.id.matches(Regex("[a-z0-9][a-z0-9-]{0,31}")))
        }
        assertNull(Profiles.F8U.choice(Images.ID_FIND_X9U))
        // The owner does not want the X9U (CN) image on the Fold: only the N6 and Pixel images there.
        assertEquals(listOf(Profiles.F8U), Profiles.BUILT_IN.filter { it.form == FormFactor.FOLD })
        assertTrue(Profiles.BUILT_IN.filter { it.choice(Images.ID_FIND_X9U) != null }.all { it.form == FormFactor.BAR })
        // The Pixel image only where it ran (0.3.7): the Fold, with its own set.
        assertEquals(listOf(Profiles.F8U), Profiles.BUILT_IN.filter { it.choice(Images.ID_PIXEL_YOGI) != null })
        assertEquals(PackageSets.ID_PIXEL_F8U, Profiles.F8U.choice(Images.ID_PIXEL_YOGI)!!.packageSetId)
        // Nothing takes the Find X9 Ultra IN image or its sets any more.
        assertTrue(Profiles.BUILT_IN.all { p -> p.choices.none { it.imageId == "coloros17-findx9u-in" || it.packageSetId.startsWith("x9u-in") } })
        // Every built-in image is some profile's.
        assertEquals(Images.ALL.map { it.id }.toSet(), Profiles.BUILT_IN.flatMap { p -> p.choices.map { it.imageId } }.toSet())
    }

    @Test
    fun allThreeIdsMustMatch() {
        // The probe builds matched model OR codename; build 2 needs all three.
        fun profile(model: String, device: String, soc: String) = Profiles.resolve(model, device, soc).profile

        assertEquals(Profiles.S_ULTRA_8850, profile("SM-S948U", "m3q", "SM8850"))
        assertEquals(Profiles.S_ULTRA_8850, profile("SM-S948B", "M3Q", "sm8850"))
        assertEquals(Profiles.S_ULTRA_8850, profile(" sm-s9480 ", "m3q", "SM8850"))
        assertNull(profile("SM-S948U", "pa3q", "SM8850")) // right model, wrong codename
        assertNull(profile("SM-S948U", "m3q", "SM8750")) // right model and codename, other chip
        assertNull(profile("SM-S948U", "m3q", "s5e9955")) // an Exynos variant
        assertNull(profile("SM-S938B", "m3q", "SM8850")) // S25 model number, S26 codename
        assertNull(profile("SM-F966B", "q8q", "SM8850")) // codename alone matched in the probe builds
        assertNull(profile("SM-F976B", "q8q", "")) // chip unknown: fail closed
        assertNull(profile("SM-S928B", "e3q", "SM8650")) // S24 Ultra: no profile
        // Galaxy S25 (base): all three ids, like the others.
        assertEquals(Profiles.S_8750, profile("SM-S931B", "pa1q", "SM8750"))
        assertEquals(Profiles.S_8750, profile("SM-S931U1", "PA1Q", "sm8750"))
        assertNull(profile("SM-S931B", "pa3q", "SM8750")) // S25 model number, S25 Ultra codename
        assertNull(profile("SM-S938B", "pa1q", "SM8750")) // S25 Ultra model number, S25 codename
        assertNull(profile("SM-S931B", "pa1q", "SM8850")) // another chip
        assertNull(profile("SM-S936B", "pa2q", "SM8750")) // S25+: no profile
        assertNull(profile("Pixel 10 Pro Fold", "rango", "Tensor G5"))

        val near = Profiles.resolve("SM-S948U", "pa3q", "SM8750")
        assertNull(near.profile)
        assertEquals(Profiles.S_ULTRA_8850, near.nearest)
        assertEquals(listOf("codename pa3q (profile: m3q)", "chip SM8750 (profile: SM8850)"), near.mismatches)

        assertNull(Profiles.resolve("SM-A566B", "a56x", "s5e8855").nearest)
    }

    @Test
    fun builtInProfilesNeverOverlap() {
        val all = Profiles.BUILT_IN
        for (a in all) {
            for (b in all) {
                if (a === b) continue
                assertFalse("${a.id} vs ${b.id}", a.matches(b.modelPrefix + "X", b.codename, b.socModel))
            }
        }
        // Two profiles matching one phone resolve to nothing (fail closed).
        val twice = Profiles.resolve("SM-F976B", "q8q", "SM8850", listOf(Profiles.F8U, Profiles.F8U.copy(id = "copy")))
        assertNull(twice.profile)
        assertTrue(twice.mismatches.single().startsWith("matches 2 profiles"))
    }

    @Test
    fun socModelFromThePlatformWhenThePropIsEmpty() {
        val f = Fixtures.facts(soc = Fixtures.F8U_SOC.copy(model = ""))
        assertEquals("SM8850", f.soc.model)
        assertEquals(Profiles.F8U, Profiles.resolve(f).profile)
    }
}
