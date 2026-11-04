package com.lifelink.app

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifelink.app.core.location.MapLibreMapCallbacks
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.tile.TileOperation
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Isolated OpenGL compatibility probe for the MapLibre native SurfaceView.
 *
 * The production visual suite initializes Vulkan first. MapLibre selects its
 * renderer once per process, so this test runs in a fresh app process and
 * explicitly initializes OpenGL before the first MapView is created.
 *
 * Only public basemap tiles are requested. No donor accounts, donor coordinates,
 * identities, or backend writes are used.
 */
@RunWith(AndroidJUnit4::class)
class MapLibreOpenGlViewportTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openGlRendererCoversMapViewportAndLoadsTiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MapLibre.getInstance(
            context.applicationContext,
            null,
            WellKnownTileServer.MapLibre,
            RenderingEngine.Type.OPENGL,
        )
        assertEquals(RenderingEngine.Type.OPENGL, RenderingEngine.getCurrentType())

        val tileActions = CopyOnWriteArrayList<TileOperation>()
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = ThemeMode.LIGHT) {
                    MapLibrePrivacySafeDonorMap(
                        latitude = 51.5074,
                        longitude = -0.1278,
                        areas = emptyList(),
                        fullScreen = true,
                        callbacks = MapLibreMapCallbacks(
                            onTileAction = { operation ->
                                tileActions.add(operation)
                                Log.d("LifeLinkMapTilesOpenGL", "operation=${operation.name}")
                            },
                        ),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 70_000) {
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            tileActions.contains(TileOperation.RequestedFromNetwork) &&
                tileActions.contains(TileOperation.LoadFromNetwork) &&
                tileActions.contains(TileOperation.EndParse)
        }

        val networkRequests = tileActions.count { it == TileOperation.RequestedFromNetwork }
        val networkLoads = tileActions.count { it == TileOperation.LoadFromNetwork }
        val parsedTiles = tileActions.count { it == TileOperation.EndParse }
        val tileErrors = tileActions.count { it == TileOperation.Error }
        Log.i(
            "LifeLinkMapTilesOpenGL",
            "renderer=OPENGL networkRequested=$networkRequests networkLoaded=$networkLoads " +
                "parsed=$parsedTiles errors=$tileErrors",
        )
        assertTrue("OpenGL did not request network tiles: $tileActions", networkRequests > 0)
        assertTrue("OpenGL did not load network tiles: $tileActions", networkLoads > 0)
        assertTrue("OpenGL did not parse tiles: $tileActions", parsedTiles > 0)
        assertEquals("OpenGL reported tile errors: $tileActions", 0, tileErrors)
        composeRule.onNodeWithContentDescription("Donor map rendered")
            .assertHeightIsAtLeast(500.dp)

        val screenshotName = "lifelink-phone-light-donor-map-opengl-full-viewport.png"
        assertTrue(
            "OpenGL map screenshot could not be captured",
            VisualEvidence.capture(screenshotName),
        )
        assertMapViewportCoverage(context.filesDir, screenshotName)
    }

    private fun assertMapViewportCoverage(filesDir: File, screenshotName: String) {
        val screenshot = File(filesDir, "lifelink-evidence/$screenshotName")
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
        assertNotNull("Map viewport screenshot could not be decoded: $screenshotName", bitmap)
        val image = requireNotNull(bitmap)
        val top = image.height / 12
        val bottom = image.height * 11 / 12
        val bandHeight = (bottom - top) / VERTICAL_BANDS
        var texturedBands = 0
        val bandCounts = mutableListOf<Int>()

        for (band in 0 until VERTICAL_BANDS) {
            val colors = mutableSetOf<Int>()
            for (rowSample in 0 until ROW_SAMPLES_PER_BAND) {
                val y = top + band * bandHeight +
                    (rowSample * bandHeight / ROW_SAMPLES_PER_BAND) + bandHeight / (ROW_SAMPLES_PER_BAND * 2)
                for (columnSample in 0 until COLUMN_SAMPLES) {
                    val x = image.width * (columnSample + 1) / (COLUMN_SAMPLES + 1)
                    colors += image.getPixel(x, y)
                }
            }
            bandCounts += colors.size
            Log.i("LifeLinkMapCoverage", "renderer=OPENGL band=$band distinctColors=${colors.size}")
            if (colors.size >= MIN_DISTINCT_COLORS_PER_BAND) texturedBands += 1
        }
        image.recycle()

        Log.i(
            "LifeLinkMapCoverage",
            "renderer=OPENGL texturedBands=$texturedBands/$VERTICAL_BANDS " +
                "distinctColorsByBand=$bandCounts",
        )
        assertTrue(
            "MapLibre OpenGL tiles only cover part of the viewport. " +
                "Expected at least $MIN_TEXTURED_BANDS textured bands out of $VERTICAL_BANDS, " +
                "observed $texturedBands; per-band colors=$bandCounts. Inspect screenshot and renderer logs.",
            texturedBands >= MIN_TEXTURED_BANDS,
        )
    }

    private companion object {
        const val VERTICAL_BANDS = 8
        const val ROW_SAMPLES_PER_BAND = 8
        const val COLUMN_SAMPLES = 24
        const val MIN_DISTINCT_COLORS_PER_BAND = 4
        const val MIN_TEXTURED_BANDS = 6
    }
}
