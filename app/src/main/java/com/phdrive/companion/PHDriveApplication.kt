package com.phdrive.companion

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.repository.TripRepository

typealias PhDriveApplication = PHDriveApplication

class PHDriveApplication : Application(), Configuration.Provider {

    val database by lazy { PhDriveDatabase.getInstance(this) }
    val tripRepository by lazy { TripRepository(database) }
    val vehicleRepository by lazy { com.phdrive.companion.repository.VehicleRepository(database) }
    val syncRepository by lazy { SyncRepository(database, this) }
    val backupRepository by lazy { com.phdrive.companion.repository.BackupRepository(database) }
    val vehicleBluetoothRepository by lazy { com.phdrive.companion.repository.VehicleBluetoothRepository(database) }
    val bluetoothConnectionMonitor by lazy { com.phdrive.companion.bluetooth.BluetoothConnectionMonitor.getInstance(this) }
    val autoTripOrchestrator by lazy { com.phdrive.companion.car.AutoTripOrchestrator.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AuthRepository.init(this)
        bluetoothConnectionMonitor.startMonitoring()
        autoTripOrchestrator.start()
        Log.d("PHDriveApplication", "PHDriveApplication inicializada com sucesso")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()

    companion object {
        lateinit var instance: PHDriveApplication
            private set
    }
}
