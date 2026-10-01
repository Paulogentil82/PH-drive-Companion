package com.phdrive.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: LocalTrip)

    @Update
    suspend fun updateTrip(trip: LocalTrip)

    @Query("SELECT * FROM trips WHERE localId = :localId LIMIT 1")
    suspend fun getTripByLocalId(localId: String): LocalTrip?

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveTrip(): LocalTrip?

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveTrip(): Flow<LocalTrip?>

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeAllTrips(): Flow<List<LocalTrip>>

    @Query("SELECT * FROM trips WHERE syncStatus != 'SYNCED' OR remoteTripId IS NULL")
    suspend fun getTripsNeedingSync(): List<LocalTrip>

    @Query("SELECT COUNT(*) FROM trips WHERE syncStatus != 'SYNCED' OR remoteTripId IS NULL")
    fun observePendingTripsCount(): Flow<Int>

    @Query("UPDATE trips SET remoteTripId = :remoteTripId, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun updateRemoteTripId(localId: String, remoteTripId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE trips SET syncStatus = :syncStatus, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun markSyncStatus(localId: String, syncStatus: SyncStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE trips SET gpsDistanceKm = :distanceKm, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun updateGpsDistance(localId: String, distanceKm: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM trips ORDER BY startedAt ASC")
    suspend fun getAllTrips(): List<LocalTrip>

    @Query("SELECT COUNT(*) FROM trips")
    fun observeTotalTripsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun countAllTrips(): Int

    @Query("SELECT COUNT(*) FROM trips WHERE syncStatus != 'SYNCED' OR remoteTripId IS NULL")
    suspend fun countPendingTrips(): Int
}
