package com.fahadali.oneos.core.model

import java.util.Locale

/**
 * One image a profile may run, with the file set staged next to it (built
 * from that image by scripts/make-overlays.sh). The staged image decides the
 * set: never another set with that image, never this set with another image.
 */
data class ImageChoice(
    /** [ImageRef.id]. */
    val imageId: String,
    /** [PackageSet.id] staged next to that image on this phone. */
    val packageSetId: String,
    /** A session has run on this phone with this image; false = the untested tick at Switch. */
    val tested: Boolean,
)

/**
 * A phone this build has OS images and file sets for (build 2, AA3). A
 * phone matches only when its model number starts with [modelPrefix] AND
 * `ro.product.device` is [codename] AND its SoC model is [socModel]. The probe
 * builds matched model OR codename (the old TestedDevice), which would have
 * let an Exynos variant or a renamed model through.
 */
data class PhoneProfile(
    /** Stable id: `ONEOS_PROFILE` in session.env, the stage marker, logs. */
    val id: String,
    /** "Galaxy S26 Ultra". */
    val label: String,
    /** `SM-S948`: every regional variant (U, B, N, W, 0) starts with it. */
    val modelPrefix: String,
    /** `ro.product.device`, e.g. `m3q`. */
    val codename: String,
    /** `ro.soc.model` (or the model of `ro.board.platform` when the prop is empty), e.g. `SM8850`. */
    val socModel: String,
    /** BAR profiles get the phone form in session.env and no fold steps. */
    val form: FormFactor,
    /** The images this phone may run, each with its set (0.3.3: more than one); the first is its usual one. */
    val choices: List<ImageChoice>,
    /** False: known, but this build refuses to switch ([disabledReason] is the BLOCK). */
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    /** Extra switch-time warnings for this phone (no tick needed). */
    val notes: List<Finding> = emptyList(),
) {
    fun matches(model: String, device: String, soc: String): Boolean =
        model.trim().uppercase(Locale.ROOT).startsWith(modelPrefix) &&
            device.trim().equals(codename, ignoreCase = true) &&
            soc.trim().equals(socModel, ignoreCase = true)

    fun matches(f: DeviceFacts): Boolean = matches(f.model, f.device, f.soc.model)

    /** The choice for [imageId]; null = this phone does not take that image. */
    fun choice(imageId: String): ImageChoice? = choices.firstOrNull { it.imageId == imageId }

    /** The images of [choices] this build has, in order. */
    fun images(all: List<ImageRef> = Images.ALL): List<ImageRef> = choices.mapNotNull { Images.byId(it.imageId, all) }

    /** The choice "Choose a system" preselects: the first one that has run on this phone, else the first. */
    val recommended: ImageChoice? get() = choices.firstOrNull { it.tested } ?: choices.firstOrNull()

    /** Some image has run on this phone (the device report's "Tested phone"). */
    val testedWithAnyImage: Boolean get() = choices.any { it.tested }

    /** "SM-S948* / m3q / SM8850". */
    val ids: String get() = "$modelPrefix* / $codename / $socModel"
}

/** [profile] is the one profile all three ids match; null when none (or, fail closed, more than one) does. */
data class ProfileResolution(
    val profile: PhoneProfile?,
    /** When nothing matched: the profile with the same model prefix (else codename), for the message. */
    val nearest: PhoneProfile?,
    /** What differs from [nearest], e.g. "codename pa3q (profile: m3q)". */
    val mismatches: List<String>,
)

/** The phones build 2 knows (PLAN.md "Profiles"). Built in; fix packs take this over later. */
object Profiles {
    const val ID_F8U = "f8u"
    const val ID_S_ULTRA_8850 = "s-ultra-8850"
    const val ID_S_ULTRA_8750 = "s-ultra-8750"
    const val ID_S_8750 = "s-8750"

    /** The SM8750 phones' note ([PhoneProfile.notes]) and its [Finding.reason]. */
    const val ID_NOTE_OLDER_VENDOR = "profile-vendor-older"
    const val R_OLDER_VENDOR = "older-vendor"

    /**
     * Both S Ultra profiles and the Galaxy S25: the Find X9 Ultra CN build,
     * tested on all three (owner, 2026-10-25; 0.3.3-0.3.6 also took the IN build,
     * gone in 0.3.7). The set is
     * named for the S Ultra but holds nothing per phone beyond the bar form (no
     * fold grid).
     */
    private val S_ULTRA_CHOICES = listOf(
        ImageChoice(Images.ID_FIND_X9U, PackageSets.ID_X9U_SULTRA, tested = true),
    )

    /** The SM8750 phones (S25 Ultra, S25): vendor API 202404 and kernel 6.6. */
    private val OLDER_VENDOR = Finding(
        ID_NOTE_OLDER_VENDOR, Level.WARN, "Older vendor software",
        "This phone has vendor API 202404 and kernel 6.6; the other systems' display path has only run on " +
            "vendor API 202504 (Snapdragon 8 Elite Gen 5).",
        R_OLDER_VENDOR,
    )

