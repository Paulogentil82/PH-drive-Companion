package com.phdrive.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TripPointDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: LocalTripPoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<LocalTripPoint>)

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId ORDER BY sequenceNumber ASC")
    suspend fun getPointsForTrip(localTripId: String): List<LocalTripPoint>

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId ORDER BY sequenceNumber ASC")
    fun observePointsForTrip(localTripId: String): Flow<List<LocalTripPoint>>

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun getLatestPoint(localTripId: String): LocalTripPoint?

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId ORDER BY sequenceNumber DESC LIMIT 1")
    fun observeLatestPoint(localTripId: String): Flow<LocalTripPoint?>

    @Query("SELECT COUNT(*) FROM trip_points WHERE localTripId = :localTripId")
    fun observePointCount(localTripId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points WHERE localTripId = :localTripId")
    suspend fun countPoints(localTripId: String): Int

    @Query("SELECT COALESCE(MAX(sequenceNumber), -1) FROM trip_points WHERE localTripId = :localTripId")
    suspend fun getMaxSequenceNumber(localTripId: String): Int

    // Queries para Sincronização
    @Query("SELECT * FROM trip_points WHERE syncStatus != 'SYNCED' ORDER BY sequenceNumber ASC LIMIT :limit")
    suspend fun getPendingPoints(limit: Int): List<LocalTripPoint>

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId AND syncStatus != 'SYNCED' ORDER BY sequenceNumber ASC LIMIT :limit")
    suspend fun getPendingPointsForTrip(localTripId: String, limit: Int): List<LocalTripPoint>

    @Query("SELECT DISTINCT localTripId FROM trip_points WHERE syncStatus != 'SYNCED'")
    suspend fun getTripIdsWithPendingPoints(): List<String>

    @Query("SELECT COUNT(*) FROM trip_points WHERE syncStatus != 'SYNCED'")
    fun observeAllPendingPointsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points WHERE syncStatus != 'SYNCED'")
    suspend fun countAllPendingPoints(): Int

    @Query("SELECT COUNT(*) FROM trip_points WHERE syncStatus = 'FAILED'")
    fun observeAllFailedPointsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points WHERE localTripId = :localTripId AND syncStatus != 'SYNCED'")
    fun observePendingPointCount(localTripId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points WHERE localTripId = :localTripId AND syncStatus = 'SYNCED'")
    fun observeSyncedPointCount(localTripId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points WHERE localTripId = :localTripId AND syncStatus != 'SYNCED'")
    suspend fun countPendingPoints(localTripId: String): Int

    @Query("UPDATE trip_points SET syncStatus = 'SYNCED', lastSyncAttempt = :timestamp WHERE localId IN (:localIds)")
    suspend fun markPointsSynced(localIds: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE trip_points SET syncStatus = 'FAILED', retryCount = retryCount + 1, lastSyncAttempt = :timestamp WHERE localId = :localId")
    suspend fun markPointFailed(localId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE trip_points SET syncStatus = 'FAILED', retryCount = retryCount + 1, lastSyncAttempt = :timestamp WHERE localId IN (:localIds)")
    suspend fun markPointsFailed(localIds: List<String>, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun insertPointAndUpdateSequence(point: LocalTripPoint) {
        insertPoint(point)
    }

    @Query("SELECT * FROM trip_points ORDER BY localTripId, sequenceNumber ASC")
    suspend fun getAllPoints(): List<LocalTripPoint>

    @Query("SELECT * FROM trip_points WHERE localTripId = :localTripId AND sequenceNumber = :sequenceNumber LIMIT 1")
    suspend fun getPointByTripAndSequence(localTripId: String, sequenceNumber: Int): LocalTripPoint?

    @Query("SELECT * FROM trip_points WHERE localId = :localId LIMIT 1")
    suspend fun getPointByLocalId(localId: String): LocalTripPoint?

    @Query("SELECT COUNT(*) FROM trip_points")
    fun observeTotalPointsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_points")
    suspend fun countTotalPoints(): Int
}
