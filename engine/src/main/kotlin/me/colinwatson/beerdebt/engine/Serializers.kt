package me.colinwatson.beerdebt.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/** ISO 8601 UTC, whole seconds, the way the iOS app writes ledger.json. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(value.epochSecond)))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Case-insensitive on the way in; iOS writes uppercase, Java lowercase. Both sides accept either. */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
