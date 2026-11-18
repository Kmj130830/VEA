package com.kmj.vea

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kmj.vea.ui.EditorMode
import com.kmj.vea.ui.VeaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeaApp { mode ->
                requestedOrientation = when (mode) {
                    EditorMode.ARRANGEMENT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    EditorMode.PIANO_ROLL -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }
}
