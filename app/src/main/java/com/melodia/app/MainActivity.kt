package com.melodia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.melodia.app.ui.MelodiaRoot
import com.melodia.app.ui.theme.MelodiaTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_LIBRARY = "open_library"
    }

    private val openLibraryRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) == true) openLibraryRequests.intValue++
        setContent {
            MelodiaTheme {
                MelodiaRoot(openLibraryRequests = openLibraryRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) openLibraryRequests.intValue++
    }
}
