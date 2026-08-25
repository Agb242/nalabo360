package com.n30dyn4m1c.photosphere.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.ui.Strings

/**
 * The desktop host has no camera stack behind this signature, so it explains
 * itself instead of showing a dead viewfinder.
 */
@Composable
actual fun SphereCaptureScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = Strings.DESKTOP_CAPTURE_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = Strings.DESKTOP_CAPTURE_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
