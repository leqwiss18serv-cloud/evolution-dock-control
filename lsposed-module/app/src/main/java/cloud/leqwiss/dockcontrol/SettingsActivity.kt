package cloud.leqwiss.dockcontrol

import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.libxposed.service.XposedService

class SettingsActivity : AppCompatActivity() {

    private var updatingUi = false
    private lateinit var statusView: TextView
    private lateinit var rootStatusView: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var adaptiveSwitch: MaterialSwitch
    private lateinit var safeModeSwitch: MaterialSwitch
    private lateinit var forceBackgroundSwitch: MaterialSwitch
    private lateinit var hideSearchSwitch: MaterialSwitch
    private lateinit var colorInput: EditText
    private lateinit var opacitySlider: Slider
    private lateinit var radiusSlider: Slider
    private lateinit var bottomSlider: Slider
    private lateinit var sideSlider: Slider
    private lateinit var widthSlider: Slider
    private lateinit var heightSlider: Slider
    private lateinit var opacityLabel: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var bottomLabel: TextView
    private lateinit var sideLabel: TextView
    private lateinit var widthLabel: TextView
    private lateinit var heightLabel: TextView
    private lateinit var restartButton: MaterialButton

    private val serviceListener: () -> Unit = {
        runOnUiThread {
            loadSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        setupListeners()
        ServiceHolder.addListener(serviceListener)
        requestRootAccess()
        loadSettings()
    }

    override fun onDestroy() {
        ServiceHolder.removeListener(serviceListener)
        super.onDestroy()
    }

    private fun bindViews() {
        statusView = findViewById(R.id.statusView)
        rootStatusView = findViewById(R.id.rootStatusView)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        adaptiveSwitch = findViewById(R.id.adaptiveSwitch)
        safeModeSwitch = findViewById(R.id.safeModeSwitch)
        forceBackgroundSwitch = findViewById(R.id.forceBackgroundSwitch)
        hideSearchSwitch = findViewById(R.id.hideSearchSwitch)
        colorInput = findViewById(R.id.colorInput)
        opacitySlider = findViewById(R.id.opacitySlider)
        radiusSlider = findViewById(R.id.radiusSlider)
        bottomSlider = findViewById(R.id.bottomSlider)
        sideSlider = findViewById(R.id.sideSlider)
        widthSlider = findViewById(R.id.widthSlider)
        heightSlider = findViewById(R.id.heightSlider)
        opacityLabel = findViewById(R.id.opacityLabel)
        radiusLabel = findViewById(R.id.radiusLabel)
        bottomLabel = findViewById(R.id.bottomLabel)
        sideLabel = findViewById(R.id.sideLabel)
        widthLabel = findViewById(R.id.widthLabel)
        heightLabel = findViewById(R.id.heightLabel)
        restartButton = findViewById(R.id.restartButton)
    }

    private fun setupListeners() {
        enabledSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        adaptiveSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        safeModeSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        forceBackgroundSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        hideSearchSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }

        opacitySlider.addOnChangeListener { _, _, _ -> updateLabels() }
        radiusSlider.addOnChangeListener { _, _, _ -> updateLabels() }
        bottomSlider.addOnChangeListener { _, _, _ -> updateLabels() }
        sideSlider.addOnChangeListener { _, _, _ -> updateLabels() }
        widthSlider.addOnChangeListener { _, _, _ -> updateLabels() }
        heightSlider.addOnChangeListener { _, _, _ -> updateLabels() }

        opacitySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })
        radiusSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })
        bottomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })
        sideSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })
        widthSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })
        heightSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })

        findViewById<MaterialButton>(R.id.applyColorButton).setOnClickListener { saveSettings() }
        findViewById<MaterialButton>(R.id.presetDarkButton).setOnClickListener { applyPreset("#1C1C1E", 82, 34, 16, 14, 92, 0) }
        findViewById<MaterialButton>(R.id.presetLightButton).setOnClickListener { applyPreset("#FFFFFF", 88, 30, 14, 14, 94, 0) }
        findViewById<MaterialButton>(R.id.presetGlassButton).setOnClickListener { applyPreset("#FFFFFF", 26, 38, 14, 14, 94, 0) }
        findViewById<MaterialButton>(R.id.presetBlackButton).setOnClickListener { applyPreset("#000000", 74, 30, 12, 12, 90, 0) }

        restartButton.setOnClickListener { restartLauncher() }
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
            enabledSwitch.isChecked = prefs.readBoolean(KEY_ENABLED, true)
            adaptiveSwitch.isChecked = prefs.readBoolean(KEY_ADAPTIVE, true)
            safeModeSwitch.isChecked = prefs.readBoolean(KEY_SAFE_MODE, true)
            forceBackgroundSwitch.isChecked = prefs.readBoolean(KEY_FORCE_BACKGROUND, true)
            hideSearchSwitch.isChecked = prefs.readBoolean(KEY_HIDE_SEARCH, false)
            colorInput.setText(prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR)
            opacitySlider.value = prefs.readInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY, 0, 100).toFloat()
            radiusSlider.value = prefs.readInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS, 0, 50).toFloat()
            bottomSlider.value = prefs.readInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN, 0, 100).toFloat()
            sideSlider.value = prefs.readInt(KEY_SIDE_MARGIN, DEFAULT_SIDE_MARGIN, 0, 100).toFloat()
            widthSlider.value = prefs.readInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT, 50, 100).toFloat()
            heightSlider.value = prefs.readInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP, 0, 160).toFloat()
            updatingUi = false
            updateLabels()
            statusView.text = getString(R.string.settings_ready)
        }.onFailure {
            updatingUi = false
            statusView.text = getString(R.string.settings_read_failed)
        }
    }

    private fun saveSettings() {
        if (updatingUi) return
        val service = ServiceHolder.service
        if (service == null) {
            statusView.text = getString(R.string.service_unavailable)
            return
        }
        runCatching {
            val editor = service.getRemotePreferences(PREFERENCES_GROUP).edit()
            editor.putBoolean(KEY_ENABLED, enabledSwitch.isChecked)
            editor.putBoolean(KEY_ADAPTIVE, adaptiveSwitch.isChecked)
            editor.putBoolean(KEY_SAFE_MODE, safeModeSwitch.isChecked)
            editor.putBoolean(KEY_FORCE_BACKGROUND, forceBackgroundSwitch.isChecked)
            editor.putBoolean(KEY_HIDE_SEARCH, hideSearchSwitch.isChecked)
            editor.putString(KEY_BACKGROUND_COLOR, colorInput.text.toString().trim())
            editor.putInt(KEY_BACKGROUND_OPACITY, opacitySlider.value.toInt())
            editor.putInt(KEY_CORNER_RADIUS, radiusSlider.value.toInt())
            editor.putInt(KEY_BOTTOM_MARGIN, bottomSlider.value.toInt())
            editor.putInt(KEY_SIDE_MARGIN, sideSlider.value.toInt())
            editor.putInt(KEY_WIDTH_PERCENT, widthSlider.value.toInt())
            editor.putInt(KEY_HEIGHT_DP, heightSlider.value.toInt())
            editor.apply()
            statusView.text = getString(R.string.settings_saved)
        }.onFailure {
            statusView.text = getString(R.string.settings_save_failed)
        }
    }

    private fun applyPreset(
        color: String,
        opacity: Int,
        radius: Int,
        bottom: Int,
        side: Int,
        width: Int,
        height: Int
    ) {
        colorInput.setText(color)
        opacitySlider.value = opacity.toFloat()
        radiusSlider.value = radius.toFloat()
        bottomSlider.value = bottom.toFloat()
        sideSlider.value = side.toFloat()
        widthSlider.value = width.toFloat()
        heightSlider.value = height.toFloat()
        saveSettings()
    }

    private fun requestRootAccess() {
        RootAccess.requestAccess { granted, output ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                restartButton.isEnabled = granted
                rootStatusView.text = if (granted) {
                    getString(R.string.root_status_granted)
                } else {
                    getString(R.string.root_status_denied, output)
                }
            }
        }
    }

    private fun restartLauncher() {
        saveSettings()
        restartButton.isEnabled = false
        rootStatusView.text = getString(R.string.root_restart_running)
        RootAccess.runCommand(RESTART_LAUNCHER_COMMAND) { granted, output ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                restartButton.isEnabled = granted
                rootStatusView.text = if (granted) {
                    getString(R.string.root_restart_done)
                } else {
                    getString(R.string.root_status_denied, output)
                }
            }
        }
    }

    private fun updateLabels() {
        opacityLabel.text = getString(R.string.background_opacity_value, opacitySlider.value.toInt())
        radiusLabel.text = getString(R.string.corner_radius_value, radiusSlider.value.toInt())
        bottomLabel.text = getString(R.string.bottom_margin_value, bottomSlider.value.toInt())
        sideLabel.text = getString(R.string.side_margin_value, sideSlider.value.toInt())
        widthLabel.text = getString(R.string.width_value, widthSlider.value.toInt())
        heightLabel.text = getString(R.string.height_value, heightSlider.value.toInt())
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
                is String -> rawValue.toDouble().toInt()
                else -> getInt(key, default)
            }.coerceIn(minimum, maximum)
        } catch (throwable: Throwable) {
            default
        }
    }

    private companion object {
        const val PREFERENCES_GROUP = "dock_style"
        const val KEY_ENABLED = "enabled"
        const val KEY_ADAPTIVE = "adaptive"
        const val KEY_SAFE_MODE = "safe_mode"
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
        const val RESTART_LAUNCHER_COMMAND =
            "am force-stop com.google.android.apps.nexuslauncher; " +
                "am force-stop com.android.launcher3; " +
                "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
    }
}
