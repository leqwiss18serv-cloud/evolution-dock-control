package cloud.leqwiss.dockcontrol

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

class DockControlModule : XposedModule() {

    private val hotseatViews = CopyOnWriteArraySet<WeakReference<View>>()

    @Volatile
    private var currentStyle = DockStyle.DEFAULT

    @Volatile
    private var preferences: SharedPreferences? = null

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in TARGET_PACKAGES) return

        val loadedPreferences = runCatching {
            getRemotePreferences(PREFERENCES_GROUP)
        }.getOrNull()

        if (loadedPreferences != null) {
            preferences = loadedPreferences
            currentStyle = loadedPreferences.readStyle()
            registerPreferenceListener(loadedPreferences)
        } else {
            log(Log.WARN, TAG, "Remote preferences unavailable; using defaults")
        }

        for (className in HOTSEAT_CLASS_NAMES) {
            val hotseatClass = runCatching {
                Class.forName(className, false, param.defaultClassLoader)
            }.getOrNull() ?: continue

            var hooked = false
            runCatching {
                hooked = hookSetInsets(hotseatClass)
            }.onFailure {
                log(Log.WARN, TAG, "setInsets hook failed for $className", it)
            }

            runCatching {
                hookLayout(hotseatClass)
            }.onFailure {
                log(Log.WARN, TAG, "layout hook failed for $className", it)
            }

            if (!currentStyle.safeMode) {
                runCatching {
                    hookConstructors(hotseatClass)
                }.onFailure {
                    log(Log.WARN, TAG, "constructor hook failed for $className", it)
                }

                runCatching {
                    hookInflate(hotseatClass)
                }.onFailure {
                    log(Log.WARN, TAG, "inflate hook failed for $className", it)
                }
            }

            if (hooked) {
                log(Log.INFO, TAG, "Hooked Hotseat: $className")
            }
        }
    }

    private fun registerPreferenceListener(newPreferences: SharedPreferences) {
        runCatching {
            newPreferences.registerOnSharedPreferenceChangeListener { _, _ ->
                updateStyle()
            }
        }.onFailure {
            log(Log.WARN, TAG, "Preference listener registration failed", it)
        }
    }

    private fun updateStyle() {
        val newStyle = preferences?.readStyle() ?: return
        currentStyle = newStyle
        hotseatViews.removeIf { reference -> reference.get() == null }
        hotseatViews.forEach { reference ->
            reference.get()?.let(::applyStyle)
        }
    }

    private fun hookSetInsets(hotseatClass: Class<*>): Boolean {
        val setInsets = runCatching {
            hotseatClass.getDeclaredMethod("setInsets", Rect::class.java)
        }.getOrNull() ?: return false

        hook(setInsets)
            .setPriority(XposedInterface.PRIORITY_LOWEST)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view ->
                        hotseatViews.add(WeakReference(view))
                        applyStyle(view)
                    }
                    result
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "setInsets interception failed", throwable)
                    chain.proceed()
                }
            }
        return true
    }

    private fun hookLayout(hotseatClass: Class<*>) {
        val onLayout = runCatching {
            hotseatClass.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()

        onLayout?.let { method ->
            hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val result = chain.proceed()
                        applyMargins(chain.thisObject as? View)
                        applyDimensions(chain.thisObject as? View)
                        result
                    } catch (throwable: Throwable) {
                        log(Log.WARN, TAG, "onLayout interception failed", throwable)
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookConstructors(hotseatClass: Class<*>): Boolean {
        var hookedCount = 0
        for (constructor in hotseatClass.declaredConstructors) {
            if (constructor.isSynthetic) continue
            val hooked = runCatching {
                hook(constructor)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            val result = chain.proceed()
                            (chain.thisObject as? View)?.let { view ->
                                hotseatViews.add(WeakReference(view))
                                applyStyle(view)
                            }
                            result
                        } catch (throwable: Throwable) {
                            log(Log.WARN, TAG, "Hotseat constructor interception failed", throwable)
                            chain.proceed()
                        }
                    }
                true
            }.getOrDefault(false)
            if (hooked) hookedCount++
        }
        return hookedCount > 0
    }

    private fun hookInflate(hotseatClass: Class<*>) {
        val onFinishInflate = runCatching {
            hotseatClass.getDeclaredMethod("onFinishInflate")
        }.getOrNull()

        onFinishInflate?.let { method ->
            hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { view ->
                            hotseatViews.add(WeakReference(view))
                            applyStyle(view)
                        }
                        result
                    } catch (throwable: Throwable) {
                        log(Log.WARN, TAG, "onFinishInflate interception failed", throwable)
                        chain.proceed()
                    }
                }
        }
    }

    private fun applyStyle(view: View?) {
        if (view == null) return
        try {
            val style = currentStyle
            if (!style.enabled) {
                restoreOriginal(view)
                return
            }

            val state = view.getTag(R.id.dock_state) as? OriginalState
            if (state == null) {
                val foreignBackground = view.background
                if (!style.forceBackground && foreignBackground != null) return
                view.setTag(R.id.dock_state, OriginalState(view))
            }

            val drawable = view.background as? GradientDrawable ?: GradientDrawable()
            drawable.setColor(style.backgroundColor)
            drawable.cornerRadius = style.cornerRadiusDp * view.resources.displayMetrics.density
            view.background = drawable

            if (style.hideSearch) {
                hideQsb(view)
            } else {
                showQsb(view)
            }
            applyMargins(view)
            applyDimensions(view)
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock style failed", throwable)
        }
    }

    private fun applyMargins(view: View?) {
        if (view == null) return
        try {
            val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val style = currentStyle
            val density = view.resources.displayMetrics.density
            val parentWidth = (view.parent as? ViewGroup)?.width
                ?.takeIf { it > 0 }
                ?: view.rootView.width.takeIf { it > 0 }
                ?: 0
            val widthInset = if (parentWidth > 0) {
                (parentWidth * (100 - style.widthPercent) / 200)
            } else {
                0
            }
            val sideMargin = (style.sideMarginDp * density).toInt() + widthInset
            val bottomMargin = (style.bottomMarginDp * density).toInt()
            if (layoutParams.leftMargin != sideMargin ||
                layoutParams.topMargin != 0 ||
                layoutParams.rightMargin != sideMargin ||
                layoutParams.bottomMargin != bottomMargin
            ) {
                layoutParams.setMargins(sideMargin, 0, sideMargin, bottomMargin)
                view.layoutParams = layoutParams
            }
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock margins failed", throwable)
        }
    }

    private fun applyDimensions(view: View?) {
        if (view == null) return
        try {
            val style = currentStyle
            val layoutParams = view.layoutParams as? FrameLayout.LayoutParams ?: return
            val density = view.resources.displayMetrics.density
            val desiredHeight = if (style.heightDp > 0) {
                (style.heightDp * density).toInt()
            } else {
                view.getTag(R.id.dock_state)?.let { (it as OriginalState).height } ?: layoutParams.height
            }

            var changed = false
            if (style.adaptive || style.heightDp == 0) {
                if (layoutParams.height != desiredHeight) {
                    layoutParams.height = desiredHeight
                    changed = true
                }
            } else if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight
                changed = true
            }

            val desiredGravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            if (layoutParams.gravity != desiredGravity && style.widthPercent < 100) {
                layoutParams.gravity = desiredGravity
                changed = true
            }

            if (changed) {
                view.layoutParams = layoutParams
            }
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock dimensions failed", throwable)
        }
    }

    private fun hideQsb(view: View) {
        runCatching {
            val qsb = view.javaClass.getMethod("getQsb").invoke(view) as? View ?: return
            if (qsb.visibility == View.VISIBLE) {
                view.setTag(R.id.dock_qsb_visible, true)
            }
            qsb.visibility = View.GONE
        }
    }

    private fun showQsb(view: View) {
        runCatching {
            val qsb = view.javaClass.getMethod("getQsb").invoke(view) as? View ?: return
            val wasVisible = view.getTag(R.id.dock_qsb_visible) == true
            if (wasVisible) {
                qsb.visibility = View.VISIBLE
                view.setTag(R.id.dock_qsb_visible, false)
            }
        }
    }

    private fun restoreOriginal(view: View) {
        try {
            val state = view.getTag(R.id.dock_state) as? OriginalState ?: return
            view.background = state.background
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { layoutParams ->
                layoutParams.setMargins(state.leftMargin, state.topMargin, state.rightMargin, state.bottomMargin)
                view.layoutParams = layoutParams
            }
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { layoutParams ->
                layoutParams.gravity = state.gravity
                layoutParams.width = state.width
                layoutParams.height = state.height
                view.layoutParams = layoutParams
            }
            showQsb(view)
            view.setTag(R.id.dock_state, null as Any?)
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Restoring original dock failed", throwable)
        }
    }

    private fun SharedPreferences.readStyle(): DockStyle {
        return try {
            DockStyle(
                enabled = readBoolean(KEY_ENABLED, true),
                safeMode = readBoolean(KEY_SAFE_MODE, true),
                adaptive = readBoolean(KEY_ADAPTIVE, true),
                forceBackground = readBoolean(KEY_FORCE_BACKGROUND, true),
                hideSearch = readBoolean(KEY_HIDE_SEARCH, false),
                backgroundColor = readColor(getString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR)),
                cornerRadiusDp = readInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS, 0, 50),
                bottomMarginDp = readInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN, 0, 100),
                sideMarginDp = readInt(KEY_SIDE_MARGIN, DEFAULT_SIDE_MARGIN, 0, 100),
                widthPercent = readInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT, 50, 100),
                heightDp = readInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP, 0, 160)
            )
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Reading dock preferences failed", throwable)
            DockStyle.DEFAULT
        }
    }

    private fun SharedPreferences.readColor(rawColor: String?): Int {
        val opacity = readInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY, 0, 100)
        val parsedColor = try {
            Color.parseColor(rawColor?.trim()?.ifEmpty { DEFAULT_COLOR })
        } catch (throwable: Throwable) {
            Color.parseColor(DEFAULT_COLOR)
        }
        return Color.argb(
            (255 * opacity) / 100,
            Color.red(parsedColor),
            Color.green(parsedColor),
            Color.blue(parsedColor)
        )
    }

    private fun SharedPreferences.readInt(key: String, default: Int, minimum: Int, maximum: Int): Int {
        val value = try {
            when (val rawValue = all[key]) {
                is Number -> rawValue.toInt()
                is String -> rawValue.toDouble().toInt()
                else -> getInt(key, default)
            }
        } catch (classCastException: ClassCastException) {
            try {
                getFloat(key, default.toFloat()).toInt()
            } catch (throwable: Throwable) {
                default
            }
        } catch (throwable: Throwable) {
            default
        }
        return value.coerceIn(minimum, maximum)
    }

    private fun SharedPreferences.readBoolean(key: String, default: Boolean): Boolean {
        return try {
            when (val rawValue = all[key]) {
                is Boolean -> rawValue
                is String -> rawValue.equals("true", ignoreCase = true)
                else -> getBoolean(key, default)
            }
        } catch (throwable: Throwable) {
            default
        }
    }

    private data class OriginalState(
        val background: Drawable?,
        val width: Int,
        val height: Int,
        val gravity: Int,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int
    ) {
        constructor(view: View) : this(
            background = view.background,
            width = view.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            height = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity = (view.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.BOTTOM,
            leftMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0,
            topMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
            rightMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0,
            bottomMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        )
    }

    private data class DockStyle(
        val enabled: Boolean,
        val safeMode: Boolean,
        val adaptive: Boolean,
        val forceBackground: Boolean,
        val hideSearch: Boolean,
        val backgroundColor: Int,
        val cornerRadiusDp: Int,
        val bottomMarginDp: Int,
        val sideMarginDp: Int,
        val widthPercent: Int,
        val heightDp: Int
    ) {
        companion object {
            val DEFAULT = DockStyle(
                enabled = true,
                safeMode = true,
                adaptive = true,
                forceBackground = true,
                hideSearch = false,
                backgroundColor = Color.argb((255 * DEFAULT_OPACITY) / 100, 28, 28, 30),
                cornerRadiusDp = DEFAULT_CORNER_RADIUS,
                bottomMarginDp = DEFAULT_BOTTOM_MARGIN,
                sideMarginDp = DEFAULT_SIDE_MARGIN,
                widthPercent = DEFAULT_WIDTH_PERCENT,
                heightDp = DEFAULT_HEIGHT_DP
            )
        }
    }

    private companion object {
        const val TAG = "EvolutionDockControl"
        const val PREFERENCES_GROUP = "dock_style"
        const val KEY_ENABLED = "enabled"
        const val KEY_SAFE_MODE = "safe_mode"
        const val KEY_ADAPTIVE = "adaptive"
        const val KEY_FORCE_BACKGROUND = "force_background"
        const val KEY_HIDE_SEARCH = "hide_search"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"
        const val KEY_CORNER_RADIUS = "corner_radius"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
        const val KEY_SIDE_MARGIN = "side_margin"
        const val KEY_WIDTH_PERCENT = "width_percent"
        const val KEY_HEIGHT_DP = "height_dp"
        const val DEFAULT_COLOR = "#1C1C1E"
        const val DEFAULT_OPACITY = 82
        const val DEFAULT_CORNER_RADIUS = 32
        const val DEFAULT_BOTTOM_MARGIN = 14
        const val DEFAULT_SIDE_MARGIN = 14
        const val DEFAULT_WIDTH_PERCENT = 100
        const val DEFAULT_HEIGHT_DP = 0
        val TARGET_PACKAGES = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.google.android.launcher",
            "com.android.launcher3"
        )
        val HOTSEAT_CLASS_NAMES = listOf(
            "com.android.launcher3.Hotseat",
            "com.google.android.apps.nexuslauncher.hotseat.Hotseat",
            "com.google.android.apps.nexuslauncher.Hotseat"
        )
    }
}
