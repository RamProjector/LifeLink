package com.lifelink.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt
import kotlin.coroutines.resume

class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val settingsClient = LocationServices.getSettingsClient(context)

    fun checkLocationSettings(
        onReady: () -> Unit,
        onNeedsResolution: (ResolvableApiException) -> Unit,
        onFailure: () -> Unit
    ) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_TIMEOUT_MILLIS)
            .setDurationMillis(LOCATION_TIMEOUT_MILLIS)
            .build()
        val settings = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .build()
        settingsClient.checkLocationSettings(settings)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) onNeedsResolution(error) else onFailure()
            }
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): DeviceLocation? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_CACHED_LOCATION_AGE_MILLIS)
            .setDurationMillis(LOCATION_TIMEOUT_MILLIS)
            .build()
        continuation.invokeOnCancellation { cancellation.cancel() }
        client.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location ->
                if (!continuation.isActive) return@addOnSuccessListener
                continuation.resume(location?.toDeviceLocation())
            }
            .addOnFailureListener {
                if (continuation.isActive) continuation.resume(null)
            }
    }

    private fun android.location.Location.toDeviceLocation(): DeviceLocation? {
        val accuracy = accuracy.takeIf { it.isFinite() && it > 0f }?.roundToInt() ?: return null
        return DeviceLocation(latitude, longitude, accuracy.coerceIn(1, 10_000))
    }

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 15_000L
        const val MAX_CACHED_LOCATION_AGE_MILLIS = 30_000L
    }
}

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val precisionMeters: Int
)
