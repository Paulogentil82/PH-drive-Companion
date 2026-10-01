package com.phdrive.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class SyncPreferences(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveLastSyncAttemptAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_ATTEMPT, timestamp).commit()
    }

    fun getLastSyncAttemptAt(): Long? {
        val v = prefs.getLong(KEY_LAST_SYNC_ATTEMPT, -1L)
        return if (v > 0) v else null
    }

    fun saveLastSuccessfulSyncAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_SYNC, timestamp).commit()
    }

    fun getLastSuccessfulSyncAt(): Long? {
        val v = prefs.getLong(KEY_LAST_SUCCESSFUL_SYNC, -1L)
        return if (v > 0) v else null
    }

    fun saveLastSuccessfulBackupAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_BACKUP, timestamp).commit()
    }

    fun getLastSuccessfulBackupAt(): Long? {
        val v = prefs.getLong(KEY_LAST_SUCCESSFUL_BACKUP, -1L)
        return if (v > 0) v else null
    }

    fun saveLastSyncErrorCode(code: String?) {
        if (code.isNullOrBlank()) {
            prefs.edit().remove(KEY_LAST_SYNC_ERROR_CODE).commit()
        } else {
            prefs.edit().putString(KEY_LAST_SYNC_ERROR_CODE, code).commit()
        }
    }

    fun getLastSyncErrorCode(): String? {
        return prefs.getString(KEY_LAST_SYNC_ERROR_CODE, null)
    }

    fun observeLastSyncAttemptAt(): Flow<Long?> = callbackFlow {
        trySend(getLastSyncAttemptAt())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_SYNC_ATTEMPT) {
                trySend(getLastSyncAttemptAt())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun observeLastSuccessfulSyncAt(): Flow<Long?> = callbackFlow {
        trySend(getLastSuccessfulSyncAt())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_SUCCESSFUL_SYNC) {
                trySend(getLastSuccessfulSyncAt())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun observeLastSuccessfulBackupAt(): Flow<Long?> = callbackFlow {
        trySend(getLastSuccessfulBackupAt())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_SUCCESSFUL_BACKUP) {
                trySend(getLastSuccessfulBackupAt())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun observeLastSyncErrorCode(): Flow<String?> = callbackFlow {
        trySend(getLastSyncErrorCode())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_SYNC_ERROR_CODE) {
                trySend(getLastSyncErrorCode())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    companion object {
        private const val PREFS_NAME = "phdrive_sync_preferences"
        private const val KEY_LAST_SYNC_ATTEMPT = "key_last_sync_attempt"
        private const val KEY_LAST_SUCCESSFUL_SYNC = "key_last_successful_sync"
        private const val KEY_LAST_SUCCESSFUL_BACKUP = "key_last_successful_backup"
        private const val KEY_LAST_SYNC_ERROR_CODE = "key_last_sync_error_code"

        @Volatile
        private var INSTANCE: SyncPreferences? = null

        fun getInstance(context: Context): SyncPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
