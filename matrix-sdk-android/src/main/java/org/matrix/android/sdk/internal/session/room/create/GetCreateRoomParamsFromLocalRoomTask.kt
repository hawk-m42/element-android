/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.create

import android.util.Patterns
import androidx.core.net.toUri
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.zhuinden.monarchy.Monarchy
import io.realm.Realm
import org.matrix.android.sdk.api.extensions.ensurePrefix
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomAliasesContent
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomGuestAccessContent
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibilityContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomPreset
import org.matrix.android.sdk.api.session.room.model.localecho.LocalRoomThirdPartyInviteContent
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.database.query.whereRoomId
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.awaitTransaction
import javax.inject.Inject

/**
 * Gets the [CreateRoomParams] related to the given local room id by parsing the state events of the room.
 * These params can be used to create a room using the same configuration as the local room (name, topic, invited members...).
 */
internal interface GetCreateRoomParamsFromLocalRoomTask : Task<GetCreateRoomParamsFromLocalRoomTask.Params, CreateRoomParams> {
    data class Params(val localRoomId: String)
}

internal class DefaultGetCreateRoomParamsFromLocalRoomTask @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy
) : GetCreateRoomParamsFromLocalRoomTask {

    override suspend fun execute(params: GetCreateRoomParamsFromLocalRoomTask.Params): CreateRoomParams {
        var createRoomParams = CreateRoomParams()
        monarchy.awaitTransaction { realm ->
            val stateEvents = CurrentStateEventEntity.whereRoomId(realm, params.localRoomId).findAll()
            stateEvents.forEach { event ->
                createRoomParams = when (event.type) {
                    EventType.STATE_ROOM_MEMBER -> handleRoomMemberEvent(realm, event, createRoomParams)
                    EventType.LOCAL_STATE_ROOM_THIRD_PARTY_INVITE -> handleLocalRoomThirdPartyInviteEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_HISTORY_VISIBILITY -> handleRoomHistoryVisibilityEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_ALIASES -> handleRoomAliasesEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_AVATAR -> handleRoomAvatarEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_CANONICAL_ALIAS -> handleRoomCanonicalAliasEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_GUEST_ACCESS -> handleRoomGuestAccessEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_ENCRYPTION -> handleRoomEncryptionEvent(createRoomParams)
                    EventType.STATE_ROOM_POWER_LEVELS -> handleRoomPowerRoomLevelsEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_NAME -> handleRoomNameEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_TOPIC -> handleRoomTopicEvent(realm, event, createRoomParams)
                    EventType.STATE_ROOM_THIRD_PARTY_INVITE -> handleRoomThirdPartyInviteEvent(event, createRoomParams)
                    EventType.STATE_ROOM_JOIN_RULES -> handleRoomJoinRulesEvent(realm, event, createRoomParams)
                    else -> createRoomParams
                }
            }
        }
        return createRoomParams
    }

    /* ==========================================================================================
     * Local events handling
     * ========================================================================================== */

    private fun handleRoomMemberEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomMemberContent>(realm, event.eventId) ?: return@apply
        invitedUserIds.add(event.stateKey)
        if (content.isDirect) {
            setDirectMessage()
        }
    }

    private fun handleLocalRoomThirdPartyInviteEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<LocalRoomThirdPartyInviteContent>(realm, event.eventId) ?: return@apply
        val threePid = when {
            content.thirdPartyInvite?.email != null -> ThreePid.Email(content.thirdPartyInvite.email)
            content.thirdPartyInvite?.msisdn != null -> ThreePid.Msisdn(content.thirdPartyInvite.msisdn)
            else -> return@apply
        }
        invite3pids.add(threePid)
        if (content.isDirect) {
            setDirectMessage()
        }
    }

    private fun handleRoomHistoryVisibilityEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomHistoryVisibilityContent>(realm, event.eventId) ?: return@apply
        historyVisibility = content.historyVisibility
    }

    private fun handleRoomAliasesEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomAliasesContent>(realm, event.eventId) ?: return@apply
        roomAliasName = content.aliases.firstOrNull()?.substringAfter("#")?.substringBefore(":")
    }

    private fun handleRoomAvatarEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomAvatarContent>(realm, event.eventId) ?: return@apply
        avatarUri = content.avatarUrl?.toUri()
    }

    private fun handleRoomCanonicalAliasEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomCanonicalAliasContent>(realm, event.eventId) ?: return@apply
        roomAliasName = content.canonicalAlias?.substringAfter("#")?.substringBefore(":")
    }

    private fun handleRoomGuestAccessEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomGuestAccessContent>(realm, event.eventId) ?: return@apply
        guestAccess = content.guestAccess
    }

    private fun handleRoomEncryptionEvent(params: CreateRoomParams): CreateRoomParams = params.apply {
        // Having an encryption event means the room is encrypted, so just enable it again
        enableEncryption()
    }

    private fun handleRoomPowerRoomLevelsEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<PowerLevelsContent>(realm, event.eventId) ?: return@apply
        powerLevelContentOverride = content
    }

    private fun handleRoomNameEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomNameContent>(realm, event.eventId) ?: return@apply
        name = content.name
    }

    private fun handleRoomTopicEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomTopicContent>(realm, event.eventId) ?: return@apply
        topic = content.topic
    }

    private fun handleRoomThirdPartyInviteEvent(event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        when {
            event.stateKey.isEmail() -> invite3pids.add(ThreePid.Email(event.stateKey))
            event.stateKey.isMsisdn() -> invite3pids.add(ThreePid.Msisdn(event.stateKey))
        }
    }

    private fun handleRoomJoinRulesEvent(realm: Realm, event: CurrentStateEventEntity, params: CreateRoomParams): CreateRoomParams = params.apply {
        val content = getEventContent<RoomJoinRulesContent>(realm, event.eventId) ?: return@apply
        preset = when {
            // If preset has already been set for direct chat, keep it
            preset == CreateRoomPreset.PRESET_TRUSTED_PRIVATE_CHAT -> CreateRoomPreset.PRESET_TRUSTED_PRIVATE_CHAT
            content.joinRules == RoomJoinRules.PUBLIC -> CreateRoomPreset.PRESET_PUBLIC_CHAT
            content.joinRules == RoomJoinRules.INVITE -> CreateRoomPreset.PRESET_PRIVATE_CHAT
            else -> null
        }
    }

    /* ==========================================================================================
     * Helper methods
     * ========================================================================================== */

    private inline fun <reified T> getEventContent(realm: Realm, eventId: String): T? {
        return EventEntity.where(realm, eventId).findFirst()?.asDomain()?.getClearContent().toModel<T>()
    }

    /**
     * Check if a CharSequence is an email.
     */
    private fun CharSequence.isEmail() = Patterns.EMAIL_ADDRESS.matcher(this).matches()

    /**
     * Check if a CharSequence is a phone number.
     */
    private fun CharSequence.isMsisdn(): Boolean {
        return try {
            PhoneNumberUtil.getInstance().parse(ensurePrefix("+"), null)
            true
        } catch (e: NumberParseException) {
            false
        }
    }
}
