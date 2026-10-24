package com.fahadali.oneos.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.Finding
import com.fahadali.oneos.core.model.Level
import dev.oneuiproject.oneui.R as OuiIcons

/** Preference rows for the build 2 screens (the same look as the device report and Run tests). */
internal fun PreferenceFragmentCompat.category(title: String): PreferenceCategory {
    val c = PreferenceCategory(preferenceManager.context)
    c.title = title
    c.isIconSpaceReserved = false
    preferenceScreen.addPreference(c)
    return c
}

internal fun PreferenceCategory.row(
    title: CharSequence,
    summary: CharSequence?,
    icon: Drawable? = null,
    onClick: (() -> Unit)? = null,
): Preference {
    val p = Preference(context)
    p.title = title
    p.summary = summary
    p.icon = icon
    p.isIconSpaceReserved = icon != null
    if (onClick == null) {
        p.isSelectable = false
    } else {
        p.setOnPreferenceClickListener { onClick(); true }
    }
    addPreference(p)
    return p
}

/** A box the user ticks; [onChange] gets the new state. */
internal fun PreferenceCategory.tick(title: CharSequence, summary: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit): CheckBoxPreference {
    val p = CheckBoxPreference(context)
    p.title = title
    p.summary = summary
    p.isChecked = checked
    p.isIconSpaceReserved = false
    p.isPersistent = false
    p.setOnPreferenceChangeListener { _, v -> onChange(v as Boolean); true }
    addPreference(p)
    return p
}

/** An on/off row (the One UI switch); [onChange] gets the new state. Not saved: the screen keeps it. */
internal fun PreferenceCategory.toggle(title: CharSequence, summary: CharSequence?, on: Boolean, onChange: (Boolean) -> Unit): SwitchPreferenceCompat {
    val p = SwitchPreferenceCompat(context)
    p.title = title
    p.summary = summary
    p.isChecked = on
    p.isIconSpaceReserved = false
    p.isPersistent = false
    p.setOnPreferenceChangeListener { _, v -> onChange(v as Boolean); true }
    addPreference(p)
    return p
}

internal fun PreferenceCategory.finding(f: Finding): Preference = row(f.title, f.detail.ifEmpty { null }, icon = levelIcon(context, f.level))

internal fun levelIcon(ctx: Context, level: Level): Drawable? {
    val (icon, color) = when (level) {
        Level.BLOCK -> OuiIcons.drawable.ic_oui_block to R.color.verdict_block
        Level.WARN -> OuiIcons.drawable.ic_oui_info to R.color.verdict_warn
        Level.OK -> OuiIcons.drawable.ic_oui_checkbox_checked to R.color.verdict_ok
        Level.INFO -> OuiIcons.drawable.ic_oui_info_outline to R.color.verdict_info
    }
    return ctx.getDrawable(icon)?.mutate()?.apply { setTint(ctx.getColor(color)) }
}

internal fun tinted(ctx: Context, res: Int, color: Int? = null): Drawable? =
    ctx.getDrawable(res)?.mutate()?.apply { if (color != null) setTint(ctx.getColor(color)) }

internal fun colored(ctx: Context, text: CharSequence, color: Int, bold: Boolean = true): CharSequence =
    SpannableString(text).apply {
        setSpan(ForegroundColorSpan(ctx.getColor(color)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
