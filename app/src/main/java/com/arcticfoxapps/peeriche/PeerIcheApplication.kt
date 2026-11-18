package com.arcticfoxapps.peeriche

import android.app.Application
import coil.Coil
import coil.ImageLoader

class PeerIcheApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyNetwork.initialize(this)
        Coil.setImageLoader { ImageLoader.Builder(this).callFactory { ProxyNetwork.calls() }.build() }
    }
}
