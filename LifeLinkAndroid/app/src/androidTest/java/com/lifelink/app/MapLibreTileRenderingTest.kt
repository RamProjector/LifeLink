package com.lifelink.app

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.tile.TileOperation

/**
 * Verifies the production OpenFreeMap style on a real MapLibre view.
 *
 * This test uses no donor areas, donor identifiers, accounts, or backend writes.
 * The camera is centred on the Null Island test coordinate solely to exercise
 * public basemap tiles without representing a donor's location.
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
                        latitude = 0.0,
                        longitude = 0.0,
                        areas = emptyList(),
                        onTileAction = { operation ->
                            tileActions.add(operation)
                            Log.d("LifeLinkMapTiles", "operation=${operation.name}")
                        },
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty() &&
                tileActions.contains(TileOperation.LoadFromNetwork) &&
                tileActions.contains(TileOperation.EndParse)
        }

        val networkRequests = tileActions.count { it == TileOperation.RequestedFromNetwork }
        val networkLoads = tileActions.count { it == TileOperation.LoadFromNetwork }
        val parsedTiles = tileActions.count { it == TileOperation.EndParse }
        val tileErrors = tileActions.count { it == TileOperation.Error }
        Log.i(
            "LifeLinkMapTiles",
            "networkRequested=$networkRequests networkLoaded=$networkLoads parsed=$parsedTiles errors=$tileErrors",
        )

        assertTrue("MapLibre did not request any network tiles: $tileActions", networkRequests > 0)
        assertTrue("MapLibre did not load any network tiles: $tileActions", networkLoads > 0)
        assertTrue("MapLibre did not parse any tiles: $tileActions", parsedTiles > 0)
        assertTrue(
            "MapLibre did not report a fully rendered map",
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "OpenFreeMap tile-render screenshot could not be captured",
            VisualEvidence.capture("lifelink-phone-light-donor-map-openfreemap-tiles-rendered.png"),
        )
    }
}
