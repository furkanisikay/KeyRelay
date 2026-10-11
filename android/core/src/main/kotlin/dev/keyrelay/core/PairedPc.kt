package dev.keyrelay.core

import java.util.Base64

// java.util.HexFormat needs API 34; minSdk is 30.
private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun fromHex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd hex length" }
    return text.chunked(2).map { it.toIntOrNull(16)?.toByte() ?: throw IllegalArgumentException("bad hex: $it") }.toByteArray()
}

/** A PC this tablet is paired with; [sealedToken] is the device token encrypted by the app (Android Keystore). */
class PairedPc(
    val deviceId: ByteArray,
    val name: String,
    val endpoints: List<Endpoint>,
    val spkiSha256: ByteArray,
    val sealedToken: ByteArray,
    val lastSeenMs: Long,
    val isDefault: Boolean,
) {
    val id: String get() = toHex(deviceId)

    fun copy(endpoints: List<Endpoint> = this.endpoints, lastSeenMs: Long = this.lastSeenMs, isDefault: Boolean = this.isDefault) =
        PairedPc(deviceId, name, endpoints, spkiSha256, sealedToken, lastSeenMs, isDefault)
}

/**
 * A new default PC clears the flag on the others, so exactly one default remains. An entry with the same PC key is
 * replaced too: pairing the same PC again gives a new device id and the old one is dead.
 */
fun List<PairedPc>.upsert(pc: PairedPc): List<PairedPc> =
    filter { it.id != pc.id && !it.spkiSha256.contentEquals(pc.spkiSha256) }.map { if (pc.isDefault) it.copy(isDefault = false) else it } + pc

fun List<PairedPc>.without(id: String): List<PairedPc> {
    val rest = filter { it.id != id }
    return if (rest.isEmpty() || rest.any { it.isDefault }) rest else listOf(rest.first().copy(isDefault = true)) + rest.drop(1)
}

fun List<PairedPc>.makeDefault(id: String): List<PairedPc> = map { it.copy(isDefault = it.id == id) }

/** Moves the address that just worked to the front (protocol.md: the last successful address is tried first). */
fun List<PairedPc>.promote(id: String, endpoint: Endpoint): List<PairedPc> = map { pc ->
    if (pc.id != id || endpoint !in pc.endpoints) pc else pc.copy(endpoints = listOf(endpoint) + pc.endpoints.filter { it != endpoint })
}

fun List<PairedPc>.touch(id: String, nowMs: Long): List<PairedPc> = map { if (it.id == id) it.copy(lastSeenMs = nowMs) else it }

val List<PairedPc>.defaultPc: PairedPc? get() = firstOrNull { it.isDefault } ?: firstOrNull()

/** One line per PC, fields separated by spaces; the name is base64url so a line never contains a space. */
object PairedPcCodec {
    private const val VERSION = "v1"
    private const val FIELDS = 8
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(pcs: List<PairedPc>): String = pcs.joinToString("\n") { pc ->
        listOf(
            VERSION, pc.id, encoder.encodeToString(pc.name.toByteArray()), pc.endpoints.joinToString(","),
            encoder.encodeToString(pc.spkiSha256), encoder.encodeToString(pc.sealedToken), pc.lastSeenMs.toString(),
            if (pc.isDefault) "1" else "0",
        ).joinToString(" ")
    }

    /** Skips lines it cannot read: one damaged entry must not hide the other PCs. */
    fun decode(text: String): List<PairedPc> = text.lineSequence().mapNotNull { runCatching { decodeLine(it) }.getOrNull() }.toList()

    private fun decodeLine(line: String): PairedPc {
        val f = line.split(' ')
        require(f.size == FIELDS && f[0] == VERSION) { "unknown line" }
        return PairedPc(
            fromHex(f[1]), String(decoder.decode(f[2])), f[3].split(',').map { Endpoint.parse(it, 0) },
            decoder.decode(f[4]), decoder.decode(f[5]), f[6].toLong(), f[7] == "1",
        )
    }
}
