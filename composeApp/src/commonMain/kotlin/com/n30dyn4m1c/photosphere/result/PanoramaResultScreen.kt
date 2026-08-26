package com.n30dyn4m1c.photosphere.result

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.isDebugBuild
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.RgbImage
import com.n30dyn4m1c.photosphere.stitching.platformImageCodec
import com.n30dyn4m1c.photosphere.ui.BackPressHandler
import com.n30dyn4m1c.photosphere.ui.SphereViewer
import com.n30dyn4m1c.photosphere.ui.Strings
import com.n30dyn4m1c.photosphere.ui.decodeSpherePreview
import com.n30dyn4m1c.photosphere.ui.exportSphereToGallery
import com.n30dyn4m1c.photosphere.ui.shareSphere
import com.n30dyn4m1c.photosphere.ui.theme.PhotoWell
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long edge the preview is decoded down to.
 *
 * A 4096×2048 sphere is 32 MB decoded, on a screen that is a tenth of that wide.
 * 1440 keeps the flat preview sharp on any phone display for about 4 MB.
 */
private const val PREVIEW_MAX_DIMENSION = 1440

/**
 * Long edge the interactive 360° viewer decodes the sphere to.
 *
 * The viewer re-projects this buffer on every drag frame, so it wants more
 * resolution than the flat preview to stay crisp when zoomed — but it is held
 * as raw RGB for as long as the viewer is open. 2560 lands near 10 MB, which
 * coexists comfortably with everything else this screen keeps alive.
 */
private const val VIEWER_MAX_DIMENSION = 2560

/** What has happened to the gallery export so far. */
private sealed interface ExportState {
    /** Not asked for yet. */
    data object Idle : ExportState

    /** Copying into the gallery. */
    data object Working : ExportState

    /** Published; [displayName] and [locationLabel] say where it landed. */
    data class Done(val displayName: String, val locationLabel: String) : ExportState
}

/**
 * What the user sees when a stitch finishes: the sphere, and what to do with it.
 *
 * The photo exists as a GPano-tagged JPEG in the app's cache by the time this
 * screen appears — nothing has been published yet. That is deliberate: a run
 * that came out badly should not have to be deleted out of the camera roll
 * afterwards. From here it can go to the gallery, out through the share sheet,
 * or nowhere at all.
 *
 * The preview is flat, not a sphere viewer. It shows the equirectangular frame
 * as it is, which is the honest picture of what was captured: the black wedges
 * at the poles are the parts of the sphere the run never reached, and they are
 * worth seeing before deciding to keep it.
 *
 * @param sphere the finished photo, as the stitcher's write step left it
 * @param onTakeAnother discards this sphere and returns to capture
 */
