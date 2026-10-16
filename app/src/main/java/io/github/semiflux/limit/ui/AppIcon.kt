package io.github.semiflux.limit.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.semiflux.limit.blocking.EnoughApp

/** System app icon, no custom artwork. */
@Composable
fun AppIcon(icon: Drawable?, modifier: Modifier = Modifier) {
    if (icon == null) {
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            modifier = modifier.size(40.dp)
        )
        return
    }
    val bitmap = remember(icon) {
        runCatching { icon.toBitmap(width = 96, height = 96).asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            modifier = modifier.size(40.dp)
        )
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(40.dp))
    }
}

/** Loads the icon off the main thread; shows a placeholder until it lands. */
@Composable
fun AsyncAppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apps = remember(context) { (context.applicationContext as? EnoughApp)?.apps }
    val icon by produceState<Drawable?>(initialValue = null, packageName) {
        value = runCatching { apps?.iconForCached(packageName) }.getOrNull()
    }
    AppIcon(icon, modifier)
}
