package com.leqwiss.dockcontrol

import android.content.Context
import android.provider.Settings

/**
 * Single source of truth for the "is the dock hidden" flag.
 *
 * The value lives in [Settings.Secure] under [KEY]. That table is world-readable, so the
 * launcher process can read it without any permission, while only root (Magisk module,
 * `adb shell`, or the module's own UI via `su`) can write it.
 *
 * The value is read exactly once per launcher process and cached in a volatile field:
 *  - one ContentProvider round-trip per process start is below any measurable threshold;
 *  - no polling, no listeners, no wakeups.
 *
 * After a toggle the launcher has to be restarted (the Magisk action and the in-app buttons
 * do that automatically) so the new value is picked up.
 */
internal object DockState {

    const val KEY = "evolution_dock_hidden"

    @Volatile
    private var cached: Boolean? = null

    /**
     * @return true when the dock must be hidden.
     *
     * Defaults to `true` (module active = dock hidden) and also defaults to `true` when the
     * setting cannot be read, so a broken/absent setting never re-enables the dock silently.
     */
    fun isHidden(context: Context?): Boolean {
        cached?.let { return it }

        val value = try {
            val ctx = context ?: return true
            Settings.Secure.getInt(ctx.contentResolver, KEY, DEFAULT_HIDDEN) != 0
        } catch (t: Throwable) {
            DEFAULT_HIDDEN
        }

        cached = value
        return value
    }

    private const val DEFAULT_HIDDEN = true
}
