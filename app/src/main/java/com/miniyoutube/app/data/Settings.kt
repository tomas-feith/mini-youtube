package com.miniyoutube.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The user's preferences. Kept out of the Room database: they are not library data, and
 * reading one must not wait on, or migrate with, the database.
 */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

class Settings(
    private val context: Context,
) {
    private companion object {
        val CHECK_ON_MOBILE_DATA = booleanPreferencesKey("check_on_mobile_data")
    }

    /**
     * Whether new videos are checked for unasked - hourly, and on opening the app - while
     * on mobile data. Off by default: a check reads every followed channel, and while the
     * feed is down a megabyte or more each. A pull to refresh checks regardless.
     */
    val checkOnMobileData: Flow<Boolean> =
        context.settingsStore.data.map { it[CHECK_ON_MOBILE_DATA] ?: false }

    /** A one-shot read, for callers that have no reason to observe. */
    suspend fun currentCheckOnMobileData(): Boolean = checkOnMobileData.first()

    suspend fun setCheckOnMobileData(allowed: Boolean) {
        context.settingsStore.edit { it[CHECK_ON_MOBILE_DATA] = allowed }
    }
}
