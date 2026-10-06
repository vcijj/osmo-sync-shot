package com.osmosync.app

import android.app.Application
import android.content.Context
import com.osmosync.app.ble.CameraManager
import com.osmosync.app.ble.RemoteIdentity
import com.osmosync.app.gps.GpsProvider
import com.osmosync.app.gps.OrientationProvider
import com.osmosync.app.insta360.Insta360Remote
import com.osmosync.app.mesh.MeshManager
import com.osmosync.app.phone.PhoneCameraController
import com.osmosync.app.shooter.IntervalShooter
import com.osmosync.app.track.TrackStore
import com.osmosync.app.util.CrashGuard

class App : Application() {

    lateinit var identity: RemoteIdentity
    lateinit var cameraManager: CameraManager
    lateinit var phone: PhoneCameraController
    lateinit var shooter: IntervalShooter
    lateinit var gps: GpsProvider
    lateinit var orientation: OrientationProvider
    lateinit var trackStore: TrackStore
    lateinit var insta360: Insta360Remote
    lateinit var mesh: MeshManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashGuard.install(this)
        identity = RemoteIdentity(getSharedPreferences("osmosync", Context.MODE_PRIVATE))
        gps = GpsProvider(this)
        orientation = OrientationProvider(this)
        trackStore = TrackStore(this)
        cameraManager = CameraManager(this, identity, gps, trackStore, orientation)
        phone = PhoneCameraController(this)
        phone.attachGps(gps)
        shooter = IntervalShooter(cameraManager, phone, gps, orientation, trackStore)
        MeshManager.initContext(this)
        mesh = MeshManager(cameraManager, shooter)
        insta360 = Insta360Remote(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
