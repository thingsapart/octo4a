package com.klipper4a.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.klipper4a.R
import com.klipper4a.repository.KlipperServerStatus
import com.klipper4a.service.OctoPrintService
import com.klipper4a.ui.fragments.TerminalSheetDialog
import com.klipper4a.ui.views.InstallationProgressItem
import com.klipper4a.utils.preferences.MainPreferences
import com.klipper4a.viewmodel.InstallationViewModel
import kotlinx.android.synthetic.main.activity_installation_progress.*
import org.koin.androidx.viewmodel.ext.android.viewModel

class InstallationActivity : AppCompatActivity() {
    private val installationViewModel: InstallationViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        setContentView(R.layout.activity_installation_progress)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        showBugReportingDialog(MainPreferences(this))

        installationViewModel.serverStatus.observe(this) {
            // progressTextView.text = "${it.getInstallationProgress()}%"
            setItemsState(it)
            continueButton.isEnabled = it == KlipperServerStatus.Running
        }

        installationViewModel.installationProgress.observe(this) {
            progressTextView.text = "${it}%"
        }

        continueButton.setOnClickListener {
            stopService(Intent(this, OctoPrintService::class.java))
            val intent = Intent(this, InitialActivity::class.java)
            startActivity(intent)
        }

        logsButton.setOnClickListener {
            val logsFragment = TerminalSheetDialog()
            logsFragment.show(supportFragmentManager, logsFragment.tag)
        }
    }

    private fun setItemsState(status: KlipperServerStatus) {
        bootstrapItem.setStatus(status, KlipperServerStatus.InstallingBootstrap)
        installngKlipperItem.setStatus(status, KlipperServerStatus.InstallingKlipper)
        installingMoonrakerItem.setStatus(status, KlipperServerStatus.InstallingMoonraker)
        installingMainsailtem.setStatus(status, KlipperServerStatus.InstallingMainsail)
        bootingKlipperItem.setStatus(status, KlipperServerStatus.BootingUp)
        installationCompleteItem.setStatus(status, KlipperServerStatus.Running)
    }

    private fun InstallationProgressItem.setStatus(currentStatus: KlipperServerStatus, requiredStatus: KlipperServerStatus) {
        status = requiredStatus
        isLoading = currentStatus.value <= requiredStatus.value
        if (currentStatus.value < requiredStatus.value) {
            setUpcoming()
        }

        if (currentStatus == KlipperServerStatus.Running) {
            isLoading = false
        }
    }
}