package io.github.semiflux.limit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.semiflux.limit.ui.AppNav
import io.github.semiflux.limit.ui.theme.EnoughTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deepLink = intent.getStringExtra(EXTRA_PACKAGE)
        setContent {
            EnoughTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(deepLinkPackage = deepLink)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-compose with the new deep link.
        val deepLink = intent.getStringExtra(EXTRA_PACKAGE)
        setContent {
            EnoughTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(deepLinkPackage = deepLink)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
