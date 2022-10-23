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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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
            when (it) {
                KlipperServerStatus.Running,
                KlipperServerStatus.InstalledBootstrap,
                KlipperServerStatus.InstalledMainsail,
                KlipperServerStatus.InstalledMoonraker,
                KlipperServerStatus.InstalledKlipper -> {
                    continueButton.isEnabled = true
                }
                else -> {
                    continueButton.isEnabled = false
                }
            }
        }

        installationViewModel.installationProgress.observe(this) {
            progressTextView.text = "${it}%"
        }

        continueButton.setOnClickListener {
            when (installationViewModel.serverStatus.value) {
                KlipperServerStatus.Corrupted -> {
                    GlobalScope.launch(Dispatchers.IO) {
                        installationViewModel.klipperHandlerRepository.beginInstallation()
                    }
                }
                KlipperServerStatus.InstalledBootstrap -> {
                    GlobalScope.launch(Dispatchers.IO) {
                        installationViewModel.klipperHandlerRepository.installKlipper()
                    }
                }
                KlipperServerStatus.InstalledKlipper -> {
                    GlobalScope.launch(Dispatchers.IO) {
                        installationViewModel.klipperHandlerRepository.installMoonraker()
                    }
                }
                KlipperServerStatus.InstalledMoonraker -> {
                    GlobalScope.launch(Dispatchers.IO) {
                        installationViewModel.klipperHandlerRepository.installMainsail()
                    }
                }
                else -> {
                    stopService(Intent(this, OctoPrintService::class.java))
                    val intent = Intent(this, InitialActivity::class.java)
                    startActivity(intent)
                }
            }
        }

        logsButton.setOnClickListener {
            val logsFragment = TerminalSheetDialog()
            logsFragment.show(supportFragmentManager, logsFragment.tag)
        }
    }

    private fun setItemsState(status: KlipperServerStatus) {
        bootstrapItem.setStatus(status, KlipperServerStatus.InstallingBootstrap, KlipperServerStatus.InstalledBootstrap)
        installngKlipperItem.setStatus(status, KlipperServerStatus.InstallingKlipper, KlipperServerStatus.InstalledKlipper)
        installingMoonrakerItem.setStatus(status, KlipperServerStatus.InstallingMoonraker, KlipperServerStatus.InstalledMoonraker)
        installingMainsailtem.setStatus(status, KlipperServerStatus.InstallingMainsail, KlipperServerStatus.InstalledMainsail)
        bootingKlipperItem.setStatus(status, KlipperServerStatus.BootingUp, KlipperServerStatus.BootingUp)
        installationCompleteItem.setStatus(status, KlipperServerStatus.Running, KlipperServerStatus.Running)
    }

    private fun InstallationProgressItem.setStatus(currentStatus: KlipperServerStatus, installingStatus: KlipperServerStatus, doneStatus: KlipperServerStatus) {
        status = if (currentStatus >= doneStatus) doneStatus else installingStatus
        isLoading = currentStatus.value < doneStatus.value
        if (currentStatus.value < installingStatus.value) {
            setUpcoming()
        }

        if (currentStatus == KlipperServerStatus.Running) {
            isLoading = false
        }
    }
}