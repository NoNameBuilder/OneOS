package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realistic shapes, synthetic values (no real device or person). */
class RedactorTest {
    private fun redactor(salt: String = "test-salt") = Redactor(salt.toByteArray())

    private val serial = "R5CX21ABCDE"
    private val imei = "490154203237518" // Luhn-valid example IMEI
    private val getprop = """
        [ro.build.fingerprint]: [samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXS3AZIJ:user/release-keys]
        [ro.build.id]: [CP2A.260605.016]
        [ro.product.model]: [SM-F976B]
        [ro.build.version.oneui]: [90000]
        [ro.serialno]: [$serial]
        [ro.boot.serialno]: [$serial]
        [ril.serialnumber]: [$serial]
        [ro.boot.bootconfig]: [androidboot.serialno=$serial androidboot.hardware=qcom]
        [ro.boot.em.did]: [2041A3B5C7D9E1F3]
        [ro.boot.uniqueno]: [7F3A9C21B04D]
        [ril.imei]: [$imei]
        [persist.radio.imei1]: [$imei,356938035643809]
        [ril.meid]: [A1000049D2E5F7]
        [ro.boot.wifimacaddr]: [a4:c3:f0:85:ac:2d]
        [ro.boot.btmacaddr]: [A4:C3:F0:85:AC:2E]
        [wifi.interface]: [wlan0]
        [wifi.direct.interface]: [p2p-dev-wlan0]
        [wifi.supplicant_scan_interval]: [15]
        [persist.vendor.wifi.lastssid]: [Alex-Home-5G]
        [persist.vendor.wifi.bssid]: [3c:84:6a:12:34:56]
        [wifi.active.ssid]: ["Lounge WiFi"]
        [ril.iccid.sim1]: [8996601234567890123]
        [ril.imsi]: [420011234567890]
        [ril.msisdn]: [+966501234567]
        [persist.sys.account.name]: [alex.doe]
        [net.hostname]: [Alexs-Fold]
        [gsm.sim.operator.alpha]: [STC]
        [ro.config.wifi.enable]: [true]
        [persist.sys.timezone]: [Europe/Paris]
        [ro.boot.verifiedbootstate]: [green]
    """.trimIndent()

    private val secrets = listOf(
        serial, imei, "356938035643809", "2041A3B5C7D9E1F3", "7F3A9C21B04D", "A1000049D2E5F7",
        "a4:c3:f0:85:ac:2d", "A4:C3:F0:85:AC:2E", "Alex-Home-5G", "3c:84:6a:12:34:56", "Lounge WiFi",
        "8996601234567890123", "420011234567890", "+966501234567", "alex.doe", "Alexs-Fold",
    )

    @Test
    fun getpropRemovesEverySecretAndKeepsBuildFacts() {
        val out = redactor().redactGetprop(getprop, "props/getprop.txt")
        for (s in secrets) assertFalse("leaked $s", out.contains(s))
        for (keep in listOf(
            "[ro.build.fingerprint]: [samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXS3AZIJ:user/release-keys]",
            "[ro.build.id]: [CP2A.260605.016]",
            "[ro.product.model]: [SM-F976B]",
            "[ro.build.version.oneui]: [90000]",
            "[wifi.interface]: [wlan0]",
            "[wifi.direct.interface]: [p2p-dev-wlan0]",
            "[wifi.supplicant_scan_interval]: [15]",
            "[gsm.sim.operator.alpha]: [STC]",
            "[ro.config.wifi.enable]: [true]",
            "[ro.boot.verifiedbootstate]: [green]",
        )) {
            assertTrue("lost $keep", out.lines().contains(keep))
        }
    }

    @Test
    fun serialKeysGetOneTokenAndTheValueIsRemovedEverywhere() {
        val r = redactor()
        val out = r.redactGetprop(getprop, "props/getprop.txt")
        val token = r.token(RedactionKind.SERIAL, serial)
        assertTrue(out.contains("[ro.serialno]: [$token]"))
        assertTrue(out.contains("[ro.boot.serialno]: [$token]"))
        assertTrue(out.contains("[ril.serialnumber]: [$token]"))
        // Inside another property's value (learned from the key rules first).
        assertTrue(out.contains("androidboot.serialno=$token androidboot.hardware=qcom"))
        // And in other files redacted later from the same bundle.
        val other = r.redactText("usb: device $serial attached", "input/getevent.txt")
        assertEquals("usb: device $token attached", other)
    }

