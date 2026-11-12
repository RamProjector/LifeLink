package com.lifelink.app

import android.content.Context
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
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.tile.TileOperation
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Isolated OpenGL compatibility probe for the MapLibre native SurfaceView.
 *
 * LifeLink's map component explicitly selects OpenGL ES before creating its
 * first MapView. MapLibre selects its renderer once per process, so this probe
 * runs in a fresh target process and verifies that the app's renderer choice,
 * network tile loading, and native viewport remain correct after startup.
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
        initializeOpenGl(context)
        clearAmbientCache(context)
        val tileActions = CopyOnWriteArrayList<TileOperation>()
        renderPublicBasemap(tileActions)
        awaitRenderedMapAndNetworkTiles(tileActions)
        assertNetworkTileOperations(tileActions)
        composeRule.onNodeWithContentDescription("Donor map rendered")
            .assertHeightIsAtLeast(500.dp)
        val viewportSummary = captureAndAssertViewport(context.filesDir)
        val tileSummary = listOf(
            "renderer=OPENGL",
            "ambientCacheCleared=true",
            "networkRequested=${tileActions.count { it == TileOperation.RequestedFromNetwork }}",
            "networkLoaded=${tileActions.count { it == TileOperation.LoadFromNetwork }}",
            "parsedTiles=${tileActions.count { it == TileOperation.EndParse }}",
            "tileErrors=${tileActions.count { it == TileOperation.Error }}",
            "mapRendered=true",
            viewportSummary,
        ).joinToString(separator = "\n", postfix = "\n")
        assertTrue(
            "MapLibre OpenGL diagnostics could not be saved",
            VisualEvidence.writeText("lifelink-maplibre-opengl-summary.txt", tileSummary),
        )
    }

    private fun initializeOpenGl(context: Context) {
        var selectedRenderer: RenderingEngine.Type? = null
        // MapLibre's global initialization is UI-thread-only. Instrumentation
        // methods run on the test thread, so use runOnMainSync rather than
        // Activity.runOnUiThread (which would post asynchronously and race the
        // renderer assertion).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapLibre.getInstance(
                context.applicationContext,
                null,
                WellKnownTileServer.MapLibre,
                RenderingEngine.Type.OPENGL,
            )
            selectedRenderer = RenderingEngine.getCurrentType()
        }
        assertEquals(RenderingEngine.Type.OPENGL, selectedRenderer)
    }

    /**
     * The production visual suite runs first and can warm MapLibre's shared ambient tile
     * cache. Clear only that cache in this disposable emulator before the
     * OpenGL probe, so LoadFromNetwork is deterministic rather than depending
     * on whichever tiles the previous test happened to cache. Offline regions
     * and app/account data are not touched.
     */
    private fun clearAmbientCache(context: Context) {
        val completed = CountDownLatch(1)
        var succeeded = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            OfflineManager.getInstance(context.applicationContext).clearAmbientCache(
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        succeeded = true
                        completed.countDown()
                    }

                    override fun onError(message: String) {
                        Log.e("LifeLinkMapTilesOpenGL", "Ambient tile cache clear failed.")
                        completed.countDown()
                    }
                },
            )
        }
        assertTrue(
            "MapLibre ambient cache clear did not complete within 30 seconds",
            completed.await(30, TimeUnit.SECONDS),
        )
        assertTrue(
            "MapLibre ambient cache could not be cleared; fresh network tile loading cannot be verified",
            succeeded,
        )
        Log.i("LifeLinkMapTilesOpenGL", "ambientCacheCleared=true")
    }

    private fun renderPublicBasemap(tileActions: CopyOnWriteArrayList<TileOperation>) {
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
    }

    private fun awaitRenderedMapAndNetworkTiles(tileActions: List<TileOperation>) {
        composeRule.waitUntil(timeoutMillis = 70_000) {
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            tileActions.contains(TileOperation.RequestedFromNetwork) &&
                tileActions.contains(TileOperation.LoadFromNetwork) &&
                tileActions.contains(TileOperation.EndParse)
        }
    }

    private fun assertNetworkTileOperations(tileActions: List<TileOperation>) {
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
    }

    private fun captureAndAssertViewport(filesDir: File): String {
        val screenshotName = "lifelink-phone-light-donor-map-opengl-full-viewport.png"
        assertTrue(
            "OpenGL map screenshot could not be captured",
            VisualEvidence.capture(screenshotName),
        )
        return assertMapViewportCoverage(filesDir, screenshotName)
    }

    private fun assertMapViewportCoverage(filesDir: File, screenshotName: String): String {
        val screenshot = File(filesDir, "lifelink-evidence/$screenshotName")
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
        assertNotNull("Map viewport screenshot could not be decoded: $screenshotName", bitmap)
        val image = requireNotNull(bitmap)
        val viewportWidth = image.width
        val viewportHeight = image.height
        val top = viewportHeight / 12
        val bottom = viewportHeight * 11 / 12
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
        assertCoverageAcrossViewport(texturedBands, bandCounts)
        return listOf(
            "screenshot=$screenshotName",
            "viewportWidthPx=$viewportWidth",
            "viewportHeightPx=$viewportHeight",
            "texturedBands=$texturedBands/$VERTICAL_BANDS",
            "minimumTexturedBands=$MIN_TEXTURED_BANDS",
            "distinctColorsByBand=$bandCounts",
        ).joinToString(separator = "\n")
    }

    private fun assertCoverageAcrossViewport(texturedBands: Int, bandCounts: List<Int>) {
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
