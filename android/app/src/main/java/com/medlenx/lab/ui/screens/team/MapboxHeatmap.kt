package com.medlenx.lab.ui.screens.team

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation

/**
 * The Mapbox-backed heatmap: the native counterpart to the web app's
 * Leaflet + OpenStreetMap panel.
 *
 * EVERY MAPBOX SYMBOL IN THE APP LIVES IN THIS FILE. That is deliberate. This
 * module cannot be compiled in the sandbox that produced it, so if any import or
 * signature here is wrong, the damage is one file rather than a hunt through the
 * UI: delete it, drop the two dependencies, and the heatmap reverts to its
 * offline Canvas layer.
 *
 * Import paths were verified against mapbox/mapbox-maps-android main rather than
 * guessed, because the Compose annotation composables sit under a `.generated`
 * subpackage that the prose documentation does not mention.
 *
 * It is only reached when a token is configured, so a checkout without one never
 * loads the SDK at all - see TeamMapSection.
 */
@Composable
fun MapboxHeatmapLayer(
    bubbles: List<MapBubbleSpec>,
    modifier: Modifier = Modifier,
) {
    // Bangladesh, matching the web app's `setView([23.685, 90.3563], 7)`.
    // Point.fromLngLat takes LONGITUDE FIRST - reversing these two arguments puts
    // the viewport in the Indian Ocean and the failure looks like a blank map.
    val viewport = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(90.3563, 23.685))
            zoom(7.0)
            pitch(0.0)
            bearing(0.0)
        }
    }

    MapboxMap(
        modifier = modifier,
        mapViewportState = viewport,
    ) {
        bubbles.forEach { b ->
            CircleAnnotation(point = Point.fromLngLat(b.lng, b.lat)) {
                // Same growth curve as the offline DataBubbleLayer: 14dp at zero
                // volume up to 62dp, so switching between the two renderers does
                // not change what the bubbles mean.
                circleRadius = 7.0 + b.volume.coerceAtMost(60) * 0.4
                circleColor = b.tint
                circleStrokeWidth = 2.0
                circleStrokeColor = Color.White
            }
        }
    }
}
