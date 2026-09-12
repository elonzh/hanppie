package cn.elonzh.hanppie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.elonzh.hanppie.ui.app.AndroidWorkbench
import cn.elonzh.hanppie.ui.app.initializeAndroidPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeAndroidPlatform(this)
        enableEdgeToEdge()
        setContent { AndroidWorkbench() }
    }
}
