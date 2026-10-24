package com.fahadali.oneos.core.model

import java.util.Locale
import kotlin.math.hypot

enum class FormFactor(val label: String) {
    BAR("Bar (no fold)"),
    FOLD("Fold"),
    FLIP("Flip"),
}

/**
 * Samsung model-number families (docs/app/compat-safety-logs.md section 1.2):
 * SM-F9xx Z Fold, SM-F7xx Z Flip, SM-S9x8 S Ultra (S928, S938, S948), other
 * SM-S9xx S series. The owner's targets are the flagships Fold, Flip, S Ultra;
 * another model is in scope only through an explicit profile (from 0.3.4 the
 * Galaxy S25, [CompatEvaluator.inScope]).
 */
enum class ModelFamily(val label: String, val expectedForm: FormFactor?) {
    Z_FOLD("Galaxy Z Fold", FormFactor.FOLD),
    Z_FLIP("Galaxy Z Flip", FormFactor.FLIP),
    S_ULTRA("Galaxy S Ultra", FormFactor.BAR),
    S_SERIES("Galaxy S", FormFactor.BAR),
    OTHER("Other model", null),
    ;

    /** Samsung flagships by model number: Z Fold, Z Flip, S Ultra (a profile adds single phones, [CompatEvaluator.inScope]). */
    val inScope: Boolean get() = this == Z_FOLD || this == Z_FLIP || this == S_ULTRA

    companion object {
        private val FOLD_RE = Regex("^SM-F9\\d{2}")
        private val FLIP_RE = Regex("^SM-F7\\d{2}")
        private val S_ULTRA_RE = Regex("^SM-S9\\d8")
        private val S_RE = Regex("^SM-S9\\d{2}")

        fun of(model: String): ModelFamily {
            val m = model.trim().uppercase(Locale.ROOT)
            return when {
                FOLD_RE.containsMatchIn(m) -> Z_FOLD
                FLIP_RE.containsMatchIn(m) -> Z_FLIP
                S_ULTRA_RE.containsMatchIn(m) -> S_ULTRA
                S_RE.containsMatchIn(m) -> S_SERIES
                else -> OTHER
            }
        }
    }
}

/** One built-in display as the app sees it (no ports without root). */
data class Panel(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val xdpi: Float,
    val ydpi: Float,
) {
    /** Physical diagonal from pixels and dpi; null when the dpi is not reported. */
    val diagonalInches: Double?
        get() = if (widthPx > 0 && heightPx > 0 && xdpi > 1f && ydpi > 1f) {
            hypot(widthPx / xdpi.toDouble(), heightPx / ydpi.toDouble())
        } else {
            null
        }
}

/** Raw hardware hints the form-factor rule needs. */
data class FormSignals(
    val model: String,
    val panels: List<Panel>,
    /**
     * True when [panels] is the full built-in display list (Android 16 QPR2+
     * built-in category, which also lists the panel that is off right now).
     * False when only the active display(s) were visible, so one panel there
     * proves nothing.
     */
    val panelsComplete: Boolean,
    /** Hinge angle sensor present; null when it could not be read. */
    val hingeSensor: Boolean?,
    val inputDeviceNames: List<String>,
)

data class FormResult(
    /** The answer used everywhere else. */
    val form: FormFactor,
    /** What the hardware alone says; null when it could not decide. */
    val hardwareForm: FormFactor?,
    /** True when the hardware shows a hinge, a fold lid or two panels. */
    val foldableHardware: Boolean,
    /** What the model number says; null for models outside the known families. */
    val prefixForm: FormFactor?,
    /** Hardware and model number disagree (WARN; the hardware answer wins). */
    val mismatch: Boolean,
    val evidence: List<String>,
)

/**
 * Form factor (docs/app/compat-safety-logs.md section 1.2): decided from the
 * hardware, then cross-checked with the model prefix.
 *
 * 1. Foldable when there is a lid switch input device named `flip` (Z Fold and
 *    Z Flip; `hall`/`certify_hall` also exist on bar phones for covers, so they
 *    do not count), a hinge angle sensor, or two built-in panels.
 * 2. Foldable with two measurable panels: the smaller panel under 4.5" is a
 *    Flip cover screen, otherwise Fold. Without sizes the model prefix picks.
 * 3. Not foldable and the hardware list is trustworthy: bar.
 * 4. A disagreement with the model prefix is a WARN and the hardware wins.
 */
object FormFactorRules {
    const val FLIP_COVER_MAX_INCHES = 4.5

    fun hasFoldLid(inputDeviceNames: List<String>): Boolean =
        inputDeviceNames.any { it.trim().equals("flip", ignoreCase = true) }

    fun resolve(s: FormSignals): FormResult {
        val evidence = mutableListOf<String>()
        val lid = hasFoldLid(s.inputDeviceNames)
        if (lid) evidence += "lid switch input device 'flip'"
        when (s.hingeSensor) {
            true -> evidence += "hinge angle sensor"
            false -> evidence += "no hinge angle sensor"
            null -> Unit
        }
        val n = s.panels.size
        evidence += if (s.panelsComplete) {
            "$n built-in display${if (n == 1) "" else "s"}"
        } else {
            "$n active display${if (n == 1) "" else "s"} visible"
        }

        val foldable = lid || s.hingeSensor == true || n >= 2
        val prefixForm = ModelFamily.of(s.model).expectedForm

        val hardwareForm: FormFactor? = when {
            foldable -> foldSubtype(s.panels, evidence)
            s.hingeSensor == false || s.panelsComplete -> FormFactor.BAR
            else -> null
        }

        val form = hardwareForm ?: if (foldable) {
            prefixForm?.takeIf { it != FormFactor.BAR } ?: FormFactor.FOLD
        } else {
            prefixForm ?: FormFactor.BAR
        }

        val mismatch = when {
            prefixForm == null -> false
            hardwareForm != null -> hardwareForm != prefixForm
            foldable -> prefixForm == FormFactor.BAR
            else -> false
        }

        return FormResult(form, hardwareForm, foldable, prefixForm, mismatch, evidence)
    }

    /** FOLD/FLIP from the smallest panel; null when fewer than two panels have a size. */
    private fun foldSubtype(panels: List<Panel>, evidence: MutableList<String>): FormFactor? {
        val sizes = panels.mapNotNull { it.diagonalInches }
        if (sizes.size < 2) return null
        val smallest = sizes.min()
        val flip = smallest < FLIP_COVER_MAX_INCHES
        evidence += "smaller panel %.1f in (%s %.1f in)".format(
            Locale.ROOT,
            smallest,
            if (flip) "under" else "at least",
            FLIP_COVER_MAX_INCHES,
        )
        return if (flip) FormFactor.FLIP else FormFactor.FOLD
    }
}
