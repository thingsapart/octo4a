package com.klipper4a.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.asLiveData
import androidx.preference.*
import com.klipper4a.Klipper4aApplication
import com.klipper4a.camera.*
import com.klipper4a.camera.CameraService
import com.klipper4a.utils.isServiceRunning
import com.klipper4a.utils.preferences.MainPreferences
import org.koin.android.ext.android.inject
import com.klipper4a.R
import com.klipper4a.repository.*

class SettingsFragment : PreferenceFragmentCompat() {
    private val cameraEnumerationRepository: CameraEnumerationRepository by inject()
    private val prefs: MainPreferences by inject()
    private val klipperHandler: KlipperHandlerRepository by inject()
    private val logger: LoggerRepository by inject()

    // Camera permission request
    private val hasCameraPermission: Boolean
        get() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Preferences
    private val enableCameraPref by lazy { findPreference<SwitchPreferenceCompat>("enableCameraServer") }
    private val enableSSH by lazy { findPreference<SwitchPreferenceCompat>("enableSSH") }
    private val sshPortPref by lazy { findPreference<EditTextPreference>("sshPort") }
    private val selectedCameraPref by lazy { findPreference<ListPreference>("selectedCamera") }
    private val selectedCameraResolution by lazy { findPreference<ListPreference>("selectedResolution") }
    private val sshPasswordPref by lazy { findPreference<EditTextPreference>("changeSSHPassword") }
    private val fpsLimit by lazy { findPreference<ListPreference>("fpsLimit") }
    private val installPluginExtras by lazy { findPreference<Preference>("installPluginExtras") }
    private val imageRotation by lazy { findPreference<ListPreference>("imageRotation") }
    private val disableAF by lazy { findPreference<SwitchPreferenceCompat>("disableAF") }
    private val enableBugReporting by lazy { findPreference<SwitchPreferenceCompat>("enableBugReporting") }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            enableCameraPref?.isChecked = isGranted
            if (isGranted) {
                cameraEnumerationRepository.enumerateCameras()
                klipperHandler.isCameraServerRunning = true
            }
        }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey)

        enableBugReporting?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                (activity?.application as Klipper4aApplication).startBugsnag()
            }
            true
        }

        setupSSHSettings()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!hasCameraPermission) {
            enableCameraPref?.isChecked = false
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        cameraEnumerationRepository.enumeratedCameras.observe(viewLifecycleOwner) {
            setupCameraSettings(it)
        }

        klipperHandler.extrasStatus.asLiveData().observe(viewLifecycleOwner) {
            when(it) {
                KlipperExtrasStatus.Installing -> {
                    installPluginExtras?.summary = "Installing..."
                }

                KlipperExtrasStatus.NotInstalled -> {
                    installPluginExtras?.summary = "Not installed"
                }

                else -> {
                    installPluginExtras?.summary = "Installed"
                }
            }
        }

        installPluginExtras?.setOnPreferenceClickListener {
            klipperHandler.installExtras()
            true
        }

        klipperHandler.getExtrasStatus()
    }

    private fun setupSSHSettings() {
        sshPortPref?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            summary = prefs.sshPort

            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toIntOrNull()
                if (value != null && value in 1025..65534) {
                    summary = newValue

                    // Restart SSH
                    prefs.sshPort = newValue
                    klipperHandler.startSSH()

                    true
                } else { false }
            }
            setDefaultValue("8022")
        }
        enableSSH?.setOnPreferenceChangeListener {
                _, newValue ->
            if (newValue as Boolean) {
                if (klipperHandler.isSSHConfigured) {
                    klipperHandler.startSSH()
                } else {
                    enableSSH?.isChecked = false
                    preferenceManager.showDialog(sshPasswordPref)
                }
            } else {
                klipperHandler.stopSSH()
            }
            true
        }

        sshPasswordPref?.apply {
            summary = "*".repeat(prefs.changeSSHPassword?.length ?: 0)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                sshPasswordPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                        pref ->
                    "*".repeat(pref?.text?.length ?: 0)
                }
            }
            setOnPreferenceChangeListener { _, newValue ->
                klipperHandler.stopSSH()
                klipperHandler.resetSSHPassword(newValue as String)
                klipperHandler.startSSH()
                prefs.sshPasword = newValue
                enableSSH?.isChecked = true
                true
            }
        }
    }

    private fun setupCameraSettings(cameras: List<CameraDescription>) {
        val enableCameraPref = findPreference<SwitchPreferenceCompat>("enableCameraServer")
        if (!hasCameraPermission) {
            prefs.enableCameraServer = false
            enableCameraPref?.isChecked = false
        } else {
            initCameraConfig(cameras)
        }

        imageRotation?.setOnPreferenceChangeListener { _, value ->
            prefs.imageRotation = value as String
            stopCameraServer()
            startCameraServer()
            true
        }

        fpsLimit?.setOnPreferenceChangeListener { _, value ->
            prefs.fpsLimit = value as String
            stopCameraServer()
            startCameraServer()
            true
        }

        enableCameraPref?.setOnPreferenceChangeListener { _, newValue ->
            if (cameras.isEmpty()) {
                enableCameraPref.isChecked = false
                Toast.makeText(requireContext(), "No cameras available", Toast.LENGTH_LONG).show()
            } else {
                if (!hasCameraPermission) {
                    enableCameraPref.isChecked = false
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    initCameraConfig(cameras)
                    if (newValue as Boolean) {
                        startCameraServer()
                    } else {
                        stopCameraServer()
                    }
                }
            }
            true
        }
    }

    // Injects proper camera resolution configs
    private fun initCameraConfig(cameras: List<CameraDescription>) {
        if (cameras.isEmpty()) {
            return
        }
        if (prefs.selectedCamera == null) {
            // Select first back camera or use whatever camera's available
            val selectedCamera =
                cameras.firstOrNull { it.isBackFacing } ?: cameras.firstOrNull()

            // First resolution that's wider than 1000px
            val selectedResolution = selectedCamera?.sizes?.sortedBy { it.width }?.firstOrNull { it.width >= 1000 } ?: selectedCamera?.sizes?.first()
            prefs.selectedCamera = selectedCamera?.id
            prefs.selectedResolution = selectedResolution?.readableString()
        }

        disableAF?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.disableAF = newValue as Boolean
                stopCameraServer()
                startCameraServer()
                true
            }
        }

        selectedCameraPref?.apply {
            // Set values and descriptions
            entries = cameras.map { it.describeString() }.toTypedArray()
            entryValues = cameras.map { it.id }.toTypedArray()

            // When camera changes, reset to default resolution
            setOnPreferenceChangeListener { _, newValue ->
                val selectedCam = cameraEnumerationRepository.cameraWithId(newValue as String)
                prefs.selectedResolution = selectedCam?.getRecommendedSize()?.readableString()
                setCameraResolutions(newValue)
                selectedCameraResolution?.value = prefs.selectedResolution
                startCameraServer()
                true
            }
        }
        val currentCam = prefs.selectedCamera
        selectedCameraPref?.value = currentCam!!
        setCameraResolutions(currentCam)
        selectedCameraResolution?.value = prefs.selectedResolution
    }

    private fun setCameraResolutions(selectedCam: String) {
        val camera = cameraEnumerationRepository.cameraWithId(selectedCam)
        selectedCameraResolution?.apply {
            entries = camera?.sizes?.map { it.readableString() }?.toTypedArray()
            entryValues = entries
            setOnPreferenceChangeListener { _, value ->
                prefs.selectedResolution = value as String
                startCameraServer()
                true
            }
        }
    }

    private fun startCameraServer() {
        val activityContext = requireActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

            if (activityContext.isServiceRunning(CameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
            activityContext.startService(cameraServiceIntent)
        } else {
            val cameraServiceIntent = Intent(activityContext, LegacyCameraService::class.java)

            if (activityContext.isServiceRunning(LegacyCameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
            activityContext.startService(cameraServiceIntent)
        }
    }

    private fun stopCameraServer() {
        val activityContext = requireActivity()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

            if (activityContext.isServiceRunning(CameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
        } else {
            val cameraServiceIntent = Intent(activityContext, LegacyCameraService::class.java)

            if (activityContext.isServiceRunning(LegacyCameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
        }
    }
}