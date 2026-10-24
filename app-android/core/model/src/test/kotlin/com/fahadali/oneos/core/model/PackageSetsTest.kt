package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PackageSetsTest {
    private val n6 = PackageSets.N6_F8U
    private val x9u = PackageSets.X9U_SULTRA
    private val pixel = PackageSets.PIXEL_F8U

    /** sha256sum of out/overlays, out/overlays_x9u (2026-10-22) and out/overlays_pixel_yogi (2026-10-24) without the omitted files. */
    private fun shas(set: PackageSet) = Bundles.text("packages/${set.id}.sha256")

    private fun guards(set: PackageSet) = Bundles.text("packages/${set.id}.guards.txt")

    /** The 15 APEX payloads every set carries. */
    private val commonPayloads = listOf(
        "art", "i18n", "runtime", "sdkext", "crashrecovery", "extservices", "permission", "wifi",
        "appsearch", "mediaprovider", "adservices", "rkpd", "healthfitness", "ondevicepersonalization",
        "configinfrastructure",
    ).map { "${it}_payload.img" }

    @Test
    fun setsAsData() {
        assertEquals(22, n6.files.size)
        assertEquals(21, x9u.files.size)
        assertEquals(31, pixel.files.size)
        assertEquals(Images.ID_FIND_N6, n6.imageId)
        assertEquals(Images.ID_FIND_X9U, x9u.imageId)
        assertEquals(Images.ID_PIXEL_YOGI, pixel.imageId)
        assertEquals("out/overlays", n6.sourceDir)
        assertEquals("out/overlays_x9u", x9u.sourceDir)
        assertEquals("out/overlays_pixel_yogi", pixel.sourceDir)
        assertEquals(listOf(n6, x9u, pixel), PackageSets.ALL)
        // Owner: leave out only dead weight.
        assertEquals(listOf("tethering_payload.img"), n6.omitted)
        assertEquals(listOf("tethering_payload.img", "OneOSWallpapersFoldGrid.apk"), x9u.omitted)
        assertEquals(listOf("tethering_payload.img"), pixel.omitted)
        for (s in listOf(n6, x9u)) assertTrue(s.id, PackageSets.CELLBROADCAST !in s.names)
        for (s in PackageSets.ALL) {
            assertEquals(s.names.size, s.names.toSet().size)
            assertTrue(s.omitted.none { it in s.names })
            assertTrue(s.names.none { it == Images.STAGE_NAME })
            assertTrue(s.id, s.names.containsAll(commonPayloads + "guards.txt" + "services_patched.jar"))
            assertEquals(s.id, FileRole.GUARDS, s.file("guards.txt")!!.role)
            // Executable: surfaceflinger_patched and vold_patched only (root's file_mode, deploy-phone.sh's push).
            for (f in s.files) assertEquals("${s.id} ${f.name}", if (f.name in setOf("surfaceflinger_patched", "vold_patched")) "0755" else "0644", f.mode)
            // The guest vold's two binaries: both or none (oneos_switch.sh and guest_prepare.sh refuse one alone).
            assertEquals(s.id, s.file("vold_patched") != null, s.file("service.incremental_patched.so") != null)
            // Back to One UI in the guest exactly when the set stages the in-guest app (guest_prepare.sh installs it).
            assertEquals(s.id, PackageSets.GUEST_APP in s.names, s.hasReturnApp)
        }
        // The ColorOS sets are as they were (0.3.6).
        for (s in listOf(n6, x9u)) {
            assertEquals(listOf("services_patched.jar", "oplus-services_patched.jar"), s.files.filter { it.role == FileRole.GUARD_JAR }.map { it.name })
            assertEquals(s.id, listOf("services_patched.jar", "oplus-services_patched.jar"), s.files.filter { it.role in PackageFile.GUARDED }.map { it.name })
            assertEquals(s.id, 15, s.files.count { it.role == FileRole.APEX_PAYLOAD })
            assertEquals(FileRole.SURFACEFLINGER, s.file("surfaceflinger_patched")!!.role)
            assertFalse(s.hasReturnApp)
        }
        assertEquals(n6.names - PackageSets.WALLPAPER_GRID_RRO, x9u.names)
        assertEquals(FileRole.RRO, n6.file("OneOSWallpapersFoldGrid.apk")!!.role)
        // 15 of the files are byte-identical in both ColorOS sets (deduplicated in the APK).
        val a = ShaList.parse(shas(n6)).entries
        val b = ShaList.parse(shas(x9u)).entries
        assertEquals(15, a.count { (n, h) -> b[n] == h })
        // The Pixel set's files are all its own: nothing shared with the ColorOS sets.
        val c = ShaList.parse(shas(pixel)).entries
        val colorOs = (a.values + b.values).toSet()
        assertEquals(emptyMap<String, String>(), c.filterValues { it in colorOs })
    }

    /** 0.3.7: exactly the files a Pixel session uses (guest_prepare.sh), with their roles. */
    @Test
    fun pixelSetIsWhatAPixelSessionUses() {
        val roles = pixel.files.associate { it.name to it.role }
        assertEquals(FileRole.GUARD_JAR, roles["services_patched.jar"])
        assertEquals(FileRole.GUARD_EXECUTABLE, roles["vold_patched"])
        assertEquals(FileRole.GUARD_LIBRARY, roles["service.incremental_patched.so"])
        assertEquals(FileRole.INJECTION_JAR, roles["oneos-telephony.jar"])
        assertEquals(FileRole.INJECTION_SHA256, roles["oneos-telephony.sha256"])
        assertEquals(FileRole.RRO, roles["OneOSPhoneInjection.apk"])
        assertEquals(FileRole.RRO, roles["OneOSPixelStatusBar.apk"])
        assertEquals(FileRole.GUEST_APP, roles[PackageSets.GUEST_APP])
        assertEquals(listOf("services_patched.jar", "vold_patched", "service.incremental_patched.so"), pixel.files.filter { it.role in PackageFile.GUARDED }.map { it.name })
        assertEquals("0755", pixel.file("vold_patched")!!.mode)
        assertEquals("0644", pixel.file("service.incremental_patched.so")!!.mode)
        // 22 payloads: every set's 15, Google's cellbroadcast/bt/nfcservices, the image's own
        // telephonycore/conscrypt/profiling (no platform framework-telecom.jar) and GmsCore.
        val payloads = pixel.files.filter { it.role == FileRole.APEX_PAYLOAD }.map { it.name }
        assertEquals(22, payloads.size)
        assertEquals(
            (commonPayloads + listOf("cellbroadcast", "bt", "nfcservices", "telephonycore", "conscrypt", "profiling", "gmssystem").map { "${it}_payload.img" }).toSet(),
            payloads.toSet(),
        )
        assertTrue(PackageSets.GMS_PAYLOAD in pixel.names)
        // Never mounted (the host keeps its tethering module): left out.
        assertTrue(PackageSets.TETHERING !in pixel.names)
        // Nothing of ColorOS: no oplus jars, no patched surfaceflinger (Google's is not patched), no fold grid.
        for (n in listOf("oplus-services_patched.jar", "oplus-telephony-common_patched.jar", "ims-ext-common_patched.jar", "surfaceflinger_patched", PackageSets.WALLPAPER_GRID_RRO)) {
            assertTrue(n, n !in pixel.names)
        }
        assertTrue(pixel.hasReturnApp)
        // The free-space gate's number is the files' total (out/overlays_pixel_yogi, 2026-10-24).
        assertEquals(485_687_124L, pixel.approxBytes)
        // Each image presents its Google services exactly when its sets stage GmsCore.
        for (p in Profiles.BUILT_IN) {
            for (choice in p.choices) {
                val image = Images.byId(choice.imageId)!!
                val set = PackageSets.byId(choice.packageSetId)!!
                assertEquals("${p.id} ${set.id}", image.presentsGms, set.file(PackageSets.GMS_PAYLOAD) != null)
            }
        }
    }

    @Test
    fun shaListsNameExactlyTheSetsFiles() {
        for (s in PackageSets.ALL) assertEquals(s.id, emptyList<String>(), s.check(shas(s)))
        // The Pixel list and a ColorOS one are not interchangeable.
        val pixelOnN6 = pixel.check(shas(n6))
        assertTrue(pixelOnN6.contains("pixel-f8u: vold_patched missing"))
        assertTrue(pixelOnN6.contains("pixel-f8u: OneOSGuest.apk missing"))
        assertTrue(pixelOnN6.contains("pixel-f8u: oplus-services_patched.jar is not in this set"))
        assertTrue(pixelOnN6.contains("pixel-f8u: OneOSWallpapersFoldGrid.apk is not in this set"))
        val n6OnPixel = n6.check(shas(pixel))
        assertTrue(n6OnPixel.contains("n6-f8u: surfaceflinger_patched missing"))
        assertTrue(n6OnPixel.contains("n6-f8u: vold_patched is not in this set"))
        assertTrue(n6OnPixel.contains("n6-f8u: OneOSGuest.apk is not in this set"))
        assertTrue(x9u.check(shas(pixel)).contains("x9u-sultra: cellbroadcast_payload.img is not in this set"))
        val pixelTether = shas(pixel) + "${"a".repeat(64)}  tethering_payload.img\n"
        assertEquals(listOf("pixel-f8u: tethering_payload.img is left out of this set"), pixel.check(pixelTether))
        // Swapped lists fail: the fold grid is not in the S Ultra set.
        assertTrue(x9u.check(shas(n6)).contains("x9u-sultra: OneOSWallpapersFoldGrid.apk is left out of this set"))
        assertTrue(n6.check(shas(x9u)).contains("n6-f8u: OneOSWallpapersFoldGrid.apk missing"))

        val extra = shas(x9u) + "${"a".repeat(64)}  framework_patched.jar\n"
        assertEquals(listOf("x9u-sultra: framework_patched.jar is not in this set"), x9u.check(extra))
        val tether = shas(x9u) + "${"a".repeat(64)}  tethering_payload.img\n"
        assertEquals(listOf("x9u-sultra: tethering_payload.img is left out of this set"), x9u.check(tether))
        val dup = shas(x9u) + shas(x9u).lines().first() + "\n"
        assertEquals(listOf("${shas(x9u).lines().first().substringAfter("  ")} listed twice"), x9u.check(dup))
    }

    @Test
    fun guardsMatchTheGuardJars() {
        for (s in PackageSets.ALL) assertEquals(s.id, emptyList<String>(), s.checkGuards(guards(s), shas(s)))
        // N6 guards with the X9U jars: never mix builds.
        val mixed = x9u.checkGuards(guards(n6), shas(x9u))
        assertEquals(2, mixed.size)
        assertTrue(mixed.all { it.startsWith("guards.txt: ") && it.contains("the set has") })
        // A guards.txt that lists something else, or misses a jar.
        assertTrue(x9u.checkGuards(guards(x9u) + "${"b".repeat(64)}  framework_patched.jar\n", shas(x9u)).single().contains("not a guard jar"))
        assertTrue(x9u.checkGuards(guards(x9u).lines().filterNot { it.endsWith("oplus-services_patched.jar") }.joinToString("\n"), shas(x9u)).single().contains("missing"))
        // A ColorOS set's guards need no Google services guards (its guards.txt names none of them).
        for (t in PackageSets.GMS_GUARDS) assertFalse(t, Regex("\\b$t\\b").containsMatchIn(guards(n6)))
    }

    /** 0.3.7: the Pixel set's guards.txt pins services_patched.jar and both gvld binaries; comment lines are fine. */
    @Test
    fun pixelGuardsPinTheGuestVoldToo() {
        val g = guards(pixel)
        val sums = shas(pixel)
        assertEquals(setOf("services_patched.jar", "vold_patched", "service.incremental_patched.so"), ShaList.parse(g).entries.keys)
        assertTrue(g.lines().first().startsWith("#"))
        assertEquals(emptyList<String>(), pixel.checkGuards(g, sums))
        // Only one of the two gvld binaries (a half-built set): refused, as the session scripts do.
        for (n in listOf("vold_patched", "service.incremental_patched.so")) {
            val half = g.lines().filterNot { it.endsWith("  $n") }.joinToString("\n")
            assertEquals(n, listOf("guards.txt: $n missing"), pixel.checkGuards(half, sums))
        }
        // A vold_patched that is not the set's.
        val wrong = g.replace(ShaList.parse(g).entries.getValue("vold_patched"), "c".repeat(64))
        assertTrue(pixel.checkGuards(wrong, sums).single().startsWith("guards.txt: vold_patched is cccccccccccc..., the set has "))
        // Something else listed.
        assertTrue(pixel.checkGuards(g + "${"b".repeat(64)}  OneOSGuest.apk\n", sums).single().contains("OneOSGuest.apk is not a guard jar of pixel-f8u"))
        // Without the comment lines naming the Google services guards: each one is missing (a switch would be refused).
        val bare = g.lines().filterNot { it.startsWith("#") }.joinToString("\n")
        assertEquals(PackageSets.GMS_GUARDS.map { "guards.txt does not name the $it guard Google services need" }, pixel.checkGuards(bare, sums))
        assertEquals(1, pixel.checkGuards(g.replace("appid15000", "appid"), sums).size)
        // Never mixed: a ColorOS set's guards do not vouch for the Pixel files, nor the other way round.
        val colorOsOnPixel = pixel.checkGuards(guards(n6), sums)
        assertTrue(colorOsOnPixel.contains("guards.txt: vold_patched missing"))
        assertTrue(colorOsOnPixel.contains("guards.txt: service.incremental_patched.so missing"))
        assertTrue(colorOsOnPixel.contains("guards.txt: oplus-services_patched.jar is not a guard jar of pixel-f8u"))
        assertTrue(colorOsOnPixel.any { it.startsWith("guards.txt: services_patched.jar is ") })
        val pixelOnN6 = n6.checkGuards(g, shas(n6))
        assertTrue(pixelOnN6.contains("guards.txt: vold_patched is not a guard jar of n6-f8u"))
        assertTrue(pixelOnN6.contains("guards.txt: service.incremental_patched.so is not a guard jar of n6-f8u"))
        assertTrue(pixelOnN6.contains("guards.txt: oplus-services_patched.jar missing"))
        assertTrue(x9u.checkGuards(g, shas(x9u)).isNotEmpty())
    }

    @Test
    fun shaListParsing() {
        val h = "0123456789abcdef".repeat(4)
        val ok = ShaList.parse("# comment\n$h  a.jar\n${h.uppercase()} *b_payload.img\n\n")
        assertEquals(mapOf("a.jar" to h, "b_payload.img" to h), ok.entries)
        assertTrue(ok.problems.isEmpty())
        assertEquals("$h  a.jar\n$h  b_payload.img\n", ShaList.render(ok.entries))

        for (bad in listOf("$h a.jar b", "$h  ../x", "$h  dir/x", "$h  .hidden", "${h.dropLast(1)}  a.jar", "$h\ta.jar")) {
            assertFalse(bad, ShaList.parse(bad).problems.isEmpty())
            assertTrue(bad, ShaList.parse(bad).entries.isEmpty())
        }
    }

    /** B2's checked-in lists (app-android/packages), when they exist, name exactly these sets. */
    @Test
    fun checkedInPackageListsMatchTheSets() {
        val dir = System.getProperty("oneos.packagesDir")?.let(::File)
        assumeTrue("app-android/packages not there yet", dir != null && dir.isDirectory)
        for (s in PackageSets.ALL) {
            val f = File(dir, "${s.id}.sha256")
            assumeTrue("${f.name} not there yet", f.isFile)
            assertEquals(s.id, emptyList<String>(), s.check(f.readText()))
        }
        // The IN sets' lists are gone with the image (0.3.7).
        assertEquals(PackageSets.ALL.map { "${it.id}.sha256" }.toSet(), dir!!.list()!!.filter { it.endsWith(".sha256") }.toSet())
    }

    @Test
    fun spaceToStage() {
        // AA17: about 22.5 GB for the S Ultra (X9U image + files + 11 GiB).
        val need = SpaceRule.toStage(Images.FIND_X9U, x9u)
        assertEquals("22.5 GB", Units.bytes(need))
        // The Pixel image is smaller: its own raw size and files.
        val pixelNeed = SpaceRule.toStage(Images.PIXEL_YOGI, pixel)
        assertEquals(4_728_029_184L + 485_687_124L + 11L * SpaceRule.GIB, pixelNeed)
        assertEquals("17.0 GB", Units.bytes(pixelNeed))
        assertEquals(11L * SpaceRule.GIB, SpaceRule.LAUNCH_BYTES)
        assertEquals(Images.FIND_X9U.rawBytes + SpaceRule.LAUNCH_BYTES, SpaceRule.toStage(Images.FIND_X9U, null))
    }
}
