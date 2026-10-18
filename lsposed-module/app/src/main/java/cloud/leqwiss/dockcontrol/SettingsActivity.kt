package cloud.leqwiss.dockcontrol

import android.content.SharedPreferences
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
    private lateinit var positionLabel: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var adaptiveSwitch: MaterialSwitch
    private lateinit var safeModeSwitch: MaterialSwitch
    private lateinit var forceBackgroundSwitch: MaterialSwitch
    private lateinit var hideSearchSwitch: MaterialSwitch
    private lateinit var colorInput: EditText
    private lateinit var opacitySlider: Slider
    private lateinit var radiusSlider: Slider
    private lateinit var topSlider: Slider
    private lateinit var bottomSlider: Slider
    private lateinit var leftSlider: Slider
    private lateinit var rightSlider: Slider
    private lateinit var widthSlider: Slider
    private lateinit var heightSlider: Slider
    private lateinit var iconOffsetXSlider: Slider
    private lateinit var iconOffsetYSlider: Slider
    private lateinit var opacityLabel: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var topLabel: TextView
    private lateinit var bottomLabel: TextView
    private lateinit var leftLabel: TextView
    private lateinit var rightLabel: TextView
    private lateinit var widthLabel: TextView
    private lateinit var heightLabel: TextView
    private lateinit var iconOffsetXLabel: TextView
    private lateinit var iconOffsetYLabel: TextView
    private lateinit var restartButton: MaterialButton
    private lateinit var dockPreview: DockPreviewView
    private var liveEditing = true
    private var currentPreviewPosition = GRAVITY_BOTTOM

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
        positionLabel = findViewById(R.id.positionLabel)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        adaptiveSwitch = findViewById(R.id.adaptiveSwitch)
        safeModeSwitch = findViewById(R.id.safeModeSwitch)
        forceBackgroundSwitch = findViewById(R.id.forceBackgroundSwitch)
        hideSearchSwitch = findViewById(R.id.hideSearchSwitch)
        colorInput = findViewById(R.id.colorInput)
        opacitySlider = findViewById(R.id.opacitySlider)
        radiusSlider = findViewById(R.id.radiusSlider)
        topSlider = findViewById(R.id.topSlider)
        bottomSlider = findViewById(R.id.bottomSlider)
        leftSlider = findViewById(R.id.leftSlider)
        rightSlider = findViewById(R.id.rightSlider)
        widthSlider = findViewById(R.id.widthSlider)
        heightSlider = findViewById(R.id.heightSlider)
        iconOffsetXSlider = findViewById(R.id.iconOffsetXSlider)
        iconOffsetYSlider = findViewById(R.id.iconOffsetYSlider)
        opacityLabel = findViewById(R.id.opacityLabel)
        radiusLabel = findViewById(R.id.radiusLabel)
        topLabel = findViewById(R.id.topLabel)
        bottomLabel = findViewById(R.id.bottomLabel)
        leftLabel = findViewById(R.id.leftLabel)
        rightLabel = findViewById(R.id.rightLabel)
        widthLabel = findViewById(R.id.widthLabel)
        heightLabel = findViewById(R.id.heightLabel)
        iconOffsetXLabel = findViewById(R.id.iconOffsetXLabel)
        iconOffsetYLabel = findViewById(R.id.iconOffsetYLabel)
        restartButton = findViewById(R.id.restartButton)
        dockPreview = findViewById(R.id.dockPreview)
    }

    private fun setupListeners() {
        enabledSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        adaptiveSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        safeModeSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        forceBackgroundSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }
        hideSearchSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }

        listOf(opacitySlider, radiusSlider, topSlider, bottomSlider, leftSlider, rightSlider, widthSlider, heightSlider, iconOffsetXSlider, iconOffsetYSlider)
            .forEach { slider ->
                slider.addOnChangeListener { _, _, _ -> updateLabels() }
                slider.addOnChangeListener { _, _, _ -> updatePreview() }
                slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = saveSettings()
                })
            }

        findViewById<MaterialButton>(R.id.applyColorButton).setOnClickListener { saveSettings() }
        findViewById<MaterialButton>(R.id.presetDarkButton).setOnClickListener {
            applyPreset("#1C1C1E", 82, 34, 0, 16, 14, 14, 92, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetLightButton).setOnClickListener {
            applyPreset("#FFFFFF", 88, 30, 0, 14, 14, 14, 94, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetGlassButton).setOnClickListener {
            applyPreset("#FFFFFF", 26, 38, 0, 14, 14, 14, 94, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetBlackButton).setOnClickListener {
            applyPreset("#000000", 74, 30, 0, 12, 12, 12, 90, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetBlueButton).setOnClickListener {
            applyPreset("#0A84FF", 78, 34, 0, 14, 14, 14, 92, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetPurpleButton).setOnClickListener {
            applyPreset("#5E5CE6", 78, 36, 0, 14, 14, 14, 92, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetSunsetButton).setOnClickListener {
            applyPreset("#FF9F0A", 76, 36, 0, 14, 14, 14, 92, 0, 0, 0)
        }
        findViewById<MaterialButton>(R.id.presetMintButton).setOnClickListener {
            applyPreset("#30D158", 76, 36, 0, 14, 14, 14, 92, 0, 0, 0)
        }

        findViewById<MaterialButton>(R.id.positionBottomButton).setOnClickListener {
            savePosition(GRAVITY_BOTTOM)
        }
        findViewById<MaterialButton>(R.id.positionTopButton).setOnClickListener {
            savePosition(GRAVITY_TOP)
        }
        findViewById<MaterialButton>(R.id.positionLeftButton).setOnClickListener {
            savePosition(GRAVITY_LEFT)
        }
        findViewById<MaterialButton>(R.id.positionRightButton).setOnClickListener {
            savePosition(GRAVITY_RIGHT)
        }

        findViewById<MaterialButton>(R.id.resetButton).setOnClickListener { resetSettings() }
        restartButton.setOnClickListener { restartLauncher() }

        dockPreview.isInteractive = liveEditing
        dockPreview.onDockChanged = { left, right, top, bottom, height ->
            updatingUi = true
            leftSlider.value = left.toFloat()
            rightSlider.value = right.toFloat()
            topSlider.value = top.toFloat()
            bottomSlider.value = bottom.toFloat()
            heightSlider.value = height.toFloat()
            updatingUi = false
            updateLabels()
            saveSettings()
        }

        findViewById<MaterialButton>(R.id.liveEditButton).setOnClickListener { button ->
            liveEditing = !liveEditing
            dockPreview.isInteractive = liveEditing
            (button as MaterialButton).text = getString(
                if (liveEditing) R.string.live_edit_disable else R.string.live_edit_enable
            )
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
            enabledSwitch.isChecked = prefs.readBoolean(KEY_ENABLED, true)
            adaptiveSwitch.isChecked = prefs.readBoolean(KEY_ADAPTIVE, true)
            safeModeSwitch.isChecked = prefs.readBoolean(KEY_SAFE_MODE, true)
            forceBackgroundSwitch.isChecked = prefs.readBoolean(KEY_FORCE_BACKGROUND, true)
            hideSearchSwitch.isChecked = prefs.readBoolean(KEY_HIDE_SEARCH, false)
            colorInput.setText(prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR)
            val layoutVersion = prefs.readInt(KEY_LAYOUT_VERSION, 0, 0, Int.MAX_VALUE)
            opacitySlider.value = prefs.readInt(KEY_BACKGROUND_OPACITY, DEFAULT_OPACITY, 0, 100).toFloat()
            radiusSlider.value = prefs.readInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS, 0, 50).toFloat()
            topSlider.value = prefs.readInt(KEY_TOP_MARGIN, DEFAULT_TOP_MARGIN, 0, 100).toFloat()
            bottomSlider.value = prefs.readInt(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN, 0, 100).toFloat()
            leftSlider.value = prefs.readInt(KEY_LEFT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 100).toFloat()
            rightSlider.value = prefs.readInt(KEY_RIGHT_MARGIN, DEFAULT_HORIZONTAL_MARGIN, 0, 100).toFloat()
            widthSlider.value = prefs.readInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT, 40, 100).toFloat()
            heightSlider.value = prefs.readInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP, 0, 200).toFloat()
            iconOffsetXSlider.value = prefs.readInt(KEY_ICON_OFFSET_X, DEFAULT_ICON_OFFSET, -50, 50).toFloat()
            iconOffsetYSlider.value = prefs.readInt(KEY_ICON_OFFSET_Y, DEFAULT_ICON_OFFSET, -50, 50).toFloat()
            if (layoutVersion < 4) {
                radiusSlider.value = 34f
                bottomSlider.value = 16f
                widthSlider.value = 78f
                prefs.edit()
                    .putInt(KEY_CORNER_RADIUS, 34)
                    .putInt(KEY_BOTTOM_MARGIN, 16)
                    .putInt(KEY_WIDTH_PERCENT, 78)
                    .putInt(KEY_LAYOUT_VERSION, 4)
                    .apply()
            }
            updatingUi = false
            val position = prefs.getString(KEY_DOCK_GRAVITY, GRAVITY_BOTTOM) ?: GRAVITY_BOTTOM
            updateLabels(position)
            updatePreview(position)
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
            editor.putInt(KEY_TOP_MARGIN, topSlider.value.toInt())
            editor.putInt(KEY_BOTTOM_MARGIN, bottomSlider.value.toInt())
            editor.putInt(KEY_LEFT_MARGIN, leftSlider.value.toInt())
            editor.putInt(KEY_RIGHT_MARGIN, rightSlider.value.toInt())
            editor.putInt(KEY_WIDTH_PERCENT, widthSlider.value.toInt())
            editor.putInt(KEY_HEIGHT_DP, heightSlider.value.toInt())
            editor.putInt(KEY_ICON_OFFSET_X, iconOffsetXSlider.value.toInt())
            editor.putInt(KEY_ICON_OFFSET_Y, iconOffsetYSlider.value.toInt())
            editor.apply()
            updatePreview()
            statusView.text = getString(R.string.settings_saved)
        }.onFailure {
            statusView.text = getString(R.string.settings_save_failed)
        }
    }

    private fun savePosition(position: String) {
        val service = ServiceHolder.service ?: return
        runCatching {
            service.getRemotePreferences(PREFERENCES_GROUP)
                .edit()
                .putString(KEY_DOCK_GRAVITY, position)
                .apply()
            updateLabels(position)
            updatePreview(position)
        }
    }

    private fun applyPreset(
        color: String,
        opacity: Int,
        radius: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        width: Int,
        height: Int,
        iconOffsetX: Int,
        iconOffsetY: Int
    ) {
        colorInput.setText(color)
        opacitySlider.value = opacity.toFloat()
        radiusSlider.value = radius.toFloat()
        topSlider.value = top.toFloat()
        bottomSlider.value = bottom.toFloat()
        leftSlider.value = left.toFloat()
        rightSlider.value = right.toFloat()
        widthSlider.value = width.toFloat()
        heightSlider.value = height.toFloat()
        iconOffsetXSlider.value = iconOffsetX.toFloat()
        iconOffsetYSlider.value = iconOffsetY.toFloat()
        saveSettings()
    }

    private fun resetSettings() {
        applyPreset(DEFAULT_COLOR, DEFAULT_OPACITY, DEFAULT_CORNER_RADIUS, DEFAULT_TOP_MARGIN, DEFAULT_BOTTOM_MARGIN, DEFAULT_HORIZONTAL_MARGIN, DEFAULT_HORIZONTAL_MARGIN, DEFAULT_WIDTH_PERCENT, DEFAULT_HEIGHT_DP, DEFAULT_ICON_OFFSET, DEFAULT_ICON_OFFSET)
        savePosition(GRAVITY_BOTTOM)
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

    private fun updateLabels(position: String = GRAVITY_BOTTOM) {
        opacityLabel.text = getString(R.string.background_opacity_value, opacitySlider.value.toInt())
        radiusLabel.text = getString(R.string.corner_radius_value, radiusSlider.value.toInt())
        topLabel.text = getString(R.string.top_margin_value, topSlider.value.toInt())
        bottomLabel.text = getString(R.string.bottom_margin_value, bottomSlider.value.toInt())
        leftLabel.text = getString(R.string.left_margin_value, leftSlider.value.toInt())
        rightLabel.text = getString(R.string.right_margin_value, rightSlider.value.toInt())
        widthLabel.text = getString(R.string.width_value, widthSlider.value.toInt())
        heightLabel.text = getString(R.string.height_value, heightSlider.value.toInt())
        iconOffsetXLabel.text = getString(R.string.icon_offset_x_value, iconOffsetXSlider.value.toInt())
        iconOffsetYLabel.text = getString(R.string.icon_offset_y_value, iconOffsetYSlider.value.toInt())
        positionLabel.text = getString(
            R.string.position_value,
            getString(
                when (position) {
                    GRAVITY_TOP -> R.string.position_top
                    GRAVITY_LEFT -> R.string.position_left
                    GRAVITY_RIGHT -> R.string.position_right
                    else -> R.string.position_bottom
                }
            )
        )
    }

    private fun updatePreview(position: String = currentPreviewPosition) {
        currentPreviewPosition = position
        dockPreview.widthPercent = widthSlider.value
        dockPreview.heightDp = heightSlider.value.toInt()
        dockPreview.leftMarginDp = leftSlider.value
        dockPreview.rightMarginDp = rightSlider.value
        dockPreview.topMarginDp = topSlider.value
        dockPreview.bottomMarginDp = bottomSlider.value
        dockPreview.cornerRadiusDp = radiusSlider.value
        dockPreview.dockGravity = position
        dockPreview.dockColor = try {
            val base = Color.parseColor(colorInput.text.toString().trim().ifEmpty { DEFAULT_COLOR })
            Color.argb(
                (255 * opacitySlider.value.toInt()) / 100,
                Color.red(base),
                Color.green(base),
                Color.blue(base)
            )
        } catch (throwable: Throwable) {
            Color.argb((255 * DEFAULT_OPACITY) / 100, 28, 28, 30)
        }
        dockPreview.invalidate()
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
        const val KEY_TOP_MARGIN = "top_margin"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
        const val KEY_LEFT_MARGIN = "left_margin"
        const val KEY_RIGHT_MARGIN = "right_margin"
        const val KEY_WIDTH_PERCENT = "width_percent"
        const val KEY_HEIGHT_DP = "height_dp"
        const val KEY_ICON_OFFSET_X = "icon_offset_x"
        const val KEY_ICON_OFFSET_Y = "icon_offset_y"
        const val KEY_DOCK_GRAVITY = "dock_gravity"
        const val KEY_LAYOUT_VERSION = "layout_version"
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
        const val RESTART_LAUNCHER_COMMAND =
            "am force-stop com.google.android.apps.nexuslauncher; " +
                "am force-stop com.android.launcher3; " +
                "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
    }
}
