package cloud.leqwiss.dockcontrol

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class StatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = getString(R.string.app_name) + "\n\n" + getString(R.string.module_description)
        }
        setContentView(text)
    }
}
