package com.klipper4a.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.klipper4a.repository.KlipperHandlerRepository
import com.klipper4a.repository.OctoPrintHandlerRepository

class InstallationViewModel(val klipperHandlerRepository: KlipperHandlerRepository) : ViewModel() {
    val serverStatus = klipperHandlerRepository.serverState.asLiveData()
    val installationProgress = klipperHandlerRepository.installationProgress.asLiveData()
}