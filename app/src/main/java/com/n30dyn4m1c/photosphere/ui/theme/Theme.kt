package com.n30dyn4m1c.photosphere.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = SphereBlue80,
    secondary = SphereBlueGrey80,
    tertiary = SphereTeal80,
)

private val LightColors = lightColorScheme(
    primary = SphereBlue40,
    secondary = SphereBlueGrey40,
    tertiary = SphereTeal40,
)

@Composable
fun PhotoSphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You, available from API 31.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PhotoSphereTypography,
        content = content,
    )
}
