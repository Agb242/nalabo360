package com.n30dyn4m1c.photosphere

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.n30dyn4m1c.photosphere.ui.AppContextHolder
import com.n30dyn4m1c.photosphere.ui.PhotoSphereApp
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme

/**
 * A thin Android host: edge-to-edge setup and the shared Compose app.
 *
 * Everything that used to live here — navigation, permissions, screens — moved
 * into common code so the iOS build runs the same app; the activity now only
 * supplies what is genuinely Android's to give: a window and its system bars.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Export and share fire from coroutine bodies where no LocalContext
        // exists; park the application context before any UI can need it.
        AppContextHolder.init(applicationContext)

        // Both bars stay transparent over the viewfinder, and both are forced to
        // *light* icons. The default follows the system's day/night setting,
        // which on a phone in light mode paints dark status-bar icons — over a
        // black viewfinder, or a night scene, that is a clock nobody can read.
        // The app's own scheme is dark in every configuration (see
        // PhotoSphereTheme), so the bars are pinned to match it rather than the
        // system.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PhotoSphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PhotoSphereApp()
                }
            }
        }
    }
}
