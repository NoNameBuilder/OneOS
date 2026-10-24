package com.fahadali.oneos.core.model

import java.util.Locale

/** The carrier props the call warning reads (all may be empty). */
data class CarrierInfo(
    /** `ro.csc.sales_code`, e.g. `ATT`, `KSA`, `EUX`. */
    val salesCode: String = "",
    /** `ro.boot.sales_code`. */
    val bootSalesCode: String = "",
    /** `ro.csc.countryiso_code`, e.g. `US`. */
    val countryIso: String = "",
    /** `gsm.sim.operator.iso-country`, one entry per SIM slot (empty = no SIM there). */
    val simCountries: List<String> = emptyList(),
) {
    val known: Boolean get() = listOf(salesCode, bootSalesCode, countryIso).any { it.isNotBlank() }
}

/**
 * VoLTE-only carriers (DA10, owner decision for 0.3.0). The other system
 * cannot use One UI's IMS, so its calls fall back to 2G/3G
 * (docs/coloros-on-fold.md); US carriers have neither, so no call connects,
 * emergency calls included.
 *
 * VoLTE-only: `ro.csc.sales_code` or `ro.boot.sales_code` is a US carrier
 * code and a SIM is from the US, or `ro.csc.countryiso_code` is US. That is a
 * WARN the user must tick before Switch, with [noCallsAck] word for word.
 * When the props cannot be read, the same tick is asked with [maybeCallsAck].
 * The texts name the system the switch starts (`{system}`, [Images.systemName]),
 * so another system asks again (owner, 2026-10-24: once per phone and system).
 */
object CarrierRules {
    val US_VOLTE_ONLY_SALES_CODES: Set<String> = setOf("ATT", "TMB", "VZW", "USC", "AIO", "SPR", "XAA")

    const val ID = "gate-carrier"

    /** [Finding.reason]: a VoLTE-only carrier (WARN); not read (WARN); calls over 2G or 3G (INFO). */
    const val R_VOLTE_ONLY = "volte-only"
    const val R_UNKNOWN = "unknown"
    const val R_2G_3G = "2g-3g"

    /** The owner's text for a VoLTE-only carrier; the Switch dialog shows it as the box to tick. */
    fun noCallsAck(system: String = Images.OTHER_SYSTEM): String =
        "I understand phone calls, including emergency calls, won't work in $system"

    /** The same tick when the carrier could not be read. */
    fun maybeCallsAck(system: String = Images.OTHER_SYSTEM): String =
        "I understand phone calls, including emergency calls, may not work in $system"

    fun from(props: Map<String, String>): CarrierInfo = CarrierInfo(
        salesCode = props["ro.csc.sales_code"].orEmpty().trim(),
        bootSalesCode = props["ro.boot.sales_code"].orEmpty().trim(),
        countryIso = props["ro.csc.countryiso_code"].orEmpty().trim(),
        simCountries = props["gsm.sim.operator.iso-country"].orEmpty().split(',').map { it.trim() },
    )

    /** True = VoLTE-only; false = not a known VoLTE-only carrier; null = could not tell. */
    fun voLteOnly(c: CarrierInfo): Boolean? {
        if (c.countryIso.equals("US", ignoreCase = true)) return true
        val usCode = listOf(c.salesCode, c.bootSalesCode).any { it.uppercase(Locale.ROOT) in US_VOLTE_ONLY_SALES_CODES }
        val sims = c.simCountries.filter { it.isNotBlank() }
        if (usCode && sims.any { it.equals("us", ignoreCase = true) }) return true
        if (usCode && sims.isEmpty()) return null
        return if (c.known) false else null
    }

    private fun code(c: CarrierInfo): String =
        listOf(c.salesCode, c.bootSalesCode).firstOrNull { it.isNotBlank() } ?: "unknown"

    /** WARN for VoLTE-only or unknown, INFO otherwise, for [system]; see [ackText] for the tick. */
    fun finding(c: CarrierInfo?, system: String = Images.OTHER_SYSTEM): Finding {
        val sys = Sentence.start(system)
        return when (if (c == null) null else voLteOnly(c)) {
            true -> Finding(
                ID, Level.WARN, "No phone calls on this carrier",
                "US carriers (here ${code(c!!)}) carry calls over VoLTE only, and $system cannot use One UI's " +
                    "VoLTE: no call connects in $system, emergency calls included. Volume Up+Down returns to One UI.",
                R_VOLTE_ONLY,
            )
            null -> Finding(
                ID, Level.WARN, "Carrier not checked",
                "Could not read the carrier. $sys cannot use One UI's VoLTE, so calls in $system need 2G or 3G; " +
                    "on a VoLTE-only carrier no call connects, emergency calls included.",
                R_UNKNOWN,
            )
            false -> Finding(
                ID, Level.INFO, "Calls use 2G or 3G",
                "$sys cannot use One UI's VoLTE, so calls in $system go over 2G or 3G (carrier ${code(c!!)}). " +
                    "Where neither is available, calls do not connect. Volume Up+Down returns to One UI.",
                R_2G_3G,
            )
        }
    }

    /** The text the user must tick before switching to [system]; null = no tick needed. */
    fun ackText(c: CarrierInfo?, system: String = Images.OTHER_SYSTEM): String? = when (if (c == null) null else voLteOnly(c)) {
        true -> noCallsAck(system)
        null -> maybeCallsAck(system)
        false -> null
    }
}
