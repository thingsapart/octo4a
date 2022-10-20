package com.klipper4a.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.klipper4a.repository.KlipperHandlerRepository
import com.klipper4a.repository.OctoPrintHandlerRepository

class InstallationViewModel(private val klipperHandlerRepository: KlipperHandlerRepository) : ViewModel() {
    val serverStatus = klipperHandlerRepository.serverState.asLiveData()
}