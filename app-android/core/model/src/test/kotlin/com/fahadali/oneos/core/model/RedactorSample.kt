package com.fahadali.oneos.core.model

/**
 * A realistic synthetic capture of a Samsung foldable on One UI 9 (Qualcomm),
 * shaped like the probe outputs: the full `getprop` dump, /proc/cpuinfo,
 * uname, the filtered `dumpsys display`, SurfaceFlinger, display and
 * device-state XML, getevent and /proc/bus/input/devices. Every personal value
 * is fake and listed in [secrets]; [kept] lists technical lines the OneOS team
 * needs, which must come through unchanged.
 */
object RedactorSample {
    const val SERIAL = "R5CY30XYZAB"
    const val AP_SERIAL = "0x9F1C3A7B2D40"
    const val DID = "30D2A9B1C4E5F607"
    const val UN = "W57A1B2C3D4E5F6"
    const val IMEI1 = "353928111234568"
    const val IMEI2 = "353928111234576"
    const val MEID = "A1000049D2E5F7"
    const val ICCID1 = "8996601234567890123"
    const val ICCID2 = "89966098765432109876"
    const val EID = "89049032004008882600012345678901"
    const val IMSI = "420011234567890"
    const val WIFI_MAC = "a4:c3:f0:85:ac:2d"
    const val BT_MAC = "A4:C3:F0:85:AC:2E"
    const val BT_MAC_PLAIN = "A4C3F085AC2F"
    const val BSSID = "3c:84:6a:12:34:56"
    const val TV_MAC = "FA:CE:B0:0C:12:34"
    const val SSID1 = "Fiber_Home_5G"
    const val SSID2 = "Lounge WiFi"
    const val HOTSPOT = "Alex Hotspot"
    const val PHONE_LOCAL = "0501234567"
    const val PHONE_INTL = "966551234567"
    const val PHONE_E164 = "+966 50 765 4321"
    const val EMAIL = "alex.doe@example.com"
    const val ACCOUNT = "alex_doe"
    const val USER_NAME = "Alex Doe"
    const val HOSTNAME = "Alexs-Z-Fold8"
    const val DEVICE_NAME = "Alexâ\u0080\u0099s Z Fold8" // "Alex’s Z Fold8" (UTF-8 read as Latin-1)
    const val BT_NAME = "Alex Fold8 BT"
    const val BT_KEYBOARD = "Alex's Keyboard"
    const val TV_NAME = "[TV] Alex Living Room"
    const val GEO_PAIR = "52.516275,13.377704"
    const val LAT = "52.5163"
    const val IP = "192.168.1.23"

    val secrets: List<String> = listOf(
        SERIAL, AP_SERIAL, DID, UN, IMEI1, IMEI2, MEID, ICCID1, ICCID2, EID, IMSI, WIFI_MAC, BT_MAC, BT_MAC_PLAIN,
        BSSID, TV_MAC, SSID1, SSID2, HOTSPOT, PHONE_LOCAL, PHONE_INTL, PHONE_E164, EMAIL, ACCOUNT, USER_NAME,
        HOSTNAME, DEVICE_NAME, BT_NAME, BT_KEYBOARD, TV_NAME, GEO_PAIR, LAT, IP,
        // Parts that identify on their own.
        "a4c3f085ac2d", "alex.doe", "Living Room",
    )

    val getprop = """
        [DEVICE_PROVISIONED]: [1]
        [aaudio.hw_burst_min_usec]: [2000]
        [bluetooth.device.default_name]: [Galaxy Z Fold8]
        [bluetooth.profile.a2dp.source.enabled]: [true]
        [dalvik.vm.heapsize]: [512m]
        [dev.bootcomplete]: [1]
        [dhcp.wlan0.ipaddress]: [$IP]
        [gsm.current.phone-type]: [1,1]
        [gsm.network.type]: [NR_SA,Unknown]
        [gsm.operator.alpha]: [STC,]
        [gsm.operator.iso-country]: [sa,]
        [gsm.operator.numeric]: [42001,]
        [gsm.sim.operator.alpha]: [STC,]
        [gsm.sim.operator.numeric]: [42001,]
        [gsm.sim.state]: [LOADED,ABSENT]
        [gsm.version.baseband]: [F976BXXU3AZIJ,F976BXXU3AZIJ]
        [gsm.version.ril-impl]: [Samsung RIL v5.0]
        [init.svc.adbd]: [running]
        [init.svc.macloader]: [stopped]
        [init.svc.mdnsd]: [stopped]
        [init.svc.vendor.serial_logger]: [running]
        [init.svc.vendor.imsdaemon]: [running]
        [init.svc.vendor.wifi_hal_legacy]: [running]
        [init.svc.wpa_supplicant]: [running]
        [net.bt.name]: [Android]
        [net.hostname]: [$HOSTNAME]
        [persist.bluetooth.address]: [$BT_MAC_PLAIN]
        [persist.radio.imei]: [$IMEI1]
        [persist.radio.imei2]: [$IMEI2]
        [persist.radio.multisim.config]: [dsds]
        [persist.sys.account.name]: [$ACCOUNT]
        [persist.sys.boot.reason.history]: [reboot,userrequested,1761114612
        reboot,1761000000]
        [persist.sys.dalvik.vm.lib.2]: [libart.so]
        [persist.sys.device_name]: [$DEVICE_NAME]
        [persist.sys.locale]: [ar-SA]
        [persist.sys.location.lat]: [$LAT]
        [persist.sys.sa.email]: [$EMAIL]
        [persist.sys.sim1.phonenumber]: [$PHONE_LOCAL]
        [persist.sys.timezone]: [Europe/Paris]
        [persist.sys.usb.config]: [mtp,adb]
        [persist.sys.user.name]: [$USER_NAME]
        [persist.sys.wifi.lastconnected]: [$SSID1]
        [persist.vendor.bt.name]: [$BT_NAME]
        [persist.vendor.gnss.lastfix]: [$GEO_PAIR]
        [persist.vendor.service.bdroid.bdaddr]: [$BT_MAC]
        [persist.vendor.wifi.bssid]: [$BSSID]
        [ril.approved_codever]: [F976BXXU3AZIJ]
        [ril.cdma.meid]: [$MEID]
        [ril.cs_svc]: [1]
        [ril.ecclist00]: [112,911,999,997,998,08,000,110,118,119]
        [ril.hw_ver]: [MP 0.400]
        [ril.ICC_TYPE0]: [2]
        [ril.iccid.sim1]: [$ICCID1]
        [ril.iccid.sim2]: [$ICCID2]
        [ril.matchedcsc]: [KSA]
        [ril.modem.board]: [SDX85M]
        [ril.product_code]: [SM-F976BZKAMEA]
        [ril.rfcal_date]: [2026.06.18]
        [ril.serialnumber]: [$SERIAL]
        [ril.simoperator]: [STC,]
        [ril.subinfo]: [0:-2]
        [ro.board.platform]: [sun]
        [ro.boot.ap_serial]: [$AP_SERIAL]
        [ro.boot.bootloader]: [F976BXXU3AZIJ]
        [ro.boot.carrierid]: [KSA]
        [ro.boot.em.did]: [$DID]
        [ro.boot.em.model]: [SM-F976B]
        [ro.boot.em.status]: [0x0]
        [ro.boot.flash.locked]: [0]
        [ro.boot.hardware]: [qcom]
        [ro.boot.serialno]: [$SERIAL]
        [ro.boot.un]: [$UN]
        [ro.boot.vbmeta.digest]: [5f1c2a7e9b3d4c6a8e0f1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6a]
        [ro.boot.verifiedbootstate]: [orange]
        [ro.boot.warranty_bit]: [1]
        [ro.boot.wifimacaddr]: [$WIFI_MAC]
        [ro.bootloader]: [F976BXXU3AZIJ]
        [ro.build.date]: [Wed Oct  1 21:03:44 KST 2026]
        [ro.build.date.utc]: [1759320224]
        [ro.build.display.id]: [CP2A.260605.016.F976BXXU3AZIJ]
        [ro.build.fingerprint]: [samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXU3AZIJ:user/release-keys]
        [ro.build.host]: [SWDK9705]
        [ro.build.user]: [dpi]
        [ro.build.version.oneui]: [90000]
        [ro.build.version.release]: [17]
        [ro.build.version.sdk]: [37]
        [ro.build.version.security_patch]: [2026-10-01]
        [ro.com.google.clientidbase]: [android-samsung-ss]
        [ro.csc.country_code]: [Saudi Arabia]
        [ro.csc.countryiso_code]: [SA]
        [ro.csc.sales_code]: [KSA]
        [ro.hardware]: [qcom]
        [ro.product.device]: [q8q]
        [ro.product.model]: [SM-F976B]
        [ro.product.name]: [q8qxxx]
        [ro.runtime.firstboot]: [1761114612345]
        [ro.serialno]: [$SERIAL]
        [ro.setupwizard.esim_cid_ignore]: [00000001]
        [ro.soc.manufacturer]: [QTI]
        [ro.soc.model]: [SM8850]
        [ro.vendor.qti.soc_id]: [618]
        [ro.vendor.wifi.sap.interface]: [swlan0]
        [sys.usb.config]: [mtp,adb]
        [vendor.ril.euicc.eid]: [$EID]
        [vendor.ril.imsi1]: [$IMSI]
        [vendor.ril.subscriber.number]: [$PHONE_INTL]
        [vendor.wifi.hotspot.ssid]: [$HOTSPOT]
        [vendor.wlan.driver.version]: [WLAN.HMT.1.1.c3-00042-QCAHMTSWPL_V1.0_V2.0_SILICONZ-1]
        [wifi.active.ssid]: ["$SSID2"]
        [wifi.aware.interface]: [aware_nmi0]
        [wifi.concurrent.interface]: [swlan0]
        [wifi.direct.interface]: [p2p-dev-wlan0]
        [wifi.interface]: [wlan0]
        [wifi.supplicant_scan_interval]: [15]
        [wlan.driver.status]: [ok]
    """.trimIndent() + "\n"

    val initSvc = """
        [init.svc.adbd]: [running]
        [init.svc.macloader]: [stopped]
        [init.svc.vendor.serial_logger]: [running]
        [init.svc.vendor.wifi_hal_legacy]: [running]
        [init.svc.wpa_supplicant]: [running]
    """.trimIndent() + "\n"

    val keyProps = """
        ro.build.fingerprint=samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXU3AZIJ:user/release-keys
        ro.build.version.sdk=37
        ro.build.version.oneui=90000
        ro.product.model=SM-F976B
        ro.product.device=q8q
        ro.soc.model=SM8850
        ro.board.platform=sun
        ro.boot.flash.locked=0
        ro.boot.verifiedbootstate=orange
    """.trimIndent() + "\n"

    private val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc " +
        "dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes " +
        "svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti ecv afp wfxt sme smei16i64 " +
        "smef64f64 smei8i32 smef16f32 smeb16f32 smef32f32 sme2 mops hbc"

    val cpuinfo = (0..7).joinToString("") { i ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: $features\nCPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x${if (i < 6) 3 else 4}\nCPU part\t: 0x00${if (i < 6) 2 else 1}\nCPU revision\t: 2\n\n"
    } + "Hardware\t: Qualcomm Technologies, Inc SM8850\n"

    const val UNAME =
        "Linux localhost 6.12.23-android16-5-31415926-abF976BXXU3AZIJ #1 SMP PREEMPT Wed Oct  1 21:03:44 KST 2026 aarch64 Toybox\n"

