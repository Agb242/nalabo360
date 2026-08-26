package com.n30dyn4m1c.photosphere.settings

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-trips the reticle style through the real repository on a real (temp)
 * file system — the format is two scalars in a file, so the honest test is the
 * write-then-read, not a mock.
 */
class ReticleSettingsRepositoryTest {

    private fun tempDirectory(): okio.Path {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "reticle_settings_test_${(0..Long.MAX_VALUE).random()}"
        FileSystem.SYSTEM.createDirectories(directory)
        return directory
    }

    @Test
    fun missingFileLoadsTheDefault() {
        assertEquals(ReticleStyle(), ReticleSettingsRepository(tempDirectory()).load())
    }

    @Test
    fun savedStyleRoundTrips() {
        val repository = ReticleSettingsRepository(tempDirectory())
        val style = ReticleStyle(colorArgb = 0xFFFFEB3B, scale = 1.7f)
        repository.save(style)
        assertEquals(style, repository.load())
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        val directory = tempDirectory()
        FileSystem.SYSTEM.write(directory / "reticle_style.ini") {
            writeUtf8("color=not-a-number\nscale=also-bad\n")
        }
        assertEquals(ReticleStyle(), ReticleSettingsRepository(directory).load())
    }

    @Test
    fun outOfRangeScaleIsClampedOnLoad() {
        val directory = tempDirectory()
        FileSystem.SYSTEM.write(directory / "reticle_style.ini") {
            writeUtf8("color=${0xFF00E5FF}\nscale=9.5\n")
        }
        assertEquals(
            ReticleStyle(colorArgb = 0xFF00E5FF, scale = ReticleStyle.MAX_SCALE),
            ReticleSettingsRepository(directory).load(),
        )
    }

    @Test
    fun outOfRangeScaleIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { ReticleStyle(scale = 5f) }
    }
}
