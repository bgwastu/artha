package net.wastu.artha

import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Only a preview flag is stored here; fictional payments never enter personal storage. */
internal object DemoMode {
    private fun preferences(context: Context) = context.getSharedPreferences("artha_demo_mode", Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean): Boolean = preferences(context).edit().putBoolean("enabled", enabled).commit()
    fun open(context: Context) {
        if (setEnabled(context, true)) {
            context.startActivity(Intent(context, DemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Destroy the personal screen's ViewModel so its import/AI jobs do not continue in demo mode.
            (context as? android.app.Activity)?.finish()
        } else Toast.makeText(context, "Couldn't start demo mode", Toast.LENGTH_SHORT).show()
    }
}
