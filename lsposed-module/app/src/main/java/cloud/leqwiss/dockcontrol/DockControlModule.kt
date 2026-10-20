package cloud.leqwiss.dockcontrol

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
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
    private val mainHandler = Handler(Looper.getMainLooper())

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
            val hotseatClass = findClass(className, param.defaultClassLoader) ?: continue
            var hooked = false
            hooked = runCatching { hookSetInsets(hotseatClass) || hooked }.getOrDefault(hooked)
            hooked = runCatching { hookAttached(hotseatClass) || hooked }.getOrDefault(hooked)
            hooked = runCatching { hookLayout(hotseatClass) || hooked }.getOrDefault(hooked)
            hooked = runCatching { hookTouch(hotseatClass) || hooked }.getOrDefault(hooked)
            if (!currentStyle.safeMode) {
                runCatching { hookConstructors(hotseatClass) }.onFailure {
                    log(Log.WARN, TAG, "Constructor hook failed for $className", it)
                }
            }
            if (hooked) log(Log.INFO, TAG, "Hooked Hotseat: $className")
        }

        hookWorkspace(param.defaultClassLoader)
    }

    private fun findClass(name: String, loader: ClassLoader?): Class<*>? {
        return runCatching {
            Class.forName(name, false, loader ?: DockControlModule::class.java.classLoader)
        }.getOrNull()
    }

    private fun registerPreferenceListener(newPreferences: SharedPreferences) {
        runCatching {
            newPreferences.registerOnSharedPreferenceChangeListener { _, _ -> updateStyle() }
        }.onFailure {
            log(Log.WARN, TAG, "Preference listener registration failed", it)
        }
    }

    private fun updateStyle() {
        val newStyle = preferences?.readStyle() ?: return
        currentStyle = newStyle
        hotseatViews.removeIf { reference -> reference.get() == null }
        hotseatViews.forEach { reference ->
            reference.get()?.let { view ->
                applyStyle(view)
            }
        }
    }

    private fun hookSetInsets(hotseatClass: Class<*>): Boolean {
        val method = runCatching {
            hotseatClass.getDeclaredMethod("setInsets", Rect::class.java)
        }.getOrNull() ?: return false

        hook(method)
            .setPriority(XposedInterface.PRIORITY_LOWEST)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val result = chain.proceed()
                    registerHotseat(chain.thisObject)
                    result
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "setInsets interception failed", throwable)
                    chain.proceed()
                }
            }
        return true
    }

    private fun hookAttached(hotseatClass: Class<*>): Boolean {
        val method = runCatching {
            hotseatClass.getDeclaredMethod("onAttachedToWindow")
        }.getOrNull() ?: return false

        hook(method)
            .setPriority(XposedInterface.PRIORITY_LOWEST)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val result = chain.proceed()
                    registerHotseat(chain.thisObject)
                    result
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "onAttachedToWindow interception failed", throwable)
                    chain.proceed()
                }
            }
        return true
    }

    private fun hookLayout(hotseatClass: Class<*>): Boolean {
        val method = runCatching {
            hotseatClass.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull() ?: return false

        hook(method)
            .setPriority(XposedInterface.PRIORITY_LOWEST)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view ->
                        if (view.getTag(R.id.dock_state) != null) {
                            centerChildren(view)
                        }
                    }
                    result
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "onLayout interception failed", throwable)
                    chain.proceed()
                }
            }
        return true
    }

    private fun hookTouch(hotseatClass: Class<*>): Boolean {
        val method = runCatching {
            hotseatClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: return false

        hook(method)
            .setPriority(XposedInterface.PRIORITY_LOWEST)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val result = chain.proceed()
                    handleUserActivity()
                    result
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "Hotseat touch interception failed", throwable)
                    chain.proceed()
                }
            }
        return true
    }

    private fun hookConstructors(hotseatClass: Class<*>) {
        for (constructor in hotseatClass.declaredConstructors) {
            if (constructor.isSynthetic) continue
            runCatching {
                hook(constructor)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            val result = chain.proceed()
                            registerHotseat(chain.thisObject)
                            result
                        } catch (throwable: Throwable) {
                            log(Log.WARN, TAG, "Constructor interception failed", throwable)
                            chain.proceed()
                        }
                    }
            }.onFailure {
                log(Log.WARN, TAG, "Unable to hook constructor", it)
            }
        }
    }

    private fun hookWorkspace(loader: ClassLoader?) {
        for (className in WORKSPACE_CLASS_NAMES) {
            val workspaceClass = findClass(className, loader) ?: continue
            for (methodName in listOf("onInterceptTouchEvent", "onTouchEvent")) {
                val method = runCatching {
                    workspaceClass.getDeclaredMethod(methodName, MotionEvent::class.java)
                }.getOrNull() ?: continue
                runCatching {
                    hook(method)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            try {
                                val result = chain.proceed()
                                handleUserActivity()
                                result
                            } catch (throwable: Throwable) {
                                log(Log.WARN, TAG, "$methodName interception failed", throwable)
                                chain.proceed()
                            }
                        }
                }.onFailure {
                    log(Log.WARN, TAG, "Failed to hook workspace $methodName", it)
                }
            }
        }
    }

    private fun registerHotseat(subject: Any?) {
        val view = subject as? View ?: return
        hotseatViews.add(WeakReference(view))
        applyStyle(view)
    }

    private fun handleUserActivity() {
        if (currentStyle.autoHideSeconds <= 0) return
        hotseatViews.removeIf { reference -> reference.get() == null }
        hotseatViews.forEach { reference ->
            reference.get()?.let { view ->
                showDock(view)
                scheduleAutoHide(view)
            }
        }
    }

    private fun applyStyle(view: View?) {
        if (view == null || view.layoutParams == null) return
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
                applyGeometry(view, style)
                applyAutoHide(view)
                return
            }

            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.background = createBackground(style, view.resources.displayMetrics.density)
            applyRenderEffect(view, style)
            if (style.hideSearch) hideQsb(view) else showQsb(view)
            applyGeometry(view, style)
            applyAutoHide(view)
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock style failed", throwable)
        }
    }

    private fun createBackground(style: DockStyle, density: Float): Drawable {
        val cornerRadius = style.cornerRadiusDp * density
        val base = GradientDrawable().apply {
            setColor(style.backgroundColor)
            setCornerRadius(cornerRadius)
        }
        if (style.glassStyle == STYLE_SOLID) return base

        val highlightTop = if (style.glassStyle == STYLE_LIQUID) 0x36FFFFFF else 0x22FFFFFF
        val highlight = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(highlightTop, 0x02FFFFFF)
        ).apply { setCornerRadius(cornerRadius) }
        val border = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setCornerRadius(cornerRadius)
            setStroke((density * 1.1f).toInt().coerceAtLeast(1), 0x33FFFFFF)
        }
        return LayerDrawable(arrayOf(base, highlight, border))
    }

    private fun applyRenderEffect(view: View, style: DockStyle) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                style.glassStyle == STYLE_LIQUID &&
                style.realBlur &&
                style.blurRadius > 0
            ) {
                val radius = style.blurRadius.toFloat()
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            } else {
                setNoRenderEffect(view)
            }
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "RenderEffect unavailable; using matte glass fallback", throwable)
            runCatching { setNoRenderEffect(view) }
        }
    }

    private fun setNoRenderEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    private fun applyGeometry(view: View, style: DockStyle) {
        val layoutParams = view.layoutParams ?: return
        val density = view.resources.displayMetrics.density
        val parentView = view.parent as? View
        val parentWidth = parentView?.width ?: 0

        if (layoutParams is ViewGroup.MarginLayoutParams) {
            layoutParams.leftMargin = (style.leftMarginDp * density).toInt()
            layoutParams.topMargin = (style.topMarginDp * density).toInt()
            layoutParams.rightMargin = (style.rightMarginDp * density).toInt()
            layoutParams.bottomMargin = (style.bottomMarginDp * density).toInt()
        }

        if (style.adaptive) {
            if (parentWidth > 0) {
                layoutParams.width = (parentWidth * style.widthPercent / 100).coerceAtLeast(1)
            }
            layoutParams.height = if (style.heightDp > 0) {
                (style.heightDp * density).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        if (layoutParams is FrameLayout.LayoutParams) {
            layoutParams.gravity = gravityFor(style.dockGravity)
        }

        view.layoutParams = layoutParams
        centerChildren(view)
    }

    private fun centerChildren(view: View) {
        if (view !is ViewGroup) return
        val offsetX = currentStyle.iconOffsetXDp * view.resources.displayMetrics.density
        val offsetY = currentStyle.iconOffsetYDp * view.resources.displayMetrics.density
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            val childLayoutParams = child.layoutParams
            if (childLayoutParams is FrameLayout.LayoutParams) {
                childLayoutParams.gravity = Gravity.CENTER
                child.layoutParams = childLayoutParams
            }
            child.translationX = offsetX
            child.translationY = offsetY
        }
    }

    private fun hideQsb(view: View) {
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            val name = child.javaClass.name.lowercase()
            if (name.contains("qsb") || name.contains("search")) {
                child.visibility = View.GONE
            }
        }
    }

    private fun showQsb(view: View) {
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            val name = child.javaClass.name.lowercase()
            if (name.contains("qsb") || name.contains("search") && child.visibility == View.GONE) {
                child.visibility = View.VISIBLE
            }
        }
    }

    private fun applyAutoHide(view: View) {
        if (currentStyle.autoHideSeconds > 0) {
            showDock(view)
            scheduleAutoHide(view)
        } else {
            cancelAutoHide(view)
            showDock(view)
        }
    }

    private fun scheduleAutoHide(view: View) {
        cancelAutoHide(view)
        val delay = currentStyle.autoHideSeconds * 1000L
        if (delay <= 0L) return
        val runnable = Runnable {
            view.setTag(R.id.dock_hide_runnable, null)
            hideDock(view)
        }
        view.setTag(R.id.dock_hide_runnable, runnable)
        mainHandler.postDelayed(runnable, delay)
    }

    private fun cancelAutoHide(view: View) {
        val runnable = view.getTag(R.id.dock_hide_runnable) as? Runnable
        if (runnable != null) {
            mainHandler.removeCallbacks(runnable)
            view.setTag(R.id.dock_hide_runnable, null)
        }
    }

    private fun showDock(view: View) {
        val state = view.getTag(R.id.dock_state) as? OriginalState ?: return
        cancelAutoHide(view)
        view.setTag(R.id.dock_hidden, false)
        if (view.visibility == View.VISIBLE && view.alpha >= 0.99f) return
        view.animate()
            .translationY(state.translationY)
            .alpha(1f)
            .setDuration(180L)
            .withStartAction { view.visibility = View.VISIBLE }
            .start()
    }

    private fun hideDock(view: View) {
        if (view.getTag(R.id.dock_hidden) == true) return
        view.setTag(R.id.dock_hidden, true)
        view.animate()
            .translationY(view.translationY + view.height * 0.65f)
            .alpha(0f)
            .setDuration(220L)
            .withEndAction {
                if (view.getTag(R.id.dock_hidden) == true) {
                    view.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun restoreOriginal(view: View) {
        cancelAutoHide(view)
        val state = view.getTag(R.id.dock_state) as? OriginalState ?: return
        view.background = state.background
        view.setLayerType(state.layerType, null)
        setNoRenderEffect(view)
        view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
        view.translationY = state.translationY
        view.alpha = 1f
        view.visibility = View.VISIBLE
        state.restoreLayoutParams(view)
        view.requestLayout()
        view.invalidate()
    }

    private fun gravityFor(value: String): Int {
        return when (value) {
            GRAVITY_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            GRAVITY_LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            GRAVITY_RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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
                realBlur = readBoolean(KEY_REAL_BLUR, false),
                backgroundColor = readColor(),
                backgroundOpacity = readInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY, 0, 100),
                cornerRadiusDp = readInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS, 0, 50),
                topMarginDp = readInt(KEY_TOP_MARGIN, DEFAULT_TOP_MARGIN, 0, 120),
                bottomMarginDp = readInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN, 0, 120),
                leftMarginDp = readInt(KEY_LEFT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 120),
                rightMarginDp = readInt(KEY_RIGHT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 120),
                widthPercent = readInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT, 40, 100),
                heightDp = readInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP, 0, 220),
                iconOffsetXDp = readInt(KEY_ICON_OFFSET_X, DEFAULT_ICON_OFFSET, -50, 50),
                iconOffsetYDp = readInt(KEY_ICON_OFFSET_Y, DEFAULT_ICON_OFFSET, -50, 50),
                glassStyle = readString(KEY_GLASS_STYLE, STYLE_LIQUID),
                blurRadius = readInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS, 0, 50),
                autoHideSeconds = readInt(KEY_AUTO_HIDE_SECONDS, DEFAULT_AUTO_HIDE_SECONDS, 0, 60),
                dockGravity = readString(KEY_DOCK_GRAVITY, GRAVITY_BOTTOM)
            )
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Reading dock style failed; using defaults", throwable)
            DockStyle.DEFAULT
        }
    }

    private fun SharedPreferences.readColor(): Int {
        val opacity = readInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY, 0, 100)
        return try {
            val base = Color.parseColor(
                readString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR).ifEmpty { DEFAULT_COLOR }
            )
            Color.argb((255 * opacity) / 100, Color.red(base), Color.green(base), Color.blue(base))
        } catch (throwable: Throwable) {
            Color.argb((255 * opacity) / 100, 28, 28, 30)
        }
    }

    private fun SharedPreferences.readString(key: String, default: String): String {
        return try {
            all[key] as? String ?: getString(key, default) ?: default
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

    private fun SharedPreferences.readInt(key: String, default: Int, minimum: Int, maximum: Int): Int {
        return try {
            when (val rawValue = all[key]) {
                is Number -> rawValue.toInt()
                is String -> rawValue.toDoubleOrNull()?.toInt() ?: default
                else -> getInt(key, default)
            }.coerceIn(minimum, maximum)
        } catch (throwable: Throwable) {
            default
        }
    }

    private class OriginalState(view: View) {
        val background: Drawable? = view.background
        val paddingLeft: Int = view.paddingLeft
        val paddingTop: Int = view.paddingTop
        val paddingRight: Int = view.paddingRight
        val paddingBottom: Int = view.paddingBottom
        val layerType: Int = view.layerType
        val translationY: Float = view.translationY
        private val width: Int = view.layoutParams.width
        private val height: Int = view.layoutParams.height
        private val leftMargin: Int
        private val topMargin: Int
        private val rightMargin: Int
        private val bottomMargin: Int
        private val gravity: Int

        init {
            val layoutParams = view.layoutParams
            if (layoutParams is ViewGroup.MarginLayoutParams) {
                leftMargin = layoutParams.leftMargin
                topMargin = layoutParams.topMargin
                rightMargin = layoutParams.rightMargin
                bottomMargin = layoutParams.bottomMargin
            } else {
                leftMargin = 0
                topMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
            gravity = (layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.NO_GRAVITY
        }

        fun restoreLayoutParams(view: View) {
            val layoutParams = view.layoutParams ?: return
            layoutParams.width = width
            layoutParams.height = height
            if (layoutParams is ViewGroup.MarginLayoutParams) {
                layoutParams.leftMargin = leftMargin
                layoutParams.topMargin = topMargin
                layoutParams.rightMargin = rightMargin
                layoutParams.bottomMargin = bottomMargin
            }
            if (layoutParams is FrameLayout.LayoutParams && gravity != Gravity.NO_GRAVITY) {
                layoutParams.gravity = gravity
            }
            view.layoutParams = layoutParams
        }
    }

    private data class DockStyle(
        val enabled: Boolean,
        val safeMode: Boolean,
        val adaptive: Boolean,
        val forceBackground: Boolean,
        val hideSearch: Boolean,
        val realBlur: Boolean,
        val backgroundColor: Int,
        val backgroundOpacity: Int,
        val cornerRadiusDp: Int,
        val topMarginDp: Int,
        val bottomMarginDp: Int,
        val leftMarginDp: Int,
        val rightMarginDp: Int,
        val widthPercent: Int,
        val heightDp: Int,
        val iconOffsetXDp: Int,
        val iconOffsetYDp: Int,
        val glassStyle: String,
        val blurRadius: Int,
        val autoHideSeconds: Int,
        val dockGravity: String
    ) {
        companion object {
            val DEFAULT = DockStyle(
                enabled = true,
                safeMode = true,
                adaptive = true,
                forceBackground = true,
                hideSearch = false,
                realBlur = false,
                backgroundColor = Color.argb((255 * 82) / 100, 28, 28, 30),
                backgroundOpacity = 82,
                cornerRadiusDp = 34,
                topMarginDp = 0,
                bottomMarginDp = 16,
                leftMarginDp = 14,
                rightMarginDp = 14,
                widthPercent = 78,
                heightDp = 0,
                iconOffsetXDp = 0,
                iconOffsetYDp = 0,
                glassStyle = STYLE_LIQUID,
                blurRadius = 18,
                autoHideSeconds = 0,
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
        const val KEY_REAL_BLUR = "real_blur"
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
        const val KEY_GLASS_STYLE = "glass_style"
        const val KEY_BLUR_RADIUS = "blur_radius"
        const val KEY_AUTO_HIDE_SECONDS = "auto_hide_seconds"
        const val KEY_DOCK_GRAVITY = "dock_gravity"
        const val GRAVITY_BOTTOM = "bottom"
        const val GRAVITY_TOP = "top"
        const val GRAVITY_LEFT = "left"
        const val GRAVITY_RIGHT = "right"
        const val STYLE_LIQUID = "liquid"
        const val STYLE_SOLID = "solid"
        const val STYLE_MATTE = "matte"
        const val DEFAULT_COLOR = "#1C1C1E"
        const val DEFAULT_OPACITY = 82
        const val DEFAULT_CORNER_RADIUS = 34
        const val DEFAULT_TOP_MARGIN = 0
        const val DEFAULT_BOTTOM_MARGIN = 16
        const val DEFAULT_HORIZONTAL_MARGIN = 14
        const val DEFAULT_WIDTH_PERCENT = 78
        const val DEFAULT_HEIGHT_DP = 0
        const val DEFAULT_ICON_OFFSET = 0
        const val DEFAULT_BLUR_RADIUS = 18
        const val DEFAULT_AUTO_HIDE_SECONDS = 0
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
        val WORKSPACE_CLASS_NAMES = listOf(
            "com.android.launcher3.Workspace",
            "com.google.android.apps.nexuslauncher.Workspace"
        )
    }
}
