package com.leqwiss.dockcontrol

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal control panel. No AndroidX, no background services, no receivers — just three buttons
 * that shell out to `su -c` (the same code path the Magisk action uses).
 *
 * The UI is optional: the module works as soon as it is enabled in LSPosed, and the Magisk
 * module can toggle the same flag without ever opening this app.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(Color.WHITE)
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, dp(8), 0, dp(20))
        }
        root.addView(statusView)

        root.addView(button(getString(R.string.btn_hide)) { toggle(hide = true) })
        root.addView(button(getString(R.string.btn_show)) { toggle(hide = false) })
        root.addView(button(getString(R.string.btn_restart)) { restartLauncher() })

        root.addView(TextView(this).apply {
            text = getString(R.string.hint)
            textSize = 13f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, dp(20), 0, dp(8))
        })

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#80CBC4"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------ UI

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

    private fun refreshStatus() {
        val hidden = try {
            Settings.Secure.getInt(contentResolver, DockState.KEY, 1) != 0
        } catch (t: Throwable) {
            true
        }
        statusView.text = getString(
            if (hidden) R.string.status_hidden else R.string.status_visible,
        )
    }

    // -------------------------------------------------------------- actions

    private fun toggle(hide: Boolean) {
        val value = if (hide) "1" else "0"
        logView.text = ""
        sh(
            "settings put secure ${DockState.KEY} $value; " +
                "am force-stop com.google.android.apps.nexuslauncher; " +
                "am force-stop com.android.launcher3; " +
                "input keyevent KEYCODE_HOME",
        ) { code, out ->
            append("exit=$code\n$out")
            refreshStatus()
        }
    }

    private fun restartLauncher() {
        sh(
            "am force-stop com.google.android.apps.nexuslauncher; " +
                "am force-stop com.android.launcher3; " +
                "input keyevent KEYCODE_HOME",
        ) { code, out -> append("exit=$code\n$out") }
    }

    /**
     * Runs a root shell command off the UI thread. Deliberately dumb: no retries, no loops.
     */
    private fun sh(command: String, onDone: (Int, String) -> Unit) {
        append("$ $command")
        Thread {
            var code = -1
            var out = ""
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                code = process.waitFor()
            } catch (t: Throwable) {
                out = "${t.javaClass.simpleName}: ${t.message}"
            }
            val finalCode = code
            val finalOut = out
            runOnUiThread { onDone(finalCode, finalOut) }
        }.apply { isDaemon = true }.start()
    }

    private fun append(text: String) {
        logView.text = if (logView.text.isNullOrEmpty()) text else "${logView.text}\n$text"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
