package com.osmosync.app

import android.app.Application
import android.content.Context
import com.osmosync.app.ble.CameraManager
import com.osmosync.app.ble.RemoteIdentity
import com.osmosync.app.phone.PhoneCameraController
import com.osmosync.app.shooter.IntervalShooter
import com.osmosync.app.util.CrashGuard

class App : Application() {

    lateinit var identity: RemoteIdentity
    lateinit var cameraManager: CameraManager
    lateinit var phone: PhoneCameraController
    lateinit var shooter: IntervalShooter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashGuard.install(this)
        identity = RemoteIdentity(getSharedPreferences("osmosync", Context.MODE_PRIVATE))
        cameraManager = CameraManager(this, identity)
        phone = PhoneCameraController(this)
        shooter = IntervalShooter(cameraManager, phone)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
