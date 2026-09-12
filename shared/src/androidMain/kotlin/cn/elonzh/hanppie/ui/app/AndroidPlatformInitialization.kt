package cn.elonzh.hanppie.ui.app

import androidx.activity.ComponentActivity
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.manualFileKitCoreInitialization

fun initializeAndroidPlatform(activity: ComponentActivity) {
    FileKit.manualFileKitCoreInitialization(activity.applicationContext)
    FileKit.init(activity)
}
