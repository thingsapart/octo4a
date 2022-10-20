package com.klipper4a.ui

import android.app.Activity
import android.app.AlertDialog
import com.klipper4a.Klipper4aApplication
import com.klipper4a.R
import com.klipper4a.utils.preferences.MainPreferences

fun Activity.showBugReportingDialog(prefs: MainPreferences) {
    prefs.enableBugReporting = false
    return

    /*
    if (!prefs.hasAskedAboutReporting) {
        val builder = AlertDialog.Builder(this)
        builder.apply {
            setTitle(getString(R.string.bugreport_dialog_title))
            setMessage(getString(R.string.bugreport_dialog_msg))
            setNegativeButton(getString(R.string.bugreport_dialog_dismiss)) { dialog, id ->
                prefs.hasAskedAboutReporting = true
                prefs.enableBugReporting = false
            }
            setPositiveButton(getString(R.string.bugreport_dialog_enable)) { dialog, id ->
                prefs.hasAskedAboutReporting = true
                prefs.enableBugReporting = true
                (application as Klipper4aApplication).startBugsnag()
            }
        }
        val dialog = builder.create()
        dialog.show()
    }
    */
}