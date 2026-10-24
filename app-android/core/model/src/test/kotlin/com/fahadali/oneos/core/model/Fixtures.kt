package com.fahadali.oneos.core.model

/** Synthetic fact sets. Values for the F8U come from compat-safety-logs.md section 1.1. */
object Fixtures {
    val F8U_INNER = Panel(0, "Built-in Screen", 2256, 2504, 420f, 420f)
    val F8U_COVER = Panel(1, "Built-in Screen", 1080, 2520, 420f, 420f)

    val F8U_SOC = SocProps(
        manufacturer = "QTI", model = "SM8850", platform = "canoe", hardware = "qcom", egl = "adreno",
    )

    fun facts(
        brand: String = "samsung",
        model: String = "SM-F976B",
        device: String = "q8q",
        soc: SocProps = F8U_SOC,
        oneUi: String = "90000",
        sdk: Int = 37,
        abis64: List<String> = listOf("arm64-v8a"),
        panels: List<Panel> = listOf(F8U_INNER, F8U_COVER),
        panelsComplete: Boolean = true,
        hinge: Boolean? = true,
        inputs: List<String> = listOf("flip", "certify_hall", "sec_touchscreen", "sec_touchscreen2"),
        root: RootSignals = RootSignals(managerPackages = listOf("me.weishu.kernelsu")),
        storage: StorageInfo? = StorageInfo(407_000_000_000, 481_000_000_000),
        battery: BatteryInfo? = BatteryInfo(80, false, 30f),
        screenLock: Boolean? = false,
        cpu: CpuInfo? = CpuInfoParser.parse(SM8850_CPUINFO),
    ): DeviceFacts {
        val form = FormFactorRules.resolve(FormSignals(model, panels, panelsComplete, hinge, inputs))
        return DeviceFacts(
            brand = brand,
            manufacturer = brand,
            model = model,
            device = device,
            soc = SocRules.resolve(soc),
            socProps = soc,
            oneUiRaw = oneUi,
            androidRelease = "17",
            sdk = sdk,
            buildId = "CP2A.260605.016",
            fingerprint = "samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXS3AZIJ:user/release-keys",
            vendorApiLevel = "202504",
            abis64 = abis64,
            form = form,
            panels = panels,
            panelsComplete = panelsComplete,
            root = root,
            storage = storage,
            battery = battery,
            flashLocked = "1",
            verifiedBootState = "green",
            screenLockSet = screenLock,
            kernel = "6.12.23",
            cpu = cpu,
        )
    }

    private const val BASE_FEATURES =
        "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc " +
            "dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm sb paca pacg dcpodp flagm2 " +
            "frint i8mm bf16 dgh rng bti ecv afp wfxt"

    /** Real: the tested Z Fold (SM8850) runs ColorOS with these: SVE/SVE2 and SME, no SME2 or MOPS. */
    const val SM8850_FEATURES =
        "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc " +
            "dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp " +
            "sve2 sveaes svepmull svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 rng bti ecv afp " +
            "rpres sme smei8i32 smef16f32 smeb16f32 smef32f32 lrcpc3"

    /** Synthetic: an Armv8.7-like CPU without SVE or SME. */
    const val NO_SVE_FEATURES = BASE_FEATURES

    fun cpuinfo(features: String, cores: Int = 8, implementer: String = "0x51", parts: List<String> = listOf("0x002", "0x001")) =
        buildString {
            for (i in 0 until cores) {
                append("processor\t: $i\n")
                append("BogoMIPS\t: 38.40\n")
                append("Features\t: $features\n")
                append("CPU implementer\t: $implementer\n")
                append("CPU architecture: 8\n")
                append("CPU variant\t: 0x3\n")
                append("CPU part\t: ${if (i < 2) parts[0] else parts.last()}\n")
                append("CPU revision\t: 2\n\n")
            }
            append("Hardware\t: Qualcomm Technologies, Inc\n")
        }

    val SM8850_CPUINFO = cpuinfo(SM8850_FEATURES)
    val NO_SVE_CPUINFO = cpuinfo(NO_SVE_FEATURES)

    /** S25 Ultra: SM-S938B, SM8750 (Snapdragon 8 Elite), One UI 9.0, SDK 37. */
    fun s25Ultra(cpu: CpuInfo? = CpuInfoParser.parse(SM8850_CPUINFO)) = facts(
        model = "SM-S938B",
        device = "pa3q",
        soc = SocProps(manufacturer = "QTI", model = "SM8750", platform = "sun", hardware = "qcom", egl = "adreno"),
        panels = listOf(Panel(0, "Built-in Screen", 1440, 3120, 500f, 500f)),
        hinge = false,
        inputs = listOf("hall", "certify_hall", "sec_touchscreen"),
        cpu = cpu,
    )

    /** S26 Ultra (SM-S948B, m3q, SM8850, One UI 9.0, SDK 37): the friends' phone. */
    fun s26Ultra(root: RootSignals = RootSignals(managerPackages = listOf("me.weishu.kernelsu"))) = facts(
        model = "SM-S948B",
        device = "m3q",
        panels = listOf(Panel(0, "Built-in Screen", 1440, 3120, 500f, 500f)),
        hinge = false,
        inputs = listOf("hall", "certify_hall", "sec_touchscreen"),
        root = root,
    )
}
