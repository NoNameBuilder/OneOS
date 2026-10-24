package com.fahadali.oneos.probe

import com.fahadali.oneos.core.device.PropReader
import com.fahadali.oneos.core.model.CarrierInfo
import com.fahadali.oneos.core.model.CarrierRules
import com.fahadali.oneos.core.model.DeviceFacts

/**
 * The carrier props for the VoLTE-only warning (FactProbe does not read
 * them), for the device report of both editions and the full edition's stage
 * check (StageCheck passes root's read as [fallback]).
 */
object CarrierProps {
    private val KEYS = listOf("ro.csc.sales_code", "ro.boot.sales_code", "ro.csc.countryiso_code", "gsm.sim.operator.iso-country")

    /** [f] with the app's own read of the props, else [fallback]; no carrier when neither says anything. */
    fun withCarrier(f: DeviceFacts, fallback: CarrierInfo? = null): DeviceFacts {
        val props = PropReader()
        val mine = KEYS.associateWith { props.get(it) }
        val carrier = if (mine.values.any { it.isNotBlank() }) CarrierRules.from(mine) else fallback
        return f.copy(carrier = carrier?.takeIf { it.known })
    }
}
