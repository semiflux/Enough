package io.github.semiflux.limit.blocking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Collections
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import io.github.semiflux.limit.ui.theme.EnoughTheme
import java.time.ZonedDateTime

/**
 * Minimal full-screen block overlay. Launched on top of the limited app;
 * back press goes Home so the user cannot slip back into the app.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The deprecated onBackPressed() override is bypassed on modern
        // Android (predictive-back era): back would just finish this
        // activity and drop the user back into the blocked app. A dispatcher
        // callback actually runs, sending them Home instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: finish().let { return }
        Log.i(TAG, "showing block screen for $pkg")
        showing.add(pkg)
        setContent {
            EnoughTheme {
                BlockScreen(
                    packageName = pkg,
                    onClose = { goHome() }
                )
            }
        }
    }

    override fun onDestroy() {
        intent.getStringExtra(EXTRA_PACKAGE)?.let {
            Log.i(TAG, "closing block screen for $it")
            showing.remove(it)
        }
        super.onDestroy()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "blocked_package"
        private const val TAG = "Enough"
        private val showing = Collections.synchronizedSet(mutableSetOf<String>())

        fun isShowing(packageName: String): Boolean = synchronized(showing) {
            showing.contains(packageName)
        }

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, BlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun BlockScreen(
    packageName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val app = remember(context) { EnoughServiceLocator.appOrNull(context) }
    var label by remember { mutableStateOf(packageName) }
    var reasonText by remember { mutableStateOf("") }

    LaunchedEffect(packageName) {
        label = app?.apps?.labelFor(packageName) ?: packageName
        val rule = app?.rulesSnapshot()?.firstOrNull { it.packageName == packageName }
        val used = app?.usage?.usageFor(packageName) ?: 0L
        reasonText = when (val reason = rule?.let { BlockEvaluator.evaluate(it, used, ZonedDateTime.now()) }) {
            is BlockReason.Schedule ->
                "Paused ${BlockEvaluator.formatWindow(reason.window)}"
            is BlockReason.Quota ->
                "Daily limit reached" + (rule.dailyLimitMinutes?.let { " · $it min/day" } ?: "")
            null -> "Limit reached"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Block,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                reasonText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onClose) { Text("Close app") }
        }
    }
}