    @Test
    fun imeiByKeyIncludingLists() {
        val r = redactor()
        val out = r.redactGetprop(getprop, "props/getprop.txt")
        assertTrue(out.contains("[ril.imei]: [${r.token(RedactionKind.IMEI, imei)}]"))
        assertTrue(out.contains("[persist.radio.imei1]: [${r.token(RedactionKind.IMEI, "$imei,356938035643809")}]"))
        assertTrue(r.log.propKeys.containsAll(listOf("ril.imei", "persist.radio.imei1")))
    }

    @Test
    fun imeiByLuhnValueAndByLabel() {
        val r = redactor()
        val out = r.redactText("slot0 $imei slot1 490154203237519\nIMEI: 35693803564380 (14 digits)", "x.txt")
        assertFalse(out.contains(imei))
        // Not Luhn-valid, no label: kept.
        assertTrue(out.contains("490154203237519"))
        // 14 digits next to the IMEI label: removed.
        assertFalse(out.contains("35693803564380"))
        assertEquals(2, r.log.count(RedactionKind.IMEI))
        assertTrue(Redactor.luhn(imei))
        assertFalse(Redactor.luhn("490154203237519"))
    }

    @Test
    fun meidByKeyAndLabel() {
        val r = redactor()
        assertFalse(r.redactGetprop("[ril.meid]: [A1000049D2E5F7]", "p").contains("A1000049D2E5F7"))
        assertFalse(r.redactText("MEID=A1000049D2E5F8", "t").contains("A1000049D2E5F8"))
        assertEquals(2, r.log.count(RedactionKind.MEID))
    }

    @Test
    fun macAddressesRemovedButPlaceholdersKept() {
        val r = redactor()
        val out = r.redactText(
            "wlan0 addr a4:c3:f0:85:ac:2d bt A4-C3-F0-85-AC-2E none 02:00:00:00:00:00 zero 00:00:00:00:00:00",
            "t",
        )
        assertFalse(out.contains("a4:c3:f0:85:ac:2d"))
        assertFalse(out.contains("A4-C3-F0-85-AC-2E"))
        assertTrue(out.contains("02:00:00:00:00:00"))
        assertTrue(out.contains("00:00:00:00:00:00"))
        assertEquals(2, r.log.count(RedactionKind.MAC))
        // Times and ratios are not MACs.
        assertEquals("at 10:22:33.123 ratio 16:9", r.redactText("at 10:22:33.123 ratio 16:9", "t"))
    }

    @Test
    fun e164PhoneNumbers() {
        val r = redactor()
        val out = r.redactText("call +966501234567 or +966 50 123 4567 or +44 20 7946 0958 ok", "t")
        assertFalse(out.contains("501234567"))
        assertFalse(out.contains("50 123 4567"))
        assertFalse(out.contains("7946 0958"))
        assertEquals(3, r.log.count(RedactionKind.PHONE))
        assertTrue(out.startsWith("call <phone:"))
    }

    @Test
    fun saudiLocalAndInternationalWithoutPlus() {
        val r = redactor()
        val out = r.redactText("mobile 0501234567, alt 055 987 6543, intl 966551234567, 00966501112222", "t")
        for (n in listOf("0501234567", "055 987 6543", "966551234567", "00966501112222")) assertFalse(n, out.contains(n))
        assertEquals(4, r.log.count(RedactionKind.PHONE))
    }

    @Test
    fun phoneByKey() {
        val r = redactor()
        val out = r.redactGetprop("[ril.msisdn]: [0501234567]\n[persist.sys.line1_number]: [12345678]", "p")
        assertFalse(out.contains("0501234567"))
        assertFalse(out.contains("12345678"))
    }

    @Test
    fun emails() {
        val r = redactor()
        val out = r.redactText("owner alex.doe@example.com, backup ops+test@corp.example.net", "t")
        assertFalse(out.contains("alex.doe@example.com"))
        assertFalse(out.contains("ops+test@corp.example.net"))
        assertEquals(2, r.log.count(RedactionKind.EMAIL))
    }

