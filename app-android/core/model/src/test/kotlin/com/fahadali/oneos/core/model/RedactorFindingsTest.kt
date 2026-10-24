package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases from the first real findings bundle (Z Fold F8U, 0.2.0-probe,
 * 2026-10-22): values that are not personal but were removed (the OneOS team
 * needs them), and per-phone IDs that stayed. Values that identify the phone
 * are synthetic, of the same shape.
 */
class RedactorFindingsTest {
    private fun redactor() = Redactor("findings-salt".toByteArray())

    /** Not personal: the same on every phone of this model, carrier or build. */
    private val keep = """
        [bluetooth.device_id.product_id]: [0x0100]
        [bluetooth.device_id.vendor_id]: [0x0075]
        [bluetooth.device_id.version]: [0x0201]
        [cache_key.telephony.phone_account_to_subid]: [-369651778108052593]
        [mdc.omc.config_version]: [SAOMC_SM-F976B_OXM_KSA_17_0002]
        [mdc.sys.sec_cid]: [30554]
        [mdc.sys.sec_pcid]: [30554]
        [persist.sys.sec_cid]: [30554]
        [mdc.sys.sec_feature]: [30554_1]
        [persist.sys.sec_cid_ver]: [170032]
        [net.bt.name]: [Android]
        [persist.device_config.netd_native.dns_event_subsample_map]: [default:6 0:400 2:110 4:110 7:110]
        [remote_provisioning.hostname]: [remoteprovisioning.googleapis.com]
        [ro.bluetooth.library_name]: [libbluetooth_qti.so]
        [ro.bt.bdaddr_path]: [/mnt/vendor/efs/bluetooth/bt_addr]
        [ro.quick_start.device_id]: [SM-F976]
        [ro.product.model]: [SM-F976B]
        [ro.product.vendor.model]: [SM-F976B]
        [ro.boot.em.model]: [SM-F976B]
        [ril.product_code]: [SM-F976BZVOXSG]
        [selinux.policy_version]: [SEPF_SM-F976B_17_0001]
    """.trimIndent()

    /** Per-phone IDs (synthetic values). */
    private val emDid = "2041A3B5C7D9E1F3"
    private val kgAp = "ABCD1234567890123456789E123FG45H"
    private val distname = "alt.1A2345BC6D78E901F23GHI4JK56789L01M2345678901234567890ABCDE1FGH23I.samsungk"
    private val btHash = "1abcd2ef3a45bc6de7fa8bcd9ef01a2bc34d56aefcab7cd890e123456789ab0c"
    private val rfcal = "2026.03.14"
    private val serial = "R3GL123ABCD"
    private val personal = """
        [ro.boot.em.did]: [$emDid]
        [ro.boot.kg.ap]: [$kgAp]
        [ro.vendor.skeymast.distname]: [$distname]
        [persist.bluetooth.registerapptoscpm]: [$btHash]
        [ril.rfcal_date]: [$rfcal]
        [ro.serialno]: [$serial]
    """.trimIndent()

    @Test
    fun modelCarrierAndBuildValuesStay() {
        val r = redactor()
        val out = r.redactGetprop(keep + "\n" + personal, "props/getprop.txt")
        for (line in keep.lines()) assertTrue("removed: $line\n$out", out.lines().contains(line))
    }

    @Test
    fun perPhoneIdsGo() {
        val r = redactor()
        val out = r.redactGetprop(keep + "\n" + personal, "props/getprop.txt")
        for (v in listOf(emDid, kgAp, distname, btHash, rfcal, serial)) {
            assertFalse("kept: $v\n$out", out.contains(v))
        }
        // Not even a recognisable piece of the long ones.
        assertFalse(out.contains("1A2345BC6D78E901F23GHI4JK"))
    }

    @Test
    fun refreshRatesAreNotLocations() {
        val r = redactor()
        val line = "DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:4630946722019192211\", 2256 x 2504, " +
            "modeId 1, renderFrameRate 120.00002, supportedRefreshRates [120.00002, 80.000015, 60.00001, " +
            "48.000008, 40.000008, 34.28572, 30.000006, 26.666672, 24.000004, 21.818184, 20.000004], " +
            "defaultModeId 1, density 420, 421.34116 x 421.20264 dpi"
        assertEquals(line, r.redactText(line, "display/displaymanager.txt"))
        assertEquals(0, r.log.total)
    }

    @Test
    fun oatAndHalFileNamesAreNotEmails() {
        val r = redactor()
        val text = """
            -rw-r--r-- 1 root root   258672 2022-01-01 03:00 apex@com.android.adservices@javalib@service-adservices.jar@classes.odex
            -rw-r--r-- 1 root root     8192 2022-01-01 03:00 apex@com.android.adservices@javalib@service-adservices.jar@classes.odex.fsv_meta
            -rw-r--r-- 1 root root  1048576 2022-01-01 03:00 system@framework@services.jar@classes.vdex
            android.hardware.bluetooth@aidl-service-qti.rc
            samsung.software.media.c2@1.0-service.rc
            vendor.qti.hardware.minkipcbinder@1.0-service.rc
            vendor.samsung.hardware.media.converter@1.0-service.rc
        """.trimIndent()
        assertEquals(text, r.redactText(text, "installd/jars.txt"))
        assertEquals(0, r.log.count(RedactionKind.EMAIL))
    }

    @Test
    fun defaultBluetoothNameIsNotRemovedEverywhere() {
        val r = redactor()
        r.redactGetprop("[net.bt.name]: [Android]", "props/getprop.txt")
        val xml = "  ~ Copyright (C) 2021 The Android Open Source Project"
        assertEquals(xml, r.redactText(xml, "display/vendor_etc_devicestate_device_state_configuration.xml"))
    }

    @Test
    fun theFixesKeepRealPersonalValuesOut() {
        val r = redactor()
        r.redactGetprop("[net.bt.name]: [Alex's Fold8]", "props/getprop.txt")
        val text = "paired with Alex's Fold8; mail alex.doe@example.com; fix lat=52.516300 lon=13.377700"
        val out = r.redactText(text, "x.txt")
        for (v in listOf("Alex's Fold8", "alex.doe@example.com", "52.516300", "13.377700")) {
            assertFalse("kept: $v\n$out", out.contains(v))
        }
    }
}