    val displayManager = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4619827259835644672", 1968 x 2184, modeId 1, renderFrameRate 120.0, defaultModeId 1, userPreferredModeId -1, modes [{id=1, width=1968, height=2184, fps=120.0, vsync=120.0}], colorMode 0, supportedColorModes [0, 7], hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[2, 3, 4], mMaxLuminance=1750.0, mMaxAverageLuminance=500.0, mMinLuminance=0.0005}, allmSupported false, gameContentTypeSupported false, density 420, 386.0 x 387.0 dpi, appVsyncOff 1000000, presDeadline 2666667, touch INTERNAL, rotation 0, type INTERNAL, address {port=129, model=0x401cecae7d6e8a}, deviceProductInfo={name=, manufacturerPnpId=QCM, productId=1, modelYear=null, manufactureDate=ManufactureDate{week=27, year=2006}, connectionToSinkType=0}, state ON, committedState ON, FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS}
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4619827259835644673", 1080 x 2520, modeId 2, type INTERNAL, address {port=130, model=0x401cecae7d6e8a}, state OFF}
        DisplayDeviceInfo{"Smart View": uniqueId="virtual:com.samsung.android.smartmirroring,1000,$TV_NAME,0", 1920 x 1080, modeId 3, type VIRTUAL, state ON}
            DisplayViewport{type=INTERNAL, valid=true, isActive=true, displayId=0, uniqueId='local:4619827259835644672', physicalPort=129, orientation=0, logicalFrame=Rect(0, 0 - 1968, 2184), physicalFrame=Rect(0, 0 - 1968, 2184), deviceWidth=1968, deviceHeight=2184}
            DisplayViewport{type=EXTERNAL, valid=true, isActive=true, displayId=2, uniqueId='wifi:$TV_MAC', physicalPort=null, orientation=0, logicalFrame=Rect(0, 0 - 1920, 1080), physicalFrame=Rect(0, 0 - 1920, 1080), deviceWidth=1920, deviceHeight=1080}
          mSupportsProtectedBuffers=true
    """.trimIndent() + "\n"

    val surfaceFlinger = """
        Display 4619827259835644672 (HWC display 0): port=129 pnpId=QCM displayName=""
        Display 4619827259835644673 (HWC display 1): port=130 pnpId=QCM displayName=""
    """.trimIndent() + "\n"

    val displayConfig = """
        <?xml version="1.0" encoding="utf-8"?>
        <displayConfiguration>
            <name>Built-In Display</name>
            <densityMapping>
                <density><height>2184</height><width>1968</width><density>420</density></density>
            </densityMapping>
            <screenBrightnessMap>
                <point><value>0.0</value><nits>2.0</nits></point>
                <point><value>0.62204725</value><nits>500.0</nits></point>
                <point><value>1.0</value><nits>1750.0</nits></point>
            </screenBrightnessMap>
            <highBrightnessMode enabled="true">
                <transitionPoint>0.62204725</transitionPoint>
                <minimumLux>10000</minimumLux>
            </highBrightnessMode>
            <screenOffBrightnessSensor>
                <type>com.samsung.sensor.light_cct</type>
                <name>TCS3720 Ambient light</name>
            </screenOffBrightnessSensor>
            <refreshRate>
                <defaultRefreshRate>60</defaultRefreshRate>
                <defaultPeakRefreshRate>120</defaultPeakRefreshRate>
            </refreshRate>
        </displayConfiguration>
    """.trimIndent() + "\n"

    val deviceState = """
        <device-state-config>
          <device-state>
            <identifier>0</identifier>
            <name>CLOSED</name>
            <conditions>
              <sensor>
                <type>android.sensor.hinge_angle</type>
                <name>Hinge Angle Sensor</name>
                <value><min-inclusive>0.0000</min-inclusive><max>5.0000</max></value>
              </sensor>
            </conditions>
          </device-state>
        </device-state-config>
    """.trimIndent() + "\n"

    /** `cmd overlay lookup` of One UI's cutout and status bar resources (display.res). */
    val frameworkRes = """
        string/config_mainBuiltInDisplayCutout=M 0,0 H -24.5 V 24.5 H 24.5 V 0 H 0 Z @dp
        string/config_mainBuiltInDisplayCutoutRectApproximation=M -40,0 H 40 V 80.25 H -40 Z @left
        string/config_mainBuiltInDisplayCutoutForUDC=M 0,0 a 26.5,26.5 0 1 1 0,53 a 26.5,26.5 0 1 1 0,-53 Z @left
        string/config_subBuiltInDisplayCutout=M 0,62 a 31,31 0 1,0 0,-62 a 31,31 0 1,0 0,62 Z @dp
        string/config_subBuiltInDisplayCutoutRectApproximation=Resource has no value
        dimen/status_bar_height_default=27.0dp
        dimen/status_bar_height_portrait=27.0dp
        dimen/rounded_corner_radius=0x0000001f
    """.trimIndent() + "\n"

    val procInputDevices = """
        I: Bus=0000 Vendor=0000 Product=0000 Version=0000
        N: Name="sec_touchscreen"
        P: Phys=sec_touchscreen/input0
        S: Sysfs=/devices/virtual/input/input2
        U: Uniq=
        H: Handlers=event2
        B: PROP=2

        I: Bus=0005 Vendor=046d Product=b342 Version=0001
        N: Name="$BT_KEYBOARD"
        P: Phys=$BT_MAC
        S: Sysfs=/devices/virtual/misc/uhid/0005:046D:B342.0001/input/input12
        U: Uniq=$BSSID
        H: Handlers=sysrq kbd event9
    """.trimIndent() + "\n"

    val getevent = """
        add device 1: /dev/input/event9
          name:     "$BT_KEYBOARD"
          events:
            KEY (0001): KEY_ESC               KEY_1                 KEY_2
          input props:
            <none>
        add device 2: /dev/input/event2
          name:     "sec_touchscreen"
          events:
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent() + "\n"

