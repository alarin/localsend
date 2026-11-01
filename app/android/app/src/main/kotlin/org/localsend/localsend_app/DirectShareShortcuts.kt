package org.localsend.localsend_app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/// Publishes the favorite devices as direct share targets, so the system share sheet
/// offers them as individual rows that send straight to one device.
object DirectShareShortcuts {
    const val CATEGORY = "org.localsend.localsend_app.DIRECT_SHARE"

    /// Replaces all dynamic shortcuts with the given favorites (at most 4,
    /// the practical direct share limit).
    fun publish(context: Context, favorites: List<Map<String, String>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return

        val shortcuts = favorites.take(4).mapIndexed { index, favorite ->
            val fingerprint = favorite["fingerprint"]!!
            val alias = favorite["alias"]!!
            ShortcutInfo.Builder(context, fingerprint)
                .setShortLabel(alias)
                .setLongLived(true)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setCategories(setOf(CATEGORY))
                .setRank(index)
                .setIntent(
                    Intent(context, DirectShareActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        putExtra(DirectShareHost.EXTRA_FINGERPRINT, fingerprint)
                    }
                )
                .build()
        }

        shortcutManager.dynamicShortcuts = shortcuts
    }
}
