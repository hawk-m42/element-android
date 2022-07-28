/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.create

import io.mockk.every
import io.mockk.unmockkAll
import io.realm.RealmResults
import io.realm.kotlin.where
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntity
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntityFields
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventEntityFields
import org.matrix.android.sdk.internal.util.time.DefaultClock
import org.matrix.android.sdk.test.fakes.FakeMonarchy
import org.matrix.android.sdk.test.fakes.FakeRealmResults
import java.util.UUID

private const val A_LOCAL_ROOM_ID = "a-local-room-id"
private const val MY_USER_ID = "my-user-id"
private const val AN_EVENT_ID = "event-id"

private const val A_DISPLAY_NAME = "display-name"
private const val AN_AVATAR_URL = "avatar-url"

@ExperimentalCoroutinesApi
internal class DefaultGetCreateRoomParamsFromLocalRoomTaskTest {

    private val fakeMonarchy = FakeMonarchy()
    private val clock = DefaultClock()

    private val defaultGetCreateRoomFromLocalRoomTask = DefaultGetCreateRoomParamsFromLocalRoomTask(fakeMonarchy.instance)

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `given a local room id when calling the task then the resulting CreateRoomParams contains the correct invited members list`() = runTest {
        // Given
        val stateEventEntities = listOf(
                givenARoomMemberStateEvent(MY_USER_ID, Membership.JOIN),
                givenARoomMemberStateEvent("bob", Membership.INVITE),
                givenARoomMemberStateEvent("alice", Membership.INVITE)
        )

        val realmResults = FakeRealmResults(stateEventEntities)
        every {
            fakeMonarchy.fakeRealm.instance
                    .where<CurrentStateEventEntity>()
                    .equalTo(CurrentStateEventEntityFields.ROOM_ID, A_LOCAL_ROOM_ID)
                    .findAll()
        } returns realmResults.instance

        // When
        val params = GetCreateRoomParamsFromLocalRoomTask.Params(A_LOCAL_ROOM_ID)
        val result = defaultGetCreateRoomFromLocalRoomTask.execute(params)

        // Then
        result.invitedUserIds shouldBeEqualTo stateEventEntities.map { it.stateKey }
    }

    private fun givenARoomMemberStateEvent(userId: String, membership: Membership): CurrentStateEventEntity {
        // Create the room member event
        val eventEntity = Event(
                type = EventType.STATE_ROOM_MEMBER,
                senderId = MY_USER_ID,
                stateKey = userId,
                eventId = UUID.randomUUID().toString(),
                originServerTs = clock.epochMillis(),
                content = RoomMemberContent(
                        membership = membership,
                        displayName = "${userId}_$A_DISPLAY_NAME",
                        avatarUrl = "${userId}_$AN_AVATAR_URL"
                ).toContent()
        ).toEntity(A_LOCAL_ROOM_ID, SendState.SYNCED, clock.epochMillis())

        // Mock it in DB
        every {
            fakeMonarchy.fakeRealm.instance
                    .where<EventEntity>()
                    .equalTo(EventEntityFields.EVENT_ID, eventEntity.eventId)
                    .findFirst()
        } returns eventEntity

        // Return the resulting state event
        return CurrentStateEventEntity(
                eventId = eventEntity.eventId,
                root = eventEntity,
                roomId = eventEntity.roomId,
                type = eventEntity.type,
                stateKey = eventEntity.stateKey!!
        )
    }
}

