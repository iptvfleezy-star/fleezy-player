package com.ultratv.tv.nativeapp.data.prefs

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.myGroupsDs by preferencesDataStore(name = "my_groups")

/**
 * A customer-created Live TV group. These sit on top of the provider/IPTVBoss
 * category layout and never modify the provider catalogue itself.
 */
data class MyGroup(
    val id: String,
    val providerId: Long,
    val name: String,
    val position: Int,
)

data class MyGroupMember(
    val groupId: String,
    val providerId: Long,
    val remoteId: String,
)

/**
 * User-owned Live TV grouping lives in DataStore rather than the catalogue
 * database so a provider refresh cannot erase it. Membership is keyed by
 * provider + remote channel id, which remains stable across normal Xtream
 * catalogue syncs.
 */
@Singleton
class MyGroupsStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val GROUPS = stringSetPreferencesKey("groups_v1")
    private val MEMBERS = stringSetPreferencesKey("members_v1")

    val groups: Flow<List<MyGroup>> = ctx.myGroupsDs.data.map { prefs ->
        decodeGroups(prefs[GROUPS].orEmpty())
            .sortedWith(
                compareBy<MyGroup> { it.providerId }
                    .thenBy { it.position }
                    .thenBy { it.name.lowercase() },
            )
    }

    val members: Flow<Set<MyGroupMember>> = ctx.myGroupsDs.data.map { prefs ->
        prefs[MEMBERS].orEmpty().mapNotNull(::decodeMember).toSet()
    }

    suspend fun create(providerId: Long, name: String): MyGroup? {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return null

        val group = MyGroup(
            id = UUID.randomUUID().toString(),
            providerId = providerId,
            name = cleanName,
            position = 0,
        )
        var created = group
        ctx.myGroupsDs.edit { prefs ->
            val current = prefs[GROUPS].orEmpty().toMutableSet()
            val nextPosition = decodeGroups(current)
                .filter { it.providerId == providerId }
                .maxOfOrNull { it.position }
                ?.plus(1)
                ?: 0
            created = group.copy(position = nextPosition)
            current.add(encodeGroup(created))
            prefs[GROUPS] = current
        }
        return created
    }

    suspend fun rename(groupId: String, name: String) {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return
        ctx.myGroupsDs.edit { prefs ->
            val current = prefs[GROUPS].orEmpty()
            val groups = decodeGroups(current)
            val target = groups.firstOrNull { it.id == groupId } ?: return@edit
            prefs[GROUPS] = groups
                .filterNot { it.id == groupId }
                .map(::encodeGroup)
                .toSet() + encodeGroup(target.copy(name = cleanName))
        }
    }

    suspend fun delete(groupId: String) {
        ctx.myGroupsDs.edit { prefs ->
            prefs[GROUPS] = decodeGroups(prefs[GROUPS].orEmpty())
                .filterNot { it.id == groupId }
                .map(::encodeGroup)
                .toSet()
            prefs[MEMBERS] = prefs[MEMBERS].orEmpty()
                .filterTo(mutableSetOf()) { raw ->
                    decodeMember(raw)?.groupId != groupId
                }
        }
    }

    suspend fun setMembership(
        groupId: String,
        providerId: Long,
        remoteId: String,
        member: Boolean,
    ) {
        ctx.myGroupsDs.edit { prefs ->
            val current = prefs[MEMBERS].orEmpty().toMutableSet()
            val encoded = encodeMember(MyGroupMember(groupId, providerId, remoteId))
            if (member) current.add(encoded) else current.remove(encoded)
            prefs[MEMBERS] = current
        }
    }

    private fun encodeGroup(group: MyGroup): String =
        group.providerId.toString() + "|" +
            group.position.toString() + "|" +
            group.id + "|" +
            encodeText(group.name)

    private fun decodeGroups(raw: Set<String>): List<MyGroup> =
        raw.mapNotNull { record ->
            val p = record.split('|', ignoreCase = false, limit = 4)
            if (p.size != 4) return@mapNotNull null
            val providerId = p[0].toLongOrNull() ?: return@mapNotNull null
            val position = p[1].toIntOrNull() ?: return@mapNotNull null
            val name = decodeText(p[3]) ?: return@mapNotNull null
            MyGroup(id = p[2], providerId = providerId, name = name, position = position)
        }

    private fun encodeMember(member: MyGroupMember): String =
        member.providerId.toString() + "|" +
            member.groupId + "|" +
            encodeText(member.remoteId)

    private fun decodeMember(raw: String): MyGroupMember? {
        val p = raw.split('|', ignoreCase = false, limit = 3)
        if (p.size != 3) return null
        val providerId = p[0].toLongOrNull() ?: return null
        val remoteId = decodeText(p[2]) ?: return null
        return MyGroupMember(groupId = p[1], providerId = providerId, remoteId = remoteId)
    }

    private fun encodeText(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )

    private fun decodeText(value: String): String? =
        runCatching {
            String(
                Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE),
                Charsets.UTF_8,
            )
        }.getOrNull()
}
