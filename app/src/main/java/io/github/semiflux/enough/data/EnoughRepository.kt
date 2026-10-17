package io.github.semiflux.enough.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.limitDataStore: DataStore<Preferences> by preferencesDataStore(name = "enough_rules")

/** Jetpack DataStore repository for the blacklist rules. */
class EnoughRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val rulesKey = stringPreferencesKey("rules_json_v1")

    val rules: Flow<List<AppRule>> =
        context.limitDataStore.data.map { prefs ->
            val raw = prefs[rulesKey] ?: return@map emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(AppRule.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun snapshot(): List<AppRule> = rules.first()

    suspend fun upsert(rule: AppRule) {
        context.limitDataStore.edit { prefs ->
            val current = readLocked(prefs[rulesKey]).toMutableList()
            val index = current.indexOfFirst { it.packageName == rule.packageName }
            if (index >= 0) current[index] = rule else current.add(rule)
            prefs[rulesKey] = json.encodeToString(ListSerializer(AppRule.serializer()), current)
        }
    }

    suspend fun remove(packageName: String) {
        context.limitDataStore.edit { prefs ->
            val current = readLocked(prefs[rulesKey]).filterNot { it.packageName == packageName }
            prefs[rulesKey] = json.encodeToString(ListSerializer(AppRule.serializer()), current)
        }
    }

    suspend fun update(packageName: String, transform: (AppRule) -> AppRule) {
        // Read-modify-write inside a single edit{} so concurrent updates
        // (e.g. two wheel columns settling back-to-back) can't fork the
        // rule from a stale snapshot and lose each other's changes.
        context.limitDataStore.edit { prefs ->
            val current = readLocked(prefs[rulesKey]).toMutableList()
            val index = current.indexOfFirst { it.packageName == packageName }
            if (index < 0) return@edit
            current[index] = transform(current[index])
            prefs[rulesKey] = json.encodeToString(ListSerializer(AppRule.serializer()), current)
        }
    }

    private fun readLocked(raw: String?): List<AppRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(AppRule.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}
