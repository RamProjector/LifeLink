package com.lifelink.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "donor_profiles")
data class DonorProfileEntity(
    @androidx.room.PrimaryKey val donorId: String,
    val displayName: String,
    val bloodType: String?,
    val area: String,
    val serviceRadiusKm: Int,
    val availability: String,
    val verified: Boolean
)

@Entity(tableName = "donor_requests")
data class DonorRequestEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val bloodType: String,
    val units: Int,
    val urgency: String,
    val facilityName: String,
    val area: String,
    val distanceKm: Double,
    val response: String?
)

@Dao
interface DonorDao {
    @Query("SELECT * FROM donor_profiles WHERE donorId = 'local-donor' LIMIT 1")
    fun observeProfile(): Flow<DonorProfileEntity?>

    @Query("SELECT * FROM donor_requests ORDER BY urgency DESC")
    fun observeRequests(): Flow<List<DonorRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: DonorProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(request: DonorRequestEntity)

    @Query("UPDATE donor_requests SET response = :response WHERE requestId = :requestId")
    suspend fun updateResponse(requestId: String, response: String)
}
