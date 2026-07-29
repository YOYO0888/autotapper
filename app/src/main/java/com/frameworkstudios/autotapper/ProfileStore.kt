package com.frameworkstudios.autotapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists panel state and named profiles. v3: profile schema is
 *   { "name": "...", "steps": [...], "loopIntervalMs": N, "cycles": N }
 * where "steps" is a list of Step JSON blobs.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("autotapper", Context.MODE_PRIVATE)

    fun saveLast(state: JSONObject) {
        prefs.edit().putString(KEY_LAST, state.toString()).apply()
    }

    fun loadLast(): JSONObject? =
        prefs.getString(KEY_LAST, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun profileNames(): List<String> {
        val names = mutableListOf<String>()
        val it = profilesJson().keys()
        while (it.hasNext()) names.add(it.next())
        return names.sorted()
    }

    fun saveProfile(name: String, state: JSONObject) {
        val all = profilesJson()
        val stored = JSONObject(state.toString()).apply { put("name", name) }
        all.put(name, stored)
        prefs.edit().putString(KEY_PROFILES, all.toString()).apply()
    }

    fun loadProfile(name: String): JSONObject? = profilesJson().optJSONObject(name)

    fun deleteProfile(name: String) {
        val all = profilesJson()
        all.remove(name)
        prefs.edit().putString(KEY_PROFILES, all.toString()).apply()
    }

    private fun profilesJson(): JSONObject =
        prefs.getString(KEY_PROFILES, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()

    private companion object {
        const val KEY_LAST = "last_state_v3"
        const val KEY_PROFILES = "profiles_v3"
    }
}
