package io.angra.salvage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The carver and the FAT parser are the only logic here that cannot be checked by running the
 * UI — a wrong offset silently produces a corrupt "recovered" file rather than an error. These
 * build a synthetic medium in memory and assert the parsers land on the right bytes.
 */
class CarverTest {

    /** Serves a ByteArray as if it were a block device. */
    private fun reader(medium: ByteArray): (Long, ByteArray) -> Int = { offset, dest ->
        if (offset >= medium.size) -1
        else {
            val n = minOf(dest.size.toLong(), medium.size - offset).toInt()
            System.arraycopy(medium, offset.toInt(), dest, 0, n)
            n
        }
    }

    @Test
    fun `carves a jpeg and stops at its end marker`() {
        val medium = ByteArray(3 * 1024 * 1024)
        val start = 512 * 4
        medium[start] = 0xFF.toByte()
        medium[start + 1] = 0xD8.toByte()
        medium[start + 2] = 0xFF.toByte()
        val end = start + 5000
        medium[end] = 0xFF.toByte()
        medium[end + 1] = 0xD9.toByte()

        val jpg = carve(medium.size.toLong(), reader(medium), { _, _ -> })
            .filter { it.ext == "jpg" }

        assertEquals(1, jpg.size)
        assertEquals(start.toLong(), jpg[0].offset)
        assertEquals(5002L, jpg[0].length)   // through the FF D9 terminator
        assertTrue(jpg[0].confident)
    }

    @Test
    fun `carves an mp4 using the declared box size`() {
        val medium = ByteArray(1 shl 20)
        val start = 512 * 2
        // Box size big-endian at +0, then "ftyp" at +4.
        ByteBuffer.wrap(medium, start, 4).order(ByteOrder.BIG_ENDIAN).putInt(40_000)
        "ftyp".toByteArray().copyInto(medium, start + 4)

        val mp4 = carve(medium.size.toLong(), reader(medium), { _, _ -> })
            .single { it.ext == "mp4" }

        assertEquals(start.toLong(), mp4.offset)
        assertEquals(40_000L, mp4.length)
    }

    @Test
    fun `ignores a header that is not sector aligned`() {
        val medium = ByteArray(1 shl 20)
        val start = 513          // deliberately off-boundary
        medium[start] = 0xFF.toByte()
        medium[start + 1] = 0xD8.toByte()
        medium[start + 2] = 0xFF.toByte()
        medium[start + 900] = 0xFF.toByte()
        medium[start + 901] = 0xD9.toByte()

        assertTrue(carve(medium.size.toLong(), reader(medium), { _, _ -> }).isEmpty())
    }

    @Test
    fun `recovers a deleted FAT32 entry with its real size and offset`() {
        val medium = ByteArray(1 shl 20)

        fun putShort(at: Int, v: Int) {
            medium[at] = (v and 0xFF).toByte()
            medium[at + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun putInt(at: Int, v: Int) {
            ByteBuffer.wrap(medium, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        }

        // 512 B sectors, 1 sector per cluster, 1 reserved, one 1-sector FAT, root at cluster 2.
        // Data therefore starts at (1 + 1) * 512 = 1024.
        putShort(11, 512)
        medium[13] = 1
        putShort(14, 1)
        medium[16] = 1
        putInt(36, 1)
        putInt(44, 2)

        // Deleted 8.3 entry at the start of the root cluster.
        val e = 1024
        medium[e] = 0xE5.toByte()                    // marker replaces the 'P' of PHOTO
        "HOTO   ".toByteArray().copyInto(medium, e + 1)
        "JPG".toByteArray().copyInto(medium, e + 8)
        medium[e + 11] = 0x20                        // ATTR_ARCHIVE, not a long-name fragment
        putShort(e + 20, 0)                          // cluster high
        putShort(e + 26, 3)                          // cluster low
        putInt(e + 28, 1234)                         // exact file size

        val found = fatDeletedEntries(reader(medium)) { }

        assertEquals(1, found.size)
        assertEquals(1234L, found[0].length)
        assertEquals(1536L, found[0].offset)         // cluster 3 = 1024 + 1 * 512
        // The first character is genuinely destroyed by the delete marker — not a bug.
        assertEquals("_HOTO.jpg", found[0].name)
    }

    @Test
    fun `rejects a medium that is not FAT32`() {
        val junk = ByteArray(1 shl 16) { (it * 31).toByte() }
        assertTrue(fatDeletedEntries(reader(junk)) { }.isEmpty())
    }

    /**
     * Carved names come from raw disk bytes and then become real filenames, so path traversal
     * and control characters must not survive. The name is attacker-controlled if the card is.
     */
    @Test
    fun `sanitizes filenames taken from disk bytes`() {
        assertEquals(".._.._etc_passwd", safeName("../../etc/passwd"))
        assertEquals("evil_.sh", safeName("  evil|.sh  "))
        assertEquals("recovered", safeName(""))
        assertEquals("recovered", safeName("..."))
        assertTrue(safeName("x".repeat(400)).length <= 120)
    }
}
