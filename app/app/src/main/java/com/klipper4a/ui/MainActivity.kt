package com.klipper4a.ui

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleService
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.klipper4a.R
import com.klipper4a.repository.LoggerRepository
import com.klipper4a.serial.VirtualSerialDriver
import com.klipper4a.serial.id
import com.klipper4a.service.OctoPrintService
import com.klipper4a.ui.fragments.TerminalSheetDialog
import com.klipper4a.utils.preferences.MainPreferences
import kotlinx.android.synthetic.main.activity_main.*
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {
    private val logger: LoggerRepository by inject()
    private val vsp: VirtualSerialDriver by inject()
    private val mainPreferences: MainPreferences by inject()
    private val pm  by lazy { getSystemService(LifecycleService.POWER_SERVICE) as PowerManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(my_toolbar)
        bottomNavigationBar.setupWithNavController(findNavController(R.id.navHost))
        vsp.updateDevicesList(OctoPrintService.BROADCAST_SERVICE_USB_GOT_ACCESS)

        // Required for acquiring wakelock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName) && mainPreferences.warnDisableBatteryOptimization) {
            showBatteryOptimizationDialog()
        }

        //showBugReportingDialog(mainPreferences)
    }

    private fun showBatteryOptimizationDialog() {
        val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager

        val alertDialog: AlertDialog? = this.let {
            val builder = AlertDialog.Builder(it)
            builder.apply {
                setTitle(R.string.disable_battery_optimization)
                setMessage(R.string.disable_battery_optimization_msg)
                setNegativeButton(R.string.action_never_ask_again) { dialog, id ->
                    mainPreferences.warnDisableBatteryOptimization = false
                }
                setNeutralButton(R.string.action_later) { dialog, id ->
                    // User cancelled the dialog
                }

                if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_NORMAL) {
                    setPositiveButton(R.string.action_open_settings) { dialog, id ->
                        val whitelist = Intent()
                        whitelist.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        whitelist.data = Uri.parse("package:$packageName")
                        whitelist.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            startActivity(whitelist)
                        } catch (e: ActivityNotFoundException) {
                            logger.log(this) { "failed to open battery optimization dialog" }
                            Toast.makeText(this@MainActivity, getString(R.string.optimization_settings_error), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // Create the AlertDialog
            builder.create()
        }
        alertDialog?.show()
    }

    // @TODO: refactor to non deprecated api
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VirtualSerialDriver.usbPermissionRequestCode) {
            vsp.updateDevicesList(OctoPrintService.BROADCAST_SERVICE_USB_GOT_ACCESS)
            vsp.connectedDevices.value.firstOrNull { it.id() == data.toString() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_appbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when(item?.itemId) {
            R.id.action_show_logs -> {
                val logsFragment = TerminalSheetDialog()
                logsFragment.show(supportFragmentManager, logsFragment.tag)
            }
        }
        return true
    }
}