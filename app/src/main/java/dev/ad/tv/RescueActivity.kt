package dev.ad.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlin.concurrent.thread

/** Shown instead of the home screen after repeated crashes, so the TV is never stuck. */
class RescueActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rescue)
        val status = findViewById<TextView>(R.id.status)

        findViewById<Button>(R.id.retry).setOnClickListener {
            CrashWatchdog.reset(this)
            finish()
        }
        val openStock = findViewById<Button>(R.id.openStock)
        openStock.isEnabled = Apps.isInstalled(this, HomeSwitch.LAUNCHERX)
        openStock.setOnClickListener {
            Apps.start(
                this,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(HomeSwitch.LAUNCHERX, "${HomeSwitch.LAUNCHERX}.home.HomeActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        findViewById<Button>(R.id.restore).setOnClickListener {
            status.setText(R.string.working)
            thread {
                val result = runCatching { HomeSwitch.useStockLauncher(this) {} }
                runOnUiThread {
                    status.text = result.fold({ getString(R.string.stock_restored) }, { it.message })
                    openStock.isEnabled = Apps.isInstalled(this, HomeSwitch.LAUNCHERX)
                }
            }
        }
        findViewById<Button>(R.id.retry).requestFocus()
    }
}