    /** The capture as FindingsBuilder sees it: output path to text. */
    val files: Map<String, String> = linkedMapOf(
        "props/getprop.txt" to getprop,
        "props/key.txt" to keyProps,
        "cpu/cpuinfo.txt" to cpuinfo,
        "cpu/uname.txt" to UNAME,
        "display/displaymanager.txt" to displayManager,
        "display/framework_res.txt" to frameworkRes,
        "display/surfaceflinger.txt" to surfaceFlinger,
        "display/vendor/etc/displayconfig/display_id_4619827259835644672.xml" to displayConfig,
        "display/vendor/etc/devicestate/device_state_configuration.xml" to deviceState,
        "input/getevent.txt" to getevent,
        "input/proc_bus_input_devices.txt" to procInputDevices,
        "hal/init_svc.txt" to initSvc,
    )

    /** Lines that carry no personal data and must come through unchanged. */
    val kept: Map<String, List<String>> = mapOf(
        "props/getprop.txt" to listOf(
            "[bluetooth.profile.a2dp.source.enabled]: [true]",
            "[gsm.operator.numeric]: [42001,]",
            "[gsm.sim.operator.alpha]: [STC,]",
            "[gsm.version.baseband]: [F976BXXU3AZIJ,F976BXXU3AZIJ]",
            "[init.svc.adbd]: [running]",
            "[init.svc.macloader]: [stopped]",
            "[init.svc.mdnsd]: [stopped]",
            "[init.svc.vendor.serial_logger]: [running]",
            "[init.svc.vendor.imsdaemon]: [running]",
            "[init.svc.wpa_supplicant]: [running]",
            "[persist.sys.locale]: [ar-SA]",
            "[persist.sys.timezone]: [Europe/Paris]",
            "[ril.ecclist00]: [112,911,999,997,998,08,000,110,118,119]",
            "[ril.product_code]: [SM-F976BZKAMEA]",
            "[ro.boot.vbmeta.digest]: [5f1c2a7e9b3d4c6a8e0f1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6a]",
            "[ro.boot.verifiedbootstate]: [orange]",
            "[ro.build.date.utc]: [1759320224]",
            "[ro.build.fingerprint]: [samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXU3AZIJ:user/release-keys]",
            "[ro.build.version.oneui]: [90000]",
            "[ro.build.version.sdk]: [37]",
            "[ro.csc.sales_code]: [KSA]",
            "[ro.product.model]: [SM-F976B]",
            "[ro.product.name]: [q8qxxx]",
            "[ro.runtime.firstboot]: [1761114612345]",
            "[ro.soc.model]: [SM8850]",
            "[ro.vendor.qti.soc_id]: [618]",
            "[ro.vendor.wifi.sap.interface]: [swlan0]",
            "[vendor.wlan.driver.version]: [WLAN.HMT.1.1.c3-00042-QCAHMTSWPL_V1.0_V2.0_SILICONZ-1]",
            "[wifi.direct.interface]: [p2p-dev-wlan0]",
            "[wifi.interface]: [wlan0]",
            "[wifi.supplicant_scan_interval]: [15]",
            "[wlan.driver.status]: [ok]",
        ),
        "props/key.txt" to keyProps.trimEnd().lines(),
        "cpu/cpuinfo.txt" to cpuinfo.trimEnd().lines(),
        "cpu/uname.txt" to listOf(UNAME.trimEnd()),
        "display/surfaceflinger.txt" to surfaceFlinger.trimEnd().lines(),
        "display/framework_res.txt" to frameworkRes.trimEnd().lines(),
        "display/vendor/etc/displayconfig/display_id_4619827259835644672.xml" to displayConfig.trimEnd().lines(),
        "display/vendor/etc/devicestate/device_state_configuration.xml" to deviceState.trimEnd().lines(),
        "display/displaymanager.txt" to displayManager.trimEnd().lines().filter { "local:" in it || "Supports" in it },
        "hal/init_svc.txt" to initSvc.trimEnd().lines(),
    )

    /** Redacts [files] the way FindingsBuilder does (Bluetooth names learned first, getprop first). */
    fun redactAll(r: Redactor): Map<String, String> {
        r.learnBluetoothInputNames(procInputDevices)
        val order = files.keys.sortedWith(compareBy({ !it.endsWith("getprop.txt") }, { it }))
        return order.associateWith { rel ->
            val text = files.getValue(rel)
            if (rel.endsWith("getprop.txt")) r.redactGetprop(text, rel) else r.redactText(text, rel)
        }
    }
}
