package com.medlenx.lab.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.avatarDataStore by preferencesDataStore(name = "officer_avatars")

/** The IDs are the entire sync payload; no photo leaves the device. */
object AvatarStore {
    val ids = listOf("avatar_01", "avatar_02", "avatar_03", "avatar_04", "avatar_05", "avatar_06", "avatar_07", "avatar_08")
    fun valid(id: String?): Boolean = id != null && id in ids
    private fun idKey(uid: String) = stringPreferencesKey("id_" + UserCaptureFiles.key(uid))
    private fun dirtyKey(uid: String) = stringPreferencesKey("dirty_" + UserCaptureFiles.key(uid))
    fun observe(context: Context, uid: String) = context.applicationContext.avatarDataStore.data.map { it[idKey(uid)]?.takeIf(::valid) }
    fun observePending(context: Context, uid: String) = context.applicationContext.avatarDataStore.data.map { it[dirtyKey(uid)] != null }
    suspend fun snapshot(context: Context, uid: String): Pair<String?, String?> {
        val data = context.applicationContext.avatarDataStore.data.first()
        return data[idKey(uid)]?.takeIf(::valid) to data[dirtyKey(uid)]
    }
    suspend fun select(context: Context, uid: String, id: String) {
        require(valid(id))
        context.applicationContext.avatarDataStore.edit {
            it[idKey(uid)] = id
            it[dirtyKey(uid)] = UUID.randomUUID().toString()
        }
    }
    suspend fun restoreIfClean(context: Context, uid: String, id: String) {
        require(valid(id))
        context.applicationContext.avatarDataStore.edit { if (it[dirtyKey(uid)] == null) it[idKey(uid)] = id }
    }
    suspend fun acknowledge(context: Context, uid: String, token: String?) {
        if (token == null) return
        context.applicationContext.avatarDataStore.edit { if (it[dirtyKey(uid)] == token) it.remove(dirtyKey(uid)) }
    }
}