@Composable
fun PanoramaResultScreen(
    sphere: StitchedSphere,
    onTakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var preview by remember(sphere.file) { mutableStateOf<ImageBitmap?>(null) }
    var isPreviewFailed by remember(sphere.file) { mutableStateOf(false) }
    var exportState by remember(sphere.file) { mutableStateOf<ExportState>(ExportState.Idle) }

    // The 360° pane is opt-in and its source decoded lazily: most visits to
    // this screen end in Export or Share, and a second full decode of the file
    // would only be paid by people who actually want to look around.
    var isViewerActive by remember(sphere.file) { mutableStateOf(false) }
    var viewerSource by remember(sphere.file) { mutableStateOf<RgbImage?>(null) }
    var isViewerSourceLoading by remember(sphere.file) { mutableStateOf(false) }

    LaunchedEffect(sphere.file) {
        val decoded = decodeSpherePreview(sphere.file, PREVIEW_MAX_DIMENSION)
        if (decoded == null) {
            isPreviewFailed = true
        } else {
            preview = decoded
        }
    }

    // Decode happens once per sphere, on first entry into the viewer.
    LaunchedEffect(isViewerActive, sphere.file) {
        if (!isViewerActive || viewerSource != null) return@LaunchedEffect
        isViewerSourceLoading = true
        val decoded = withContext(Dispatchers.Default) {
            platformImageCodec().decodeJpeg(sphere.file, VIEWER_MAX_DIMENSION)
        }
        isViewerSourceLoading = false
        if (decoded == null) {
            // Back out gracefully — the flat preview is still there, and the
            // photo itself is fine; only the viewer could not be prepared.
            isViewerActive = false
            snackbarHostState.showSnackbar(Strings.RESULT_PREVIEW_FAILED)
        } else {
            viewerSource = decoded
        }
    }

    // Leaving discards the sphere, and the file is only in the cache — so if it
    // has not been exported, "back" is a delete. Both routes out ask first; see
    // [DiscardConfirmation].
    var isConfirmingDiscard by remember(sphere.file) { mutableStateOf(false) }
    val isSaved = exportState is ExportState.Done

    /** Leaves the screen, pausing to confirm if the photo would be lost. */
    fun leave() {
        // Back out of the 360° view first — leaving the screen is a separate,
        // deliberate gesture from closing it.
        if (isViewerActive) {
            isViewerActive = false
            return
        }
        if (isSaved) onTakeAnother() else isConfirmingDiscard = true
    }

    // Back means "I'm done with this one" — the same thing the button does.
    // Without this, back would leave the app with a sphere still cached.
    BackPressHandler(onBack = ::leave)

    if (isConfirmingDiscard) {
        DiscardConfirmation(
            onDismiss = { isConfirmingDiscard = false },
            onDiscard = {
                isConfirmingDiscard = false
                onTakeAnother()
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The header is deliberately compact — a badge, a title, a line of
            // metadata. Everything above the photograph is space taken from the
            // photograph, and on this screen the photograph is the reason the
            // user is here.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Surface(
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = Strings.RESULT_BADGE,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = Strings.RESULT_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = Strings.resultDimensions(sphere.width, sphere.height),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isDebugBuild && sphere.diagnostics != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            text = sphere.diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (isViewerActive) {
                    ViewerPane(
                        source = viewerSource,
                        isLoadingSource = isViewerSourceLoading,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Surface(
                        shape = PillShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    ) {
                        IconButton(onClick = { isViewerActive = false }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = Strings.RESULT_VIEWER_CLOSE_DESCRIPTION,
                                tint = Color.White,
                            )
                        }
                    }
                    Text(
                        text = Strings.VIEWER_HINT,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                    )
                } else {
                    SpherePreview(
                        preview = preview,
                        isFailed = isPreviewFailed,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // The invitation into the 360° view floats on the photo it
                    // opens — visible exactly when there is something to open.
                    if (preview != null) {
                        FilledTonalButton(
                            onClick = { isViewerActive = true },
                            shape = PillShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.55f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Panorama,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                modifier = Modifier.padding(start = 6.dp),
                                text = Strings.RESULT_EXPLORE_360,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            ResultActions(
                exportState = exportState,
                onExport = {
                    exportState = ExportState.Working
                    scope.launch {
                        exportSphereToGallery(sphere)
                            .onSuccess { exported ->
                                exportState = ExportState.Done(
                                    displayName = exported.displayName,
                                    locationLabel = exported.locationLabel,
                                )
                                snackbarHostState.showSnackbar(
                                    Strings.resultExportSuccess(exported.locationLabel)
                                )
                            }
                            .onFailure { error ->
                                exportState = ExportState.Idle
                                snackbarHostState.showSnackbar(
                                    Strings.resultExportFailed(error.message.orEmpty())
                                )
                            }
                    }
                },
                onShare = {
                    if (!shareSphere(sphere, Strings.RESULT_SHARE)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(Strings.RESULT_SHARE_FAILED)
                        }
                    }
                },
                onTakeAnother = ::leave,
            )
        }
    }
}

/**
 * Asks before throwing away a sphere that only exists in the cache.
 *
 * Starting a new capture — or pressing back — deletes this one, and until it has
 * been exported the cached JPEG is the only copy there is. That is minutes of
 * standing in one place turning on the spot, undone by one tap on a button
 * sitting directly beside "Share". Once the photo has been saved to the gallery
 * the question stops being worth asking, and this never appears.
 */
@Composable
private fun DiscardConfirmation(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = { Text(Strings.RESULT_DISCARD_TITLE) },
        text = { Text(Strings.RESULT_DISCARD_MESSAGE) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = Strings.RESULT_DISCARD_CONFIRM,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.RESULT_DISCARD_CANCEL)
            }
        },
    )
}

/**
 * The recessed frame the photograph lives in — shared by the flat preview and
 * the 360° pane so switching between them never visibly moves the edges.
 */
@Composable
private fun PreviewWell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(PhotoWell)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The equirectangular frame, letterboxed into whatever space is going. */
@Composable
private fun SpherePreview(
    preview: ImageBitmap?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    PreviewWell(modifier = modifier) {
        when {
            preview != null -> Image(
                bitmap = preview,
                contentDescription = Strings.RESULT_PREVIEW_DESCRIPTION,
                modifier = Modifier.fillMaxSize(),
                // Fit, not Crop: the whole 2:1 frame is the point, including the
                // uncovered poles.
                contentScale = ContentScale.Fit,
            )

            isFailed -> Text(
                text = Strings.RESULT_PREVIEW_FAILED,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * The interactive 360° pane — a [com.n30dyn4m1c.photosphere.ui.SphereViewer]
 * in the same well the flat preview occupied.
 */
@Composable
private fun ViewerPane(
    source: RgbImage?,
    isLoadingSource: Boolean,
    modifier: Modifier = Modifier,
) {
    PreviewWell(modifier = modifier) {
        when {
            source != null -> SphereViewer(source = source, modifier = Modifier.fillMaxSize())
            isLoadingSource -> CircularProgressIndicator(color = Color.White)
            else -> Text(
                text = Strings.RESULT_PREVIEW_FAILED,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** Export, share, and start again. */
@Composable
private fun ResultActions(
    exportState: ExportState,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onTakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExported = exportState is ExportState.Done
    val isWorking = exportState is ExportState.Working

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Save is the one action with consequences, so it gets the full width,
        // the filled treatment and a thumb-sized target; share and discard sit
        // below it as equals. The hierarchy is the recommendation.
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = PillShape,
            // Disabled once it has landed rather than hidden: "Saved to gallery"
            // is the answer to "did that work?", and re-tapping would only file a
            // second copy.
            enabled = !isWorking && !isExported,
            colors = ButtonDefaults.buttonColors(
                // A landed export keeps the accent instead of greying out. It is
                // disabled because the work is done, not because it is
                // unavailable, and a dimmed control reads as the latter.
                disabledContainerColor = if (isExported) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                disabledContentColor = if (isExported) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            ),
            onClick = onExport,
        ) {
            when {
                isWorking -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )

                isExported -> Icon(Icons.Filled.Check, contentDescription = null)
                else -> Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            }
            Text(
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                text = when {
                    isWorking -> Strings.RESULT_EXPORTING
                    isExported -> Strings.RESULT_EXPORTED
                    else -> Strings.RESULT_EXPORT
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = PillShape,
                enabled = !isWorking,
                onClick = onShare,
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = Strings.RESULT_SHARE,
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = PillShape,
                // Held back during an export: the sphere it is copying from is
                // the file this button deletes.
                enabled = !isWorking,
                onClick = onTakeAnother,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = Strings.RESULT_TAKE_ANOTHER,
                )
            }
        }

        if (exportState is ExportState.Done) {
            Text(
                text = Strings.resultExportLocation(
                    exportState.locationLabel,
                    exportState.displayName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
