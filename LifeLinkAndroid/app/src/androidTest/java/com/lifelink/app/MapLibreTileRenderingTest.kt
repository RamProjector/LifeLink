package com.lifelink.app

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.location.MapLibreMapCallbacks
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.RenderingEngine
import org.maplibre.android.tile.TileOperation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the production OpenFreeMap style on a real MapLibre view.
 *
 * This test uses no donor areas, donor identifiers, accounts, or backend writes.
 * The camera is centred on a public central-London basemap fixture solely to
 * exercise visible public tiles; no donor coordinates or backend data are used.
 */
@RunWith(AndroidJUnit4::class)
class MapLibreTileRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openFreeMapLoadsAndParsesNetworkTilesBeforeMapIsReportedRendered() {
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
                                Log.d("LifeLinkMapTiles", "operation=${operation.name}")
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

        val renderer = RenderingEngine.getCurrentType()
        val networkRequests = tileActions.count { it == TileOperation.RequestedFromNetwork }
        val networkLoads = tileActions.count { it == TileOperation.LoadFromNetwork }
        val parsedTiles = tileActions.count { it == TileOperation.EndParse }
        val tileErrors = tileActions.count { it == TileOperation.Error }
        Log.i(
            "LifeLinkMapTiles",
            "renderer=$renderer networkRequested=$networkRequests networkLoaded=$networkLoads parsed=$parsedTiles errors=$tileErrors",
        )
        assertEquals(
            "LifeLink explicitly selects OpenGL ES because the CI emulator's Vulkan surface rendered blank despite successful tile parsing",
            RenderingEngine.Type.OPENGL,
            renderer,
        )
        assertTrue("MapLibre did not request any network tiles: $tileActions", networkRequests > 0)
        assertTrue("MapLibre did not load any network tiles: $tileActions", networkLoads > 0)
        assertTrue("MapLibre did not parse any tiles: $tileActions", parsedTiles > 0)
        assertEquals("MapLibre reported tile request failures: $tileActions", 0, tileErrors)
        assertTrue(
            "MapLibre did not report a fully rendered map",
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithContentDescription("Donor map rendered")
            .assertHeightIsAtLeast(500.dp)
        val tileSummary = listOf(
            "renderer=${renderer.name}",
            "networkRequested=$networkRequests",
            "networkLoaded=$networkLoads",
            "parsedTiles=$parsedTiles",
            "tileErrors=$tileErrors",
            "mapRendered=true",
            "minimumViewportHeightDp=500",
        ).joinToString(separator = "\\n", postfix = "\\n")
        assertTrue(
            "MapLibre production tile diagnostics could not be saved",
            VisualEvidence.writeText("lifelink-maplibre-production-tile-summary.txt", tileSummary),
        )
        assertTrue(
            "OpenFreeMap tile-render screenshot could not be captured",
            VisualEvidence.capture("lifelink-phone-light-donor-map-openfreemap-tiles-rendered.png"),
        )
    }
}