    @Test
    fun wifiPropsAreSsidOrBssidUnlessTechnical() {
        val r = redactor()
        val out = r.redactGetprop(getprop, "props/getprop.txt")
        assertTrue(out.contains("[persist.vendor.wifi.lastssid]: [${r.token(RedactionKind.SSID, "Alex-Home-5G")}]"))
        assertTrue(out.contains("[wifi.active.ssid]: [${r.token(RedactionKind.SSID, "\"Lounge WiFi\"")}]"))
        assertTrue(out.contains("[persist.vendor.wifi.bssid]: [${r.token(RedactionKind.BSSID, "3c:84:6a:12:34:56")}]"))
        assertTrue(out.contains("[wifi.interface]: [wlan0]"))
    }

    @Test
    fun ssidAndBssidInText() {
        val r = redactor()
        val out = r.redactText(
            "SSID: \"Lounge WiFi\", BSSID: 3c:84:6a:12:34:56, ssid=Home5G, ssid=<unknown ssid>",
            "t",
        )
        assertFalse(out.contains("Lounge WiFi"))
        assertFalse(out.contains("3c:84:6a:12:34:56"))
        assertFalse(out.contains("Home5G"))
        assertTrue(out.contains("<unknown ssid>"))
        assertEquals(1, r.log.count(RedactionKind.BSSID))
        assertEquals(2, r.log.count(RedactionKind.SSID))
    }

    @Test
    fun iccidByValueAndKey() {
        val r = redactor()
        assertFalse(r.redactText("sim iccid 8996601234567890123F", "t").contains("899660123456789012"))
        assertFalse(r.redactGetprop("[ril.iccid.sim1]: [8996601234567890123]", "p").contains("8996601234567890123"))
        assertEquals(2, r.log.count(RedactionKind.ICCID))
    }

    @Test
    fun imsiByKeyAndLabel() {
        val r = redactor()
        assertFalse(r.redactGetprop("[ril.imsi]: [420011234567890]", "p").contains("420011234567890"))
        assertFalse(r.redactText("mImsi=42001987654321", "t").contains("42001987654321"))
        assertEquals(2, r.log.count(RedactionKind.IMSI))
    }

    @Test
    fun accountNames() {
        val r = redactor()
        val out = r.redactText("Account {name=alex.doe, type=com.google} accountName=alex_doe", "t")
        assertFalse(out.contains("alex.doe"))
        assertFalse(out.contains("alex_doe"))
        assertTrue(out.contains("type=com.google"))
        assertEquals(2, r.log.count(RedactionKind.ACCOUNT))
    }

    @Test
    fun gpsCoordinates() {
        val r = redactor()
        val out = r.redactText("fix 52.516275, 13.377704 lat=52.5163 lon: 13.3777 not 512.12345, 3.12345", "t")
        assertFalse(out.contains("52.516275"))
        assertFalse(out.contains("13.377704"))
        assertFalse(out.contains("52.5163"))
        assertFalse(out.contains("13.3777"))
        assertTrue(out.contains("512.12345, 3.12345")) // latitude over 90: not a coordinate
        assertEquals(3, r.log.count(RedactionKind.GEO))
    }

    @Test
    fun samsungDeviceIdAndDeviceNames() {
        val r = redactor()
        val out = r.redactGetprop(getprop, "props/getprop.txt")
        assertTrue(out.contains("[ro.boot.em.did]: [${r.token(RedactionKind.DEVICE_ID, "2041A3B5C7D9E1F3")}]"))
        assertTrue(out.contains("[ro.boot.uniqueno]: [${r.token(RedactionKind.DEVICE_ID, "7F3A9C21B04D")}]"))
        assertTrue(out.contains("[net.hostname]: [${r.token(RedactionKind.DEVICE_NAME, "Alexs-Fold")}]"))
        val bt = r.redactGetprop("[persist.bluetooth.name]: [Alex Z Fold]\n[ro.product.name]: [q8qxxx]", "p")
        assertFalse(bt.contains("Alex Z Fold"))
        assertTrue(bt.contains("[ro.product.name]: [q8qxxx]"))
    }

    @Test
    fun bluetoothInputDeviceNamesGoEverywhereBuiltInOnesStay() {
        val proc = """
            I: Bus=0000 Vendor=0000 Product=0000 Version=0000
            N: Name="sec_touchscreen"
            P: Phys=sec_touchscreen/input0
            H: Handlers=event2
            
            I: Bus=0005 Vendor=046d Product=b342 Version=0001
            N: Name="Alex's Keyboard"
            P: Phys=a4:c3:f0:85:ac:2e
            U: Uniq=3c:84:6a:12:34:56
            H: Handlers=sysrq kbd event9
        """.trimIndent()
        val r = redactor()
        r.learnBluetoothInputNames(proc)
        // getevent.txt is redacted before the /proc file and names devices without their bus.
        val getevent = r.redactText(
            "add device 9: /dev/input/event9\n  name:     \"Alex's Keyboard\"\nadd device 2: /dev/input/event2\n  name:     \"sec_touchscreen\"",
            "input/getevent.txt",
        )
        val procOut = r.redactText(proc, "input/proc_bus_input_devices.txt")
        val token = r.token(RedactionKind.DEVICE_NAME, "Alex's Keyboard")
        for (out in listOf(getevent, procOut)) {
            assertFalse(out, out.contains("Alex"))
            assertTrue(out, out.contains(token))
            assertTrue(out, out.contains("sec_touchscreen"))
        }
        assertFalse(procOut.contains("3c:84:6a:12:34:56"))
        // The whole name only: the word "Keyboard" alone stays elsewhere.
        assertEquals("USB Keyboard", r.redactText("USB Keyboard", "t"))
    }

    @Test
    fun keyValueLinesAndCpuinfoSerial() {
        val r = redactor()
        val out = r.redactText(
            "ro.serialno=R5CX99ZZZZZ\nro.build.id=CP2A.260605.016\nHardware\t: Qualcomm\nSerial\t\t: 00000000c0ffee12",
            "props/key.txt",
        )
        assertFalse(out.contains("R5CX99ZZZZZ"))
        assertFalse(out.contains("c0ffee12"))
        assertTrue(out.contains("ro.build.id=CP2A.260605.016"))
        assertTrue(out.contains("Hardware\t: Qualcomm"))
        assertTrue("ro.serialno" in r.log.propKeys)
    }

    @Test
    fun noFalsePositivesOnTechnicalOutput() {
        val technical = """
            Display 4619827259835644672 (HWC display 0): port=147 pnpId=QCM displayName="Built-in Screen"
            DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4619827259835644672", 2256 x 2504, modeId 1}
            Linux localhost 6.12.23-android16-5-gabcdef123456-4k #1 SMP PREEMPT Thu Oct  2 10:22:33 UTC 2026 aarch64
            3f1b2c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80  /system/framework/services.jar
            28190726 2026-10-02 10:22 classes.dex
            ro.build.version.security_patch=2026-10-05
            /dev/block/dm-48     481000000 74000000 407000000  16% /data
            CPU part	: 0xd81
            max_brightness=4095
        """.trimIndent()
        val r = redactor()
        assertEquals(technical, r.redactText(technical, "t"))
        assertEquals(0, r.log.total)
    }

    @Test
    fun tokensAreStablePerSaltAndDifferAcrossBundles() {
        val a = redactor("bundle-a")
        val b = redactor("bundle-b")
        val t1 = a.token(RedactionKind.IMEI, imei)
        assertEquals(t1, a.token(RedactionKind.IMEI, imei))
        assertNotEquals(t1, b.token(RedactionKind.IMEI, imei))
        assertTrue(Regex("^<imei:[0-9a-f]{6}>$").matches(t1))
        assertNotEquals(Redactor.forBundle().token(RedactionKind.IMEI, imei), Redactor.forBundle().token(RedactionKind.IMEI, imei))
    }

    @Test
    fun logCountsKindsAndNeverShowsValues() {
        val r = redactor()
        r.redactGetprop(getprop, "props/getprop.txt")
        r.redactText("mail alex.doe@example.com", "display/x.txt")
        val log = r.log
        assertTrue(log.count(RedactionKind.SERIAL) >= 4)
        assertEquals(1, log.distinct(RedactionKind.SERIAL))
        assertEquals(1, log.count(RedactionKind.EMAIL))
        assertEquals(setOf("props/getprop.txt", "display/x.txt"), log.fileCounts().keys)
        assertTrue(log.propKeys.containsAll(listOf("ro.serialno", "ril.iccid.sim1", "ril.msisdn", "ro.boot.wifimacaddr")))
        val rendered = log.render() + Json.write(log.toJson())
        for (s in secrets + "alex.doe@example.com") assertFalse("log shows $s", rendered.contains(s))
        assertTrue(rendered.contains("serial"))
    }

    @Test
    fun existingTokensAreNeverRedactedAgain() {
        val r = redactor()
        val once = r.redactText("serial: R5CX21ABCDE ssid=Home5G Account {name=alex.doe, type=x}", "t")
        val total = r.log.total
        assertEquals(3, total)
        // Tokens look like "serial:1a2b3c" / "ssid:..." / "account:..."; a second pass must leave them alone.
        assertEquals(once, r.redactText(once, "t"))
        assertEquals(total, r.log.total)
    }
    // Found by the realistic sample (RedactorSample): each was a leak or a broken technical line.

    @Test
    fun serviceStatePropsAreNeverRedactedByName() {
        val r = redactor()
        val dump = "[init.svc.vendor.serial_logger]: [running]\n[init.svc.vendor.wifi_hal_legacy]: [running]\n" +
            "[init.svc.adbd]: [running]\n[ro.boottime.vendor.imei_svc]: [4211234567]\n[init.svc.macloader]: [stopped]"
        assertEquals(dump, r.redactGetprop(dump, "p"))
        assertEquals(0, r.log.total)
        // A state word is never learned and spread, even from a property that is redacted.
        val out = r.redactGetprop("[persist.vendor.radio.imei_state]: [running]\n[init.svc.adbd]: [running]", "p")
        assertTrue(out.contains("[init.svc.adbd]: [running]"))
    }

    @Test
    fun lowVarietyValuesAreRedactedInPlaceButNotSpread() {
        val r = redactor()
        val out = r.redactGetprop("[ro.setupwizard.esim_cid_ignore]: [00000001]", "p")
        assertFalse(out.contains("00000001"))
        val bitmap = "B: KEY=4000000000000 0000000000000001 0"
        assertEquals(bitmap, r.redactText(bitmap, "input/proc_bus_input_devices.txt"))
    }

    @Test
    fun wifiNamesAnywhereInTheKey() {
        val r = redactor()
        val out = r.redactGetprop(
            "[persist.sys.wifi.lastconnected]: [Fiber_Home_5G]\n[ro.vendor.wifi.sap.interface]: [swlan0]\n" +
                "[persist.vendor.wifi.ifaces]: [wlan0,wlan1]\n[vendor.wifi.config.dir]: [/data/vendor/wifi]",
            "p",
        )
        assertFalse(out.contains("Fiber_Home_5G"))
        assertTrue(out.contains("[ro.vendor.wifi.sap.interface]: [swlan0]"))
        assertTrue(out.contains("[persist.vendor.wifi.ifaces]: [wlan0,wlan1]"))
        assertTrue(out.contains("[vendor.wifi.config.dir]: [/data/vendor/wifi]"))
    }

    @Test
    fun dottedPersonalKeyNames() {
        val r = redactor()
        val out = r.redactGetprop(
            "[persist.sys.user.name]: [Alex Doe]\n[persist.sys.device.name]: [Alexs Fold]\n" +
                "[persist.sys.sim1.phone.number]: [551234567]\n[vendor.ril.subscriber.number]: [551234568]\n" +
                "[persist.sys.nick_name]: [abu_ali]",
            "p",
        )
        for (s in listOf("Alex Doe", "Alexs Fold", "551234567", "551234568", "abu_ali")) assertFalse(s, out.contains(s))
    }

    @Test
    fun samsungUfsNumberAndEsimEid() {
        val r = redactor()
        val eid = "89049032004008882600012345678901"
        val out = r.redactGetprop("[ro.boot.un]: [W57A1B2C3D4E5F6]\n[vendor.ril.euicc.eid]: [$eid]", "p")
        assertFalse(out.contains("W57A1B2C3D4E5F6"))
        assertTrue(out.contains("[vendor.ril.euicc.eid]: [${r.token(RedactionKind.EID, eid)}]"))
        // By value: 32 digits starting with 89 (not an ICCID, which is 19-20).
        val text = r.redactText("euicc eid 89049032004008882600098765432109 iccid 8996601234567890123", "t")
        assertFalse(text.contains("89049032004008882600098765432109"))
        assertFalse(text.contains("8996601234567890123"))
        assertEquals(2, r.log.count(RedactionKind.EID))
    }

    @Test
    fun macAfterAPrefixButNotInsideALongerColonRun() {
        val r = redactor()
        val out = r.redactText(
            "uniqueId='wifi:FA:CE:B0:0C:12:34' mac:a4:c3:f0:85:ac:2d HWaddr:3c:84:6a:12:34:56 " +
                "fp 11:22:33:44:55:66:77:88",
            "t",
        )
        for (m in listOf("FA:CE:B0:0C:12:34", "a4:c3:f0:85:ac:2d", "3c:84:6a:12:34:56")) assertFalse(m, out.contains(m))
        assertTrue(out.contains("fp 11:22:33:44:55:66:77:88"))
        assertEquals(3, r.log.count(RedactionKind.MAC))
    }

    @Test
    fun learnedIdentifiersMatchAnyCaseAndOtherSpellings() {
        val r = redactor()
        r.redactGetprop(
            "[persist.bluetooth.address]: [A4C3F085AC2F]\n[ro.boot.wifimacaddr]: [a4:c3:f0:85:ac:2d]\n" +
                "[ro.boot.ap_serial]: [0x9F1C3A7B2D40]\n[ro.serialno]: [R5CY30XYZAB]",
            "p",
        )
        val out = r.redactText(
            "bt a4:c3:f0:85:ac:2f wlan A4C3F085AC2D or a4-c3-f0-85-ac-2d ap 9f1c3a7b2d40 sn r5cy30xyzab",
            "t",
        )
        for (s in listOf("ac:2f", "AC2D", "ac-2d", "9f1c3a7b2d40", "r5cy30xyzab")) assertFalse(s, out.contains(s, ignoreCase = true))
        // One token per value, whatever the spelling.
        assertTrue(out.contains("wlan ${r.token(RedactionKind.MAC, "a4:c3:f0:85:ac:2d")} or ${r.token(RedactionKind.MAC, "a4:c3:f0:85:ac:2d")}"))
        // A path under a MAC or serial name is where the value is kept, not the value.
        assertEquals("[ro.bt.bdaddr_path]: [/efs/bluetooth/bt_addr]", r.redactGetprop("[ro.bt.bdaddr_path]: [/efs/bluetooth/bt_addr]", "p"))
    }

    @Test
    fun multiLinePropertyValuesGetTheKeyRules() {
        val r = redactor()
        val dump = "[persist.sys.wifi.saved]: [Lounge WiFi\nFiber_Home_5G]\n[persist.sys.boot.reason.history]: [reboot,1761114612\nreboot,1761000000]\n[ro.product.model]: [SM-F976B]"
        val out = r.redactGetprop(dump, "p")
        assertFalse(out.contains("Lounge"))
        assertFalse(out.contains("Fiber_Home_5G"))
        assertTrue(out.contains("[persist.sys.boot.reason.history]: [reboot,1761114612\nreboot,1761000000]"))
        assertTrue(out.endsWith("[ro.product.model]: [SM-F976B]"))
    }

    @Test
    fun virtualAndWifiDisplayNames() {
        val r = redactor()
        val out = r.redactText(
            "DisplayDeviceInfo{\"Alex TV\": uniqueId=\"virtual:com.samsung.android.smartmirroring,1000,[TV] Alex Living Room,0\", 1920 x 1080, type VIRTUAL}\n" +
                "DisplayInfo{\"Lounge TV\", displayId 2, uniqueId \"wifi:FA:CE:B0:0C:12:34\", type WIFI}\n" +
                "DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:4619827259835644672\", 1968 x 2184, type INTERNAL}",
            "display/displaymanager.txt",
        )
        for (s in listOf("Alex", "Living Room", "Lounge", "FA:CE:B0")) assertFalse(s, out.contains(s))
        assertTrue(out.contains("DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:4619827259835644672\""))
    }

    @Test
    fun usbAccessorySerialInUniqAndCellIds() {
        val r = redactor()
        val out = r.redactText("U: Uniq=0001234567AB\nU: Uniq=\nCellIdentityNr:{ mPci=123 mTac=4567 mNci=987654321 }", "t")
        assertFalse(out.contains("0001234567AB"))
        assertTrue(out.contains("U: Uniq=\n"))
        for (s in listOf("mPci=123", "mTac=4567", "mNci=987654321")) assertFalse(s, out.contains(s))
        assertEquals(3, r.log.count(RedactionKind.GEO))
    }
}
