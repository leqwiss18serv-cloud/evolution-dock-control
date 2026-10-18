package cloud.leqwiss.dockcontrol

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.github.libxposed.service.XposedService

class SettingsActivity : Activity() {

    private var updatingUi = false
    private lateinit var statusView: TextView
    private lateinit var colorInput: EditText
    private lateinit var opacitySlider: SeekBar
    private lateinit var radiusSlider: SeekBar
    private lateinit var bottomSlider: SeekBar
    private lateinit var sideSlider: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var bottomLabel: TextView
    private lateinit var sideLabel: TextView
    private val serviceListener: () -> Unit = {
        runOnUiThread {
            loadSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        ServiceHolder.addListener(serviceListener)
        loadSettings()
    }

    override fun onDestroy() {
        ServiceHolder.removeListener(serviceListener)
        super.onDestroy()
    }

    private fun createContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            setPadding(0, 16, 0, 24)
        }
        content.addView(statusView)

        content.addView(label(R.string.background_color))
        colorInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "#FFFFFF"
        }
        content.addView(colorInput)

        content.addView(Button(this).apply {
            text = getString(R.string.apply_color)
            setOnClickListener { writeSettings() }
        })

        content.addView(label(R.string.color_palette))
        val palette = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PRESET_COLORS.forEach { presetColor ->
            palette.addView(Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundColor(presetColor)
                text = ""
                setOnClickListener {
                    colorInput.setText(colorToHex(presetColor))
                    writeSettings()
                }
            })
        }
        content.addView(palette)

        opacityLabel = label(R.string.background_opacity)
        content.addView(opacityLabel)
        opacitySlider = createSlider(content, 100, DEFAULT_OPACITY) { writeSettings() }

        radiusLabel = label(R.string.corner_radius)
        content.addView(radiusLabel)
        radiusSlider = createSlider(content, 50, DEFAULT_CORNER_RADIUS) { writeSettings() }

        bottomLabel = label(R.string.bottom_margin)
        content.addView(bottomLabel)
        bottomSlider = createSlider(content, 100, DEFAULT_BOTTOM_MARGIN) { writeSettings() }

        sideLabel = label(R.string.side_margin)
        content.addView(sideLabel)
        sideSlider = createSlider(content, 100, DEFAULT_SIDE_MARGIN) { writeSettings() }

        return ScrollView(this).apply { addView(content) }
    }

    private fun createSlider(
        parent: LinearLayout,
        max: Int,
        initialValue: Int,
        onCommit: () -> Unit
    ): SeekBar {
        return SeekBar(this).apply {
            max = max
            progress = initialValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabels()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    onCommit()
                }
            })
            parent.addView(this)
        }
    }

    private fun label(textResource: Int): TextView {
        return TextView(this).apply {
            text = getString(textResource)
            setPadding(0, 24, 0, 8)
        }
    }

    private fun loadSettings() {
        val service = ServiceHolder.service
        if (service == null) {
            statusView.text = getString(R.string.service_unavailable)
            return
        }
        runCatching {
            val prefs = service.getRemotePreferences(PREFERENCES_GROUP)
            updatingUi = true
            colorInput.setText(prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR)
            opacitySlider.progress = prefs.getInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY)
            radiusSlider.progress = prefs.getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
            bottomSlider.progress = prefs.getInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN)
            sideSlider.progress = prefs.getInt(KEY_SIDE_MARGIN, DEFAULT_SIDE_MARGIN)
            updatingUi = false
            updateLabels()
            statusView.text = getString(R.string.settings_ready)
        }.onFailure {
            updatingUi = false
            statusView.text = getString(R.string.settings_read_failed)
        }
    }

    private fun writeSettings() {
        if (updatingUi) return
        val service = ServiceHolder.service
        if (service == null) {
            statusView.text = getString(R.string.service_unavailable)
            return
        }
        runCatching {
            val editor = service.getRemotePreferences(PREFERENCES_GROUP).edit()
            editor.putString(KEY_BACKGROUND_COLOR, colorInput.text.toString().trim())
            editor.putInt(KEY_BACKGROUND_OPACITY, opacitySlider.progress)
            editor.putInt(KEY_CORNER_RADIUS, radiusSlider.progress)
            editor.putInt(KEY_BOTTOM_MARGIN, bottomSlider.progress)
            editor.putInt(KEY_SIDE_MARGIN, sideSlider.progress)
            editor.apply()
            statusView.text = getString(R.string.settings_saved)
        }.onFailure {
            statusView.text = getString(R.string.settings_save_failed)
        }
    }

    private fun updateLabels() {
        opacityLabel.text = getString(R.string.background_opacity_value, opacitySlider.progress)
        radiusLabel.text = getString(R.string.corner_radius_value, radiusSlider.progress)
        bottomLabel.text = getString(R.string.bottom_margin_value, bottomSlider.progress)
        sideLabel.text = getString(R.string.side_margin_value, sideSlider.progress)
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
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
        val PRESET_COLORS = intArrayOf(
            Color.WHITE,
            Color.BLACK,
            Color.parseColor("#1F1F1F"),
            Color.parseColor("#2C2C2E"),
            Color.parseColor("#0A84FF"),
            Color.parseColor("#FF9F0A")
        )
    }
}
