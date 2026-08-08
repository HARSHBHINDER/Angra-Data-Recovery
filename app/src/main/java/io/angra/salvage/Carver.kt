package io.angra.salvage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A file located in raw sectors, identified by its own bytes rather than by a directory
 * entry. [offset] is absolute from the start of the medium.
 */
data class Carved(
    val offset: Long,
    val length: Long,
    val ext: String,
    val name: String,
    val confident: Boolean
)

/** Header magic, extension, and how the end of the file is determined. */
private class Sig(
    val magic: ByteArray,
    val ext: String,
    val terminator: ByteArray? = null,
    /** Reads a declared length out of the header when the format carries one. */
    val declaredLength: ((ByteArray, Int) -> Long)? = null
)

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun beInt(buf: ByteArray, at: Int): Long =
    ByteBuffer.wrap(buf, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

private fun leInt(buf: ByteArray, at: Int): Long =
    ByteBuffer.wrap(buf, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

private fun leShort(buf: ByteArray, at: Int): Int =
    (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

/**
 * Signatures worth carving. Formats with a length field in the header are exact; the rest
 * are bounded by a terminator.
 */
private val SIGS = listOf(
    Sig(bytes(0xFF, 0xD8, 0xFF), "jpg", terminator = bytes(0xFF, 0xD9)),
    Sig(
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "png",
        terminator = "IEND".toByteArray()
    ),
    Sig("GIF89a".toByteArray(), "gif", terminator = bytes(0x00, 0x3B)),
    Sig("GIF87a".toByteArray(), "gif", terminator = bytes(0x00, 0x3B)),
    Sig("%PDF-".toByteArray(), "pdf", terminator = "%%EOF".toByteArray()),
    // ftyp box: the 4 bytes ahead of it are the box size, so the magic sits at +4.
    Sig("ftyp".toByteArray(), "mp4", declaredLength = { b, at -> beInt(b, at - 4) }),
    Sig("RIFF".toByteArray(), "riff", declaredLength = { b, at -> leInt(b, at + 4) + 8 }),
    Sig(bytes(0x50, 0x4B, 0x03, 0x04), "zip", terminator = bytes(0x50, 0x4B, 0x05, 0x06)),
    Sig("ID3".toByteArray(), "mp3"),
    Sig(bytes(0x1A, 0x45, 0xDF, 0xA3), "mkv"),
    Sig(bytes(0x7B, 0x5C, 0x72, 0x74), "rtf", terminator = bytes(0x7D))
)

/** Nothing carved may claim more than this — one bad length field would eat the whole card. */
private const val MAX_FILE = 512L * 1024 * 1024

/** Scan window. Large reads are the difference between minutes and hours on a real card. */
const val SCAN_CHUNK = 1 shl 20 // 1 MiB

/**
 * Sequential signature scan over a block device.
 *
 * Deleting a file on FAT32/exFAT clears its directory entry and marks its clusters free; it
 * does not touch the data, and removable cards are not TRIMmed. The file's own header is
 * therefore still sitting in its sector, which is what makes carving work here and not on
 * the phone's own encrypted, TRIMmed storage.
 *
 * [read] fills the supplied buffer from an absolute byte offset and returns bytes read.
 */
fun carve(
    totalBytes: Long,
    read: (offset: Long, dest: ByteArray) -> Int,
    onProgress: (scanned: Long, hits: Int) -> Unit,
    shouldStop: () -> Boolean = { false }
): List<Carved> {
    val out = mutableListOf<Carved>()
    val buf = ByteArray(SCAN_CHUNK)
    var pos = 0L
    var n = 0

    while (pos < totalBytes && !shouldStop()) {
        val got = read(pos, buf)
        if (got <= 0) break

        // Sector-aligned headers only: filesystems start files on a sector boundary, and
        // scanning every byte instead multiplies false positives without finding more files.
        var i = 0
        while (i + 32 < got) {
            for (sig in SIGS) {
                val at = if (sig.ext == "mp4") i + 4 else i
                if (at + sig.magic.size > got) continue
                if (!matches(buf, at, sig.magic)) continue

                val length = resolveLength(sig, buf, at, pos + i, totalBytes, read)
                if (length <= 0) continue

                n++
                out += Carved(
                    offset = pos + i,
                    length = length,
                    ext = sig.ext,
                    name = "carved_%08d.%s".format(n, sig.ext),
                    confident = sig.declaredLength != null || sig.terminator != null
                )
                break
            }
            i += 512
        }

        pos += got
        onProgress(pos, out.size)
    }
    return out
}

private fun matches(buf: ByteArray, at: Int, magic: ByteArray): Boolean {
    if (at < 0 || at + magic.size > buf.size) return false
    for (k in magic.indices) if (buf[at + k] != magic[k]) return false
    return true
}

/**
 * Exact length when the header declares one, otherwise a forward scan for the terminator.
 * Formats carrying neither (mp3 streams, mkv) take a bounded grab — playable, and flagged
 * unconfident rather than presented as exact.
 */
private fun resolveLength(
    sig: Sig,
    head: ByteArray,
    at: Int,
    absolute: Long,
    totalBytes: Long,
    read: (Long, ByteArray) -> Int
): Long {
    sig.declaredLength?.let { fn ->
        if (at < 4) return -1
        val declared = runCatching { fn(head, at) }.getOrDefault(0L)
        return if (declared in 1..MAX_FILE) declared else -1
    }

    val term = sig.terminator
        ?: return minOf(8L * 1024 * 1024, totalBytes - absolute).coerceAtLeast(0)

    val window = ByteArray(SCAN_CHUNK)
    var searched = 0L
    while (searched < MAX_FILE && absolute + searched < totalBytes) {
        val got = read(absolute + searched, window)
        if (got < term.size) break
        for (k in 0..got - term.size) {
            if (matches(window, k, term)) {
                // PNG's IEND carries a 4-byte CRC; ZIP's EOCD an 18-byte trailing record.
                val trailer = when (sig.ext) {
                    "png" -> 8
                    "zip" -> 18
                    else -> 0
                }
                return searched + k + term.size + trailer
            }
        }
        searched += got - term.size   // overlap so a terminator split across reads still hits
    }
    return -1
}

// ---------- FAT32 deleted directory entries ----------

/**
 * Deleted entries recovered from a FAT32 directory. Better than carving when available,
 * because the entry still holds the real filename and exact byte size — FAT overwrites only
 * the first character of the name with 0xE5 on delete.
 *
 * The cluster chain is freed on delete, so the run is assumed contiguous. That holds for most
 * camera and download files, and is why a carved video sometimes plays only partway: it was
 * fragmented.
 */
fun fatDeletedEntries(
    read: (offset: Long, dest: ByteArray) -> Int,
    onProgress: (Int) -> Unit
): List<Carved> {
    val boot = ByteArray(512)
    if (read(0, boot) < 512) return emptyList()

    val bytesPerSector = leShort(boot, 11)
    val sectorsPerCluster = boot[13].toInt() and 0xFF
    val reserved = leShort(boot, 14)
    val numFats = boot[16].toInt() and 0xFF
    val sectorsPerFat = leInt(boot, 36)
    val rootCluster = leInt(boot, 44)

    // Gate the geometry: a non-FAT32 medium yields nonsense here and must not be trusted.
    if (bytesPerSector !in intArrayOf(512, 1024, 2048, 4096) ||
        sectorsPerCluster == 0 || numFats == 0 || sectorsPerFat == 0L || rootCluster < 2
    ) return emptyList()

    val clusterBytes = bytesPerSector.toLong() * sectorsPerCluster
    val dataStart = (reserved + numFats * sectorsPerFat) * bytesPerSector
    fun clusterOffset(cluster: Long) = dataStart + (cluster - 2) * clusterBytes

    // ponytail: root directory only, no recursion into subdirectories. Covers DCIM and
    // Download, which is where the losses are. Recurse when someone needs deeper.
    val cluster = ByteArray(clusterBytes.coerceAtMost((1 shl 20).toLong()).toInt())
    if (read(clusterOffset(rootCluster), cluster) <= 0) return emptyList()

    val out = mutableListOf<Carved>()
    var i = 0
    while (i + 32 <= cluster.size) {
        val first = cluster[i].toInt() and 0xFF
        if (first == 0x00) break                        // directory ends here
        val attr = cluster[i + 11].toInt() and 0xFF
        if (first == 0xE5 && attr != 0x0F) {            // 0x0F is a long-name fragment
            val stem = String(cluster, i + 1, 7)
                .trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            val ext = String(cluster, i + 8, 3).trim().lowercase()
                .filter { it.isLetterOrDigit() }
            val size = leInt(cluster, i + 28)
            val start = (leShort(cluster, i + 20).toLong() shl 16) or
                leShort(cluster, i + 26).toLong()
            if (size in 1..MAX_FILE && start >= 2) {
                val suffix = ext.ifEmpty { "bin" }
                out += Carved(
                    offset = clusterOffset(start),
                    length = size,
                    ext = suffix,
                    name = "_$stem.$suffix",
                    confident = true
                )
                onProgress(out.size)
            }
        }
        i += 32
    }
    return out
}
