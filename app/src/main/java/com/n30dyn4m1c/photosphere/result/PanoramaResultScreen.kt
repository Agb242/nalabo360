package com.n30dyn4m1c.photosphere.result

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PanoramaResult"

/**
 * Long edge the preview is decoded down to.
 *
 * A 4096×2048 sphere is 32 MB decoded, on a screen that is a tenth of that wide.
 * 1440 keeps the flat preview sharp on any phone display for about 4 MB.
 */
private const val PREVIEW_MAX_DIMENSION = 1440

/** What has happened to the gallery export so far. */
private sealed interface ExportState {
    /** Not asked for yet. */
    data object Idle : ExportState

    /** Copying into the gallery. */
    data object Working : ExportState

    /** Published; [displayName] is the file name it landed under. */
    data class Done(val displayName: String) : ExportState
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
 * @param sphere the finished photo, as [SphereImageStore.writeStitchedSphere] left it
 * @param onTakeAnother discards this sphere and returns to capture
 */
@Composable
fun PanoramaResultScreen(
    sphere: StitchedSphere,
    onTakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var preview by remember(sphere.file) { mutableStateOf<ImageBitmap?>(null) }
    var isPreviewFailed by remember(sphere.file) { mutableStateOf(false) }
    var exportState by remember(sphere.file) { mutableStateOf<ExportState>(ExportState.Idle) }

    LaunchedEffect(sphere.file) {
        val decoded = withContext(Dispatchers.IO) { decodePreview(sphere.file) }
        if (decoded == null) {
            isPreviewFailed = true
            Log.w(TAG, "Could not decode a preview of ${sphere.file.name}")
        } else {
            preview = decoded.asImageBitmap()
        }
    }

    // Back means "I'm done with this one" — the same thing the button does.
    // Without this, back would leave the activity with a sphere still cached.
    BackHandler(onBack = onTakeAnother)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.result_badge),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.result_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.result_dimensions, sphere.width, sphere.height),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (BuildConfig.DEBUG && sphere.diagnostics != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
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

            SpherePreview(
                preview = preview,
                isFailed = isPreviewFailed,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ResultActions(
                exportState = exportState,
                onExport = {
                    exportState = ExportState.Working
                    scope.launch {
                        val result = MediaExporter.export(
                            context = context,
                            source = sphere.file,
                            width = sphere.width,
                            height = sphere.height,
                        )
                        result
                            .onSuccess { exported ->
                                exportState = ExportState.Done(exported.displayName)
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.result_export_success,
                                        exported.relativePath,
                                    )
                                )
                            }
                            .onFailure { error ->
                                exportState = ExportState.Idle
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.result_export_failed,
                                        error.message.orEmpty(),
                                    )
                                )
                            }
                    }
                },
                onShare = {
                    if (!context.shareSphere(sphere.file)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.result_share_failed)
                            )
                        }
                    }
                },
                onTakeAnother = onTakeAnother,
            )
        }
    }
}

/** The equirectangular frame, letterboxed into whatever space is going. */
@Composable
private fun SpherePreview(
    preview: ImageBitmap?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Clipped as well as filled, so the image inside takes the rounded
            // corners rather than painting over them.
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF101010)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            preview != null -> Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.result_preview_description),
                modifier = Modifier.fillMaxSize(),
                // Fit, not Crop: the whole 2:1 frame is the point, including the
                // uncovered poles.
                contentScale = ContentScale.Fit,
            )

            isFailed -> Text(
                text = stringResource(R.string.result_preview_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )

            else -> CircularProgressIndicator(color = Color.White)
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            // Disabled once it has landed rather than hidden: "Saved to gallery"
            // is the answer to "did that work?", and re-tapping would only file a
            // second copy.
            enabled = !isWorking && !isExported,
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
                modifier = Modifier.padding(start = 8.dp),
                text = when {
                    isWorking -> stringResource(R.string.result_exporting)
                    isExported -> stringResource(R.string.result_exported)
                    else -> stringResource(R.string.result_export)
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !isWorking,
                onClick = onShare,
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.result_share),
                )
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                // Held back during an export: the sphere it is copying from is
                // the file this button deletes.
                enabled = !isWorking,
                onClick = onTakeAnother,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.result_take_another),
                )
            }
        }

        if (exportState is ExportState.Done) {
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(
                    R.string.result_export_location,
                    MediaExporter.RELATIVE_PATH,
                    exportState.displayName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Hands the sphere to the system share sheet.
 *
 * The JPEG goes out as it is, GPano and all, so a receiving app that understands
 * 360 photos gets one. It travels as a [FileProvider] URI with a read grant
 * attached — the cache directory is private, and a `file://` URI would trip
 * `FileUriExposedException` on anything since API 24.
 *
 * Returns false if the device has nothing that can receive an image.
 */
private fun Context.shareSphere(file: File): Boolean {
    val uri = try {
        SphereImageStore.shareUri(this, file)
    } catch (e: IllegalArgumentException) {
        // Thrown when the file sits outside every path in file_paths.xml.
        Log.e(TAG, "No FileProvider path covers ${file.name}", e)
        return false
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Some targets read the grant off the ClipData rather than the extra.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        startActivity(
            Intent.createChooser(send, getString(R.string.result_share))
                // Started from a Context that may not be an Activity.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing on this device can receive an image", e)
        false
    }
}

/** Decodes [file] down to something a phone screen can hold. */
private fun decodePreview(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_DIMENSION)
    }
    return BitmapFactory.decodeFile(file.path, options)
}
