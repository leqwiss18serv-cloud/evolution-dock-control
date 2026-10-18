package cloud.leqwiss.dockcontrol

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
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
                hookMeasureAndLayout(hotseatClass)
            }.onFailure {
                log(Log.WARN, TAG, "layout hook failed for $className", it)
            }

            if (!currentStyle.safeMode) {
                runCatching {
                    hookConstructors(hotseatClass)
                }.onFailure {
                    log(Log.WARN, TAG, "constructor hook failed for $className", it)
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

    private fun hookMeasureAndLayout(hotseatClass: Class<*>) {
        val onMeasure = runCatching {
            hotseatClass.getDeclaredMethod(
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()

        onMeasure?.let { method ->
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
                        log(Log.WARN, TAG, "onMeasure interception failed", throwable)
                        chain.proceed()
                    }
                }
        }

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
                        (chain.thisObject as? View)?.let { view ->
                            hotseatViews.add(WeakReference(view))
                            applyStyle(view)
                        }
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
                                if (view.layoutParams != null) {
                                    applyStyle(view)
                                }
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

    private fun applyStyle(view: View?) {
        if (view == null) return
        try {
            val style = currentStyle
            if (!style.enabled) {
                restoreOriginal(view)
                return
            }

            var state = view.getTag(R.id.dock_state) as? OriginalState
            if (state == null) {
                state = OriginalState(view)
                view.setTag(R.id.dock_state, state)
            }

            if (!style.forceBackground && state.background != null) {
                applyIconOffsets(view)
                return
            }

            val density = view.resources.displayMetrics.density
            val gradient = GradientDrawable().apply {
                setColor(style.backgroundColor)
                cornerRadius = style.cornerRadiusDp * density
            }

            val parentView = view.parent as? ViewGroup
            val parentWidth = when {
                parentView != null && parentView.width > 0 -> parentView.width
                view.width > 0 -> view.width
                view.rootView.width > 0 -> view.rootView.width
                else -> 0
            }
            val horizontalInset = if (parentWidth > 0) {
                (parentWidth * (100 - style.widthPercent) / 200)
            } else {
                0
            }
            val insetLeft = horizontalInset.coerceAtLeast(0)
            val insetRight = horizontalInset.coerceAtLeast(0)
            val insetTop = 0
            val insetBottom = 0

            view.background = InsetDrawable(gradient, insetLeft, insetTop, insetRight, insetBottom)
            view.setPadding(
                state.paddingLeft + insetLeft,
                state.paddingTop + insetTop,
                state.paddingRight + insetRight,
                state.paddingBottom + insetBottom
            )

            if (style.hideSearch) {
                hideQsb(view)
            } else {
                showQsb(view)
            }

            applyMargins(view)
            applyDimensions(view)
            applyIconOffsets(view)
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock style failed", throwable)
        }
    }

    private fun applyMargins(view: View) {
        try {
            val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val style = currentStyle
            val density = view.resources.displayMetrics.density
            val leftMargin = (style.leftMarginDp * density).toInt()
            val topMargin = (style.topMarginDp * density).toInt()
            val rightMargin = (style.rightMarginDp * density).toInt()
            val bottomMargin = (style.bottomMarginDp * density).toInt()
            if (layoutParams.leftMargin != leftMargin ||
                layoutParams.topMargin != topMargin ||
                layoutParams.rightMargin != rightMargin ||
                layoutParams.bottomMargin != bottomMargin
            ) {
                layoutParams.setMargins(leftMargin, topMargin, rightMargin, bottomMargin)
                view.layoutParams = layoutParams
            }
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock margins failed", throwable)
        }
    }

    private fun applyDimensions(view: View) {
        try {
            val layoutParams = view.layoutParams as? FrameLayout.LayoutParams ?: return
            val style = currentStyle
            val density = view.resources.displayMetrics.density
            val desiredHeight = if (style.heightDp > 0) {
                (style.heightDp * density).toInt()
            } else {
                state(view)?.height ?: layoutParams.height
            }

            var changed = false
            if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight
                changed = true
            }

            val desiredGravity = gravityFor(style.dockGravity)
            if (layoutParams.gravity != desiredGravity) {
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

    private fun applyIconOffsets(view: View) {
        try {
            val container = view.javaClass.getMethod("getShortcutsAndWidgets")
                .invoke(view) as? View ?: return
            val style = currentStyle
            val density = view.resources.displayMetrics.density
            val translationX = style.iconOffsetXDp * density
            val translationY = style.iconOffsetYDp * density
            if (container.translationX != translationX) container.translationX = translationX
            if (container.translationY != translationY) container.translationY = translationY
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying icon offsets failed", throwable)
        }
    }

    private fun state(view: View): OriginalState? {
        return view.getTag(R.id.dock_state) as? OriginalState
    }

    private fun gravityFor(value: String): Int {
        return when (value) {
            GRAVITY_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            GRAVITY_LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            GRAVITY_RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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
            if (view.getTag(R.id.dock_qsb_visible) == true) {
                qsb.visibility = View.VISIBLE
                view.setTag(R.id.dock_qsb_visible, false)
            }
        }
    }

    private fun restoreOriginal(view: View) {
        try {
            val state = state(view) ?: return
            view.background = state.background
            view.setPadding(
                state.paddingLeft,
                state.paddingTop,
                state.paddingRight,
                state.paddingBottom
            )

            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { layoutParams ->
                layoutParams.setMargins(
                    state.leftMargin,
                    state.topMargin,
                    state.rightMargin,
                    state.bottomMargin
                )
                view.layoutParams = layoutParams
            }

            (view.layoutParams as? FrameLayout.LayoutParams)?.let { layoutParams ->
                layoutParams.gravity = state.gravity
                layoutParams.width = state.width
                layoutParams.height = state.height
                view.layoutParams = layoutParams
            }

            view.javaClass.getMethod("getShortcutsAndWidgets").invoke(view)?.let { container ->
                (container as? View)?.let { childContainer ->
                    childContainer.translationX = 0f
                    childContainer.translationY = 0f
                }
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
                topMarginDp = readInt(KEY_TOP_MARGIN, DEFAULT_TOP_MARGIN, 0, 100),
                bottomMarginDp = readInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN, 0, 100),
                leftMarginDp = readInt(KEY_LEFT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 100),
                rightMarginDp = readInt(KEY_RIGHT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 100),
                widthPercent = readInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT, 50, 100),
                heightDp = readInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP, 0, 200),
                iconOffsetXDp = readInt(KEY_ICON_OFFSET_X, DEFAULT_ICON_OFFSET, -50, 50),
                iconOffsetYDp = readInt(KEY_ICON_OFFSET_Y, DEFAULT_ICON_OFFSET, -50, 50),
                dockGravity = getString(KEY_DOCK_GRAVITY, GRAVITY_BOTTOM) ?: GRAVITY_BOTTOM
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
        return try {
            when (val rawValue = all[key]) {
                is Number -> rawValue.toInt()
                is String -> rawValue.toDouble().toInt()
                else -> getInt(key, default)
            }.coerceIn(minimum, maximum)
        } catch (throwable: Throwable) {
            default
        }
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
        val bottomMargin: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int
    ) {
        constructor(view: View) : this(
            background = view.background,
            width = view.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            height = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity = (view.layoutParams as? FrameLayout.LayoutParams)?.gravity
                ?: (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
            leftMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0,
            topMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
            rightMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0,
            bottomMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom
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
        val topMarginDp: Int,
        val bottomMarginDp: Int,
        val leftMarginDp: Int,
        val rightMarginDp: Int,
        val widthPercent: Int,
        val heightDp: Int,
        val iconOffsetXDp: Int,
        val iconOffsetYDp: Int,
        val dockGravity: String
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
                topMarginDp = DEFAULT_TOP_MARGIN,
                bottomMarginDp = DEFAULT_BOTTOM_MARGIN,
                leftMarginDp = DEFAULT_HORIZONTAL_MARGIN,
                rightMarginDp = DEFAULT_HORIZONTAL_MARGIN,
                widthPercent = DEFAULT_WIDTH_PERCENT,
                heightDp = DEFAULT_HEIGHT_DP,
                iconOffsetXDp = DEFAULT_ICON_OFFSET,
                iconOffsetYDp = DEFAULT_ICON_OFFSET,
                dockGravity = GRAVITY_BOTTOM
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
        const val KEY_TOP_MARGIN = "top_margin"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
        const val KEY_LEFT_MARGIN = "left_margin"
        const val KEY_RIGHT_MARGIN = "right_margin"
        const val KEY_WIDTH_PERCENT = "width_percent"
        const val KEY_HEIGHT_DP = "height_dp"
        const val KEY_ICON_OFFSET_X = "icon_offset_x"
        const val KEY_ICON_OFFSET_Y = "icon_offset_y"
        const val KEY_DOCK_GRAVITY = "dock_gravity"
        const val GRAVITY_BOTTOM = "bottom"
        const val GRAVITY_TOP = "top"
        const val GRAVITY_LEFT = "left"
        const val GRAVITY_RIGHT = "right"
        const val DEFAULT_COLOR = "#1C1C1E"
        const val DEFAULT_OPACITY = 82
        const val DEFAULT_CORNER_RADIUS = 32
        const val DEFAULT_TOP_MARGIN = 0
        const val DEFAULT_BOTTOM_MARGIN = 14
        const val DEFAULT_HORIZONTAL_MARGIN = 14
        const val DEFAULT_WIDTH_PERCENT = 100
        const val DEFAULT_HEIGHT_DP = 0
        const val DEFAULT_ICON_OFFSET = 0
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