    val F8U = PhoneProfile(
        id = ID_F8U,
        label = "Z Fold (F8U)",
        modelPrefix = "SM-F976",
        codename = "q8q",
        socModel = "SM8850",
        form = FormFactor.FOLD,
        choices = listOf(
            ImageChoice(Images.ID_FIND_N6, PackageSets.ID_N6_F8U, tested = true),
            // 0.3.7: the Pixel 11 Fold image with its set, on this phone only.
            // Tested: runs 41-44 booted it here (staged over adb by
            // scripts/deploy-phone.sh, not yet from the app).
            ImageChoice(Images.ID_PIXEL_YOGI, PackageSets.ID_PIXEL_F8U, tested = true),
        ),
    )

    val S_ULTRA_8850 = PhoneProfile(
        id = ID_S_ULTRA_8850,
        label = "Galaxy S26 Ultra",
        modelPrefix = "SM-S948",
        codename = "m3q",
        socModel = "SM8850",
        form = FormFactor.BAR,
        choices = S_ULTRA_CHOICES,
    )

    val S_ULTRA_8750 = PhoneProfile(
        id = ID_S_ULTRA_8750,
        label = "Galaxy S25 Ultra",
        modelPrefix = "SM-S938",
        codename = "pa3q",
        socModel = "SM8750",
        form = FormFactor.BAR,
        // Off in 0.3.0 (the S26 Ultra was to go first); on from 0.3.1 (owner: the
        // friend's S25 Ultra is on Android 17 and ready). Untested: ticks as the S26 Ultra.
        choices = S_ULTRA_CHOICES,
        notes = listOf(OLDER_VENDOR),
    )

    /**
     * Galaxy S25 (base), from 0.3.4: a friend's probe findings of 2026-10-23
     * (SM-S931B, pa1q, SM8750, One UI 9.0, Android 17, one 1080 x 2340
     * panel, no hinge, installd table identical to switcher/patches/
     * host_installd.txt, 85 methods). Not an S Ultra, so its explicit profile
     * is what puts it in scope
     * ([CompatEvaluator.inScope]); it takes the S Ultra image and set, untested.
     */
    val S_8750 = PhoneProfile(
        id = ID_S_8750,
        label = "Galaxy S25",
        modelPrefix = "SM-S931",
        codename = "pa1q",
        socModel = "SM8750",
        form = FormFactor.BAR,
        choices = S_ULTRA_CHOICES,
        notes = listOf(OLDER_VENDOR),
    )

    val BUILT_IN: List<PhoneProfile> = listOf(F8U, S_ULTRA_8850, S_ULTRA_8750, S_8750)

    /** `ro.build.version.oneui` values seen on profiled phones (all One UI 9.0). */
    val ONE_UI_SEEN: Set<Int> = setOf(90000)

    fun byId(id: String, profiles: List<PhoneProfile> = BUILT_IN): PhoneProfile? = profiles.firstOrNull { it.id == id }

    fun resolve(f: DeviceFacts, profiles: List<PhoneProfile> = BUILT_IN): ProfileResolution =
        resolve(f.model, f.device, f.soc.model, profiles)

    fun resolve(model: String, device: String, soc: String, profiles: List<PhoneProfile> = BUILT_IN): ProfileResolution {
        val hits = profiles.filter { it.matches(model, device, soc) }
        if (hits.size == 1) return ProfileResolution(hits.single(), null, emptyList())
        if (hits.size > 1) {
            return ProfileResolution(null, null, listOf("matches ${hits.size} profiles (${hits.joinToString { it.id }})"))
        }
        val m = model.trim().uppercase(Locale.ROOT)
        val nearest = profiles.firstOrNull { m.startsWith(it.modelPrefix) }
            ?: profiles.firstOrNull { device.trim().equals(it.codename, ignoreCase = true) }
            ?: return ProfileResolution(null, null, emptyList())
        val diff = mutableListOf<String>()
        if (!m.startsWith(nearest.modelPrefix)) diff += "model ${model.trim()} (profile: ${nearest.modelPrefix}*)"
        if (!device.trim().equals(nearest.codename, ignoreCase = true)) {
            diff += "codename ${device.trim().ifEmpty { "unknown" }} (profile: ${nearest.codename})"
        }
        if (!soc.trim().equals(nearest.socModel, ignoreCase = true)) {
            diff += "chip ${soc.trim().ifEmpty { "unknown" }} (profile: ${nearest.socModel})"
        }
        return ProfileResolution(null, nearest, diff)
    }
}

/** Android release names for the SDK levels OneOS meets. */
object AndroidRelease {
    fun name(sdk: Int): String = when (sdk) {
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        37 -> "17"
        else -> "SDK $sdk"
    }
}
