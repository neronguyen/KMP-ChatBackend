package io.github.neronguyenvn.nerochat.domain.event

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@Serializable
sealed class Event {
    abstract val key: String
    abstract val exchangeName: String
    open val id: String = Uuid.generateV7().toString()
    open val occurredAt: Instant = Clock.System.now()
}
