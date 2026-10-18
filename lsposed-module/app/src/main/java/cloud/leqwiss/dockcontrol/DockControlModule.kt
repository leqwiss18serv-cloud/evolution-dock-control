package cloud.leqwiss.dockcontrol

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

class DockControlModule : XposedModule() {

    private val hotseatViews = CopyOnWriteArraySet<WeakReference<View>>()
    private val lock = Any()

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
            log(Log.WARN, TAG, "Remote preferences are unavailable; using defaults")
        }

        for (className in HOTSEAT_CLASS_NAMES) {
            val hotseatClass = runCatching {
                Class.forName(className, false, param.defaultClassLoader)
            }.getOrNull() ?: continue

            var hooked = false
            runCatching {
                hooked = hookConstructors(hotseatClass)
            }.onFailure {
                log(Log.WARN, TAG, "Constructor hook failed for $className", it)
            }

            runCatching {
                hookLifecycle(hotseatClass)
            }.onFailure {
                log(Log.WARN, TAG, "Lifecycle hook failed for $className", it)
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

    private fun hookConstructors(hotseatClass: Class<*>): Boolean {
        var hookedCount = 0
        for (constructor in hotseatClass.declaredConstructors) {
            if (constructor.isSynthetic) continue
            val hooked = runCatching {
                hook(constructor)
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
                            log(Log.WARN, TAG, "Hotseat constructor hook failed", throwable)
                            chain.proceed()
                        }
                    }
                true
            }.getOrDefault(false)
            if (hooked) hookedCount++
        }
        return hookedCount > 0
    }

    private fun hookLifecycle(hotseatClass: Class<*>) {
        val setInsets = runCatching {
            hotseatClass.getDeclaredMethod("setInsets", Rect::class.java)
        }.getOrNull()
        setInsets?.let { method ->
            hook(method)
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
                        log(Log.WARN, TAG, "setInsets hook failed", throwable)
                        chain.proceed()
                    }
                }
        }

        val onFinishInflate = runCatching {
            hotseatClass.getDeclaredMethod("onFinishInflate")
        }.getOrNull()
        onFinishInflate?.let { method ->
            hook(method)
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
                        log(Log.WARN, TAG, "onFinishInflate hook failed", throwable)
                        chain.proceed()
                    }
                }
        }

        val onMeasure = runCatching {
            hotseatClass.getDeclaredMethod(
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
        onMeasure?.let { method ->
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val result = chain.proceed()
                        applyMargins(chain.thisObject as? View)
                        result
                    } catch (throwable: Throwable) {
                        log(Log.WARN, TAG, "onMeasure hook failed", throwable)
                        chain.proceed()
                    }
                }
        }
    }

    private fun applyStyle(view: View?) {
        if (view == null) return
        try {
            val style = currentStyle
            val drawable = GradientDrawable().apply {
                setColor(style.backgroundColor)
                cornerRadius = style.cornerRadiusDp * view.resources.displayMetrics.density
            }
            view.background = drawable
            hideQsb(view)
            applyMargins(view)
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Applying dock style failed", throwable)
        }
    }

    private fun hideQsb(view: View) {
        runCatching {
            val qsb = view.javaClass.getMethod("getQsb").invoke(view) as? View
            qsb?.visibility = View.GONE
        }
    }

    private fun applyMargins(view: View?) {
        if (view == null) return
        try {
            val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val style = currentStyle
            val density = view.resources.displayMetrics.density
            val sideMargin = (style.sideMarginDp * density).toInt()
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

    private fun SharedPreferences.readStyle(): DockStyle {
        return try {
            DockStyle(
                backgroundColor = readColor(getString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR)),
                cornerRadiusDp = readIntSafely(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
                bottomMarginDp = readIntSafely(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN),
                sideMarginDp = readIntSafely(KEY_SIDE_MARGIN, DEFAULT_SIDE_MARGIN)
            )
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Reading dock preferences failed", throwable)
            DockStyle.DEFAULT
        }
    }

    private fun SharedPreferences.readColor(rawColor: String?): Int {
        val opacity = readIntSafely(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY)
        val parsedColor = try {
            Color.parseColor(rawColor?.trim()?.ifEmpty { DEFAULT_COLOR })
        } catch (throwable: Throwable) {
            Color.parseColor(DEFAULT_COLOR)
        }
        val opacityValue = opacity.coerceIn(0, 100)
        return Color.argb(
            (255 * opacityValue) / 100,
            Color.red(parsedColor),
            Color.green(parsedColor),
            Color.blue(parsedColor)
        )
    }

    private fun SharedPreferences.readIntSafely(key: String, default: Int): Int {
        return try {
            getInt(key, default).coerceIn(0, MAX_SETTING_VALUE)
        } catch (classCastException: ClassCastException) {
            try {
                getFloat(key, default.toFloat()).toInt().coerceIn(0, MAX_SETTING_VALUE)
            } catch (throwable: Throwable) {
                default
            }
        } catch (throwable: Throwable) {
            default
        }
    }

    private data class DockStyle(
        val backgroundColor: Int,
        val cornerRadiusDp: Int,
        val bottomMarginDp: Int,
        val sideMarginDp: Int
    ) {
        companion object {
            val DEFAULT = DockStyle(
                backgroundColor = Color.argb((255 * DEFAULT_OPACITY) / 100, 255, 255, 255),
                cornerRadiusDp = DEFAULT_CORNER_RADIUS,
                bottomMarginDp = DEFAULT_BOTTOM_MARGIN,
                sideMarginDp = DEFAULT_SIDE_MARGIN
            )
        }
    }

    private companion object {
        const val TAG = "EvolutionDockControl"
        const val PREFERENCES_GROUP = "dock_style"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"
        const val KEY_CORNER_RADIUS = "corner_radius"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
        const val KEY_SIDE_MARGIN = "side_margin"
        const val DEFAULT_COLOR = "#FFFFFF"
        const val DEFAULT_OPACITY = 70
        const val DEFAULT_CORNER_RADIUS = 28
        const val DEFAULT_BOTTOM_MARGIN = 12
        const val DEFAULT_SIDE_MARGIN = 12
        const val MAX_SETTING_VALUE = 100
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
