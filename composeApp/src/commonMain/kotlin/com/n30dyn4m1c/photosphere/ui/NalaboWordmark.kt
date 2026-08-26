package com.n30dyn4m1c.photosphere.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.n30dyn4m1c.photosphere.ui.theme.BrandNavy
import com.n30dyn4m1c.photosphere.ui.theme.BrandTeal
import com.n30dyn4m1c.photosphere.ui.theme.GlassContent

/**
 * The Nalabo360 wordmark, set in type.
 *
 * Drawn as two-tone text rather than shipped as an image: it scales with the
 * system type size, costs no assets on any target, and on the app's dark
 * surfaces the navy half flips to near-white exactly as the logo does when
 * printed dark-on-light. The teal "360" carries the brand either way.
 */
@Composable
fun NalaboWordmark(
    onDark: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Text(
            text = "Nalabo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (onDark) GlassContent else BrandNavy,
        )
        Text(
            text = "360",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = BrandTeal,
        )
    }
}
