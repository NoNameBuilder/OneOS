package com.fahadali.oneos.guest

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Back to One UI": opens [MainActivity]'s confirmation
 * (the same question as the button), never returns by itself.
 */
class ReturnTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            // An action, not a switch.
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            subtitle = getString(R.string.tile_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openConfirmation() } else openConfirmation()
    }

    private fun openConfirmation() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REASON, ReturnRequest.REASON_TILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: the Intent overload throws for apps that target 34 or later.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            collapseBefore14(intent)
        }
    }

    /** Android 12 and 13 (minSdk 31) have only the Intent overload. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun collapseBefore14(intent: Intent) = startActivityAndCollapse(intent)
}
