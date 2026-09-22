package com.medlenx.lab.ui.screens.team

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx

/**
 * Bangladesh's rough bounding box, for the equirectangular fit.
 *
 * Lives here, next to the base map, because both layers project through it.
 */
internal const val BD_LAT_MAX = 26.7
internal const val BD_LAT_MIN = 20.5
internal const val BD_LNG_MIN = 88.0
internal const val BD_LNG_MAX = 92.7

/**
 * Projects a fix onto 0..1 canvas fractions, clamped so a stray pin stays visible.
 */
internal fun project(lat: Double, lng: Double): Offset = Offset(
    x = ((lng - BD_LNG_MIN) / (BD_LNG_MAX - BD_LNG_MIN)).toFloat().coerceIn(0f, 1f),
    y = ((BD_LAT_MAX - lat) / (BD_LAT_MAX - BD_LAT_MIN)).toFloat().coerceIn(0f, 1f),
)

/**
 * The base map of Bangladesh: one dot per district, always drawn.
 *
 * **This composable deliberately takes no data parameters.** The previous version
 * drew from `TeamViewModel.districtCentroids`, so the "map" was coupled to the same
 * async load as the metrics: when the load was empty, slow or failed, the module
 * rendered a blank box and looked broken rather than empty. Geography has no
 * dependency on scan data — every district exists on the map whether or not this
 * device has audited anything in it.
 *
 * The 63 coordinates are baked from `data/bd_geo.json` at authoring time, so
 * there is nothing to fetch, parse or await. They are real district centroids, so
 * the familiar shape of the country emerges without bundling a tile provider or a
 * vector outline (the repo holds centroids, not polygons).
 *
 * Draw it first inside a `Box` so it sits at the bottom of the Z-order, with the
 * data bubbles layered over it.
 */
@Composable
fun BaseMapLayer(
    modifier: Modifier = Modifier,
    dotColor: androidx.compose.ui.graphics.Color = Mlx.Brand400,
) {
    Canvas(modifier = modifier) {
        val radius = 3.5.dp.toPx()
        // Pairs: (lat, lng) per district.
        var i = 0
        while (i < DISTRICT_CENTROIDS.size) {
            val f = project(
                DISTRICT_CENTROIDS[i].toDouble(),
                DISTRICT_CENTROIDS[i + 1].toDouble(),
            )
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(f.x * size.width, f.y * size.height),
            )
            i += 2
        }
    }
}

/** Flat [lat, lng, lat, lng, ...] — see `data/bd_geo.json`. */
private val DISTRICT_CENTROIDS = floatArrayOf(
    22.6516f, 89.7859f,  // Bagerhat
    22.1953f, 92.2184f,  // Bandarban
    22.0953f, 90.1121f,  // Barguna
    22.7010f, 90.3535f,  // Barishal
    22.6859f, 90.6482f,  // Bhola
    24.8465f, 89.3777f,  // Bogura
    23.9571f, 91.1119f,  // Brahmanbaria
    23.2333f, 90.6712f,  // Chandpur
    24.5965f, 88.2775f,  // Chapainawabganj
    22.3569f, 91.7832f,  // Chattogram
    23.6402f, 88.8412f,  // Chuadanga
    23.4607f, 91.1809f,  // Cumilla
    23.8103f, 90.4125f,  // Dhaka
    25.6217f, 88.6354f,  // Dinajpur
    23.6070f, 89.8429f,  // Faridpur
    23.0159f, 91.3976f,  // Feni
    25.3288f, 89.5357f,  // Gaibandha
    24.0029f, 90.4255f,  // Gazipur
    23.0050f, 89.8266f,  // Gopalganj
    24.3745f, 91.4155f,  // Habiganj
    24.9375f, 89.9373f,  // Jamalpur
    23.1664f, 89.2081f,  // Jashore
    22.6406f, 90.1987f,  // Jhalokathi
    23.5459f, 89.1539f,  // Jhenaidah
    25.0968f, 89.0227f,  // Joypurhat
    23.1193f, 91.9847f,  // Khagrachhari
    22.8456f, 89.5403f,  // Khulna
    24.4258f, 90.7855f,  // Kishoreganj
    25.8054f, 89.6362f,  // Kurigram
    23.9013f, 89.1206f,  // Kushtia
    22.9447f, 90.8282f,  // Lakshmipur
    25.9172f, 89.4552f,  // Lalmonirhat
    23.1641f, 90.1896f,  // Madaripur
    23.4855f, 89.4195f,  // Magura
    23.8617f, 90.0003f,  // Manikganj
    23.7622f, 88.6318f,  // Meherpur
    24.4829f, 91.7774f,  // Moulvibazar
    23.5422f, 90.5305f,  // Munshiganj
    24.7471f, 90.4203f,  // Mymensingh
    24.7936f, 88.9318f,  // Naogaon
    23.1725f, 89.5127f,  // Narail
    23.6238f, 90.5000f,  // Narayanganj
    23.9322f, 90.7158f,  // Narsingdi
    24.4206f, 89.0000f,  // Natore
    24.8703f, 90.7279f,  // Netrokona
    25.9310f, 88.8560f,  // Nilphamari
    22.8696f, 91.0994f,  // Noakhali
    24.0064f, 89.2372f,  // Pabna
    26.3411f, 88.5542f,  // Panchagarh
    22.3596f, 90.3299f,  // Patuakhali
    22.5795f, 89.9713f,  // Pirojpur
    23.7575f, 89.6333f,  // Rajbari
    24.3745f, 88.6042f,  // Rajshahi
    22.6533f, 92.1799f,  // Rangamati
    25.7439f, 89.2752f,  // Rangpur
    22.7185f, 89.0705f,  // Satkhira
    23.2423f, 90.4348f,  // Shariatpur
    25.0205f, 90.0153f,  // Sherpur
    24.4534f, 89.7007f,  // Sirajganj
    25.0658f, 91.3950f,  // Sunamganj
    24.8949f, 91.8687f,  // Sylhet
    24.2513f, 89.9167f,  // Tangail
    26.0337f, 88.4616f,  // Thakurgaon
)
