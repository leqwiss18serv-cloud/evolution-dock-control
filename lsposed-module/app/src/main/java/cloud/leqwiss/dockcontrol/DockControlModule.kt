package cloud.leqwiss.dockcontrol

import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class DockControlModule : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in TARGET_PACKAGES) return

        for (className in HOTSEAT_CLASS_NAMES) {
            val hotseatClass = runCatching {
                Class.forName(className, false, param.defaultClassLoader)
            }.getOrNull() ?: continue

            runCatching {
                hookOnMeasure(hotseatClass)
                hookAttached(hotseatClass)
                log(Log.INFO, TAG, "Hooked Hotseat: $className")
            }.onFailure {
                log(Log.WARN, TAG, "Failed to hook $className", it)
            }
        }
    }

    private fun hookOnMeasure(hotseatClass: Class<*>) {
        val onMeasure = hotseatClass.getDeclaredMethod(
            "onMeasure",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        hook(onMeasure)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    chain.proceed()
                    val view = chain.thisObject as View
                    view.setMeasuredDimensions(
                        view.measuredWidth,
                        0
                    )
                    null
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "onMeasure hook failed", throwable)
                    chain.proceed()
                }
            }
    }

    private fun hookAttached(hotseatClass: Class<*>) {
        val onAttached = hotseatClass.getDeclaredMethod("onAttachedToWindow")
        hook(onAttached)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = try {
                    chain.proceed()
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "onAttachedToWindow hook failed", throwable)
                    null
                }
                try {
                    val view = chain.thisObject as View
                    val layoutParams = view.layoutParams
                    if (layoutParams != null && layoutParams.height != 0) {
                        layoutParams.height = 0
                        view.layoutParams = layoutParams
                    }
                } catch (throwable: Throwable) {
                    log(Log.WARN, TAG, "LayoutParams hook failed", throwable)
                }
                result
            }
    }

    private fun View.setMeasuredDimensions(width: Int, height: Int) {
        val method = View::class.java.getDeclaredMethod(
            "setMeasuredDimension",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(this, width, height)
    }

    private companion object {
        const val TAG = "EvolutionDockControl"
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
