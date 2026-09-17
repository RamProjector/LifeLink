package com.lifelink.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): DeviceLocation? = suspendCancellableCoroutine { continuation ->
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                continuation.resume(location?.let { DeviceLocation(it.latitude, it.longitude, it.accuracy.toInt().coerceIn(10, 10000)) })
            }
            .addOnFailureListener { continuation.resume(null) }
    }
}

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val precisionMeters: Int
)
