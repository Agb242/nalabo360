package com.n30dyn4m1c.photosphere.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.camera.ReticleOverlay
import com.n30dyn4m1c.photosphere.settings.ReticleColorChoices
import com.n30dyn4m1c.photosphere.settings.ReticleSettingsRepository
import com.n30dyn4m1c.photosphere.settings.ReticleStyle
import com.n30dyn4m1c.photosphere.settings.ReticleStyleHub
import com.n30dyn4m1c.photosphere.settings.appDataDirectory
import com.n30dyn4m1c.photosphere.ui.theme.PhotoWell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Reticle settings: its colour and how large the ring is drawn.
 *
 * Both exist for one reason — the reticle is the only piece of the capture HUD
 * the user must be able to see in every condition, and "every condition"
 * includes a white noon sky that erases a white ring. Colour applies
 * immediately to the live preview and to any capture screen behind this one
 * (they read the same hub), and persists on release of the size slider so a
 * drag does not become a hundred disk writes.
 */
@Composable
fun ReticleSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repository = remember { ReticleSettingsRepository(appDataDirectory()) }
    val style by ReticleStyleHub.style.collectAsState()

    /** Applies to every screen at once, then lands on disk. */
    fun apply(style: ReticleStyle, persist: Boolean) {
        ReticleStyleHub.update(style)
        if (persist) {
            scope.launch { withContext(Dispatchers.Default) { repository.save(style) } }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().systemBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = Strings.SETTINGS_BACK_DESCRIPTION,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = Strings.SETTINGS_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
                NalaboWordmark(modifier = Modifier.padding(start = 16.dp))
            }

            // The reticle against a well as dark as a viewfinder at dusk —
            // enough to judge colour and weight without a camera behind it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(PhotoWell)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                    ),
            ) {
                ReticleOverlay(style = style, modifier = Modifier.fillMaxSize())
            }

            Text(
                text = Strings.SETTINGS_RETICLE_COLOR,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReticleColorChoices.forEach { colorArgb ->
                    val isSelected = style.colorArgb == colorArgb
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(colorArgb))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            )
                            .clickable { apply(style.copy(colorArgb = colorArgb), persist = true) },
                    )
                }
            }

            Text(
                text = Strings.settingsReticleSize((style.scale * 100).roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Slider(
                value = style.scale,
                onValueChange = { apply(style.copy(scale = it), persist = false) },
                valueRange = ReticleStyle.MIN_SCALE..ReticleStyle.MAX_SCALE,
                onValueChangeFinished = { apply(style, persist = true) },
            )
        }
    }
}
