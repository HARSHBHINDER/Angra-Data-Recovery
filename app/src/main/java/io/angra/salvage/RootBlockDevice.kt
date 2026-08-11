package io.angra.salvage

import java.io.DataInputStream

/**
 * Raw read access to the phone's own internal partitions, via root.
 *
 * This is the approach DiskDigger's rooted "full scan" uses: bypass the filesystem and read
 * the block device directly, so files with no remaining directory entry are still visible as
 * bytes. Without root there is no path to this — Android's file APIs only return files that
 * still have a directory entry, which is why the unrooted scans in this app can reach no
 * further than trash and thumbnail caches.
 *
 * Two things cap the yield on a modern device, and the app says so rather than pretending:
 * file-based encryption gives each file its own key and discards that key on delete, and f2fs
 * issues TRIM, which zeroes the flash blocks. Carving then returns ciphertext or zeroes.
 * Older devices, full-disk-encrypted devices, and unencrypted partitions fare far better.
 */
class RootBlockDevice private constructor(
    val path: String,
    val sizeBytes: Long
) {

    companion object {
        /** True when a root shell answers. Triggers the usual su prompt on first call. */
        fun rootAvailable(): Boolean = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)

        /**
         * Partitions worth carving, largest first. userdata backs both app storage and the
         * internal "sdcard", so it is where deleted downloads and camera files lived.
         */
        fun candidates(): List<RootBlockDevice> = runCatching {
            val script = """
                for n in userdata data media sdcard; do
                  for p in /dev/block/by-name/${'$'}n /dev/block/mapper/${'$'}n; do
                    if [ -e "${'$'}p" ]; then
                      s=`blockdev --getsize64 "${'$'}p" 2>/dev/null`
                      echo "${'$'}p ${'$'}s"
                    fi
                  done
                done
            """.trimIndent()

            val p = ProcessBuilder("su", "-c", script).start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()

            lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val size = parts[1].toLongOrNull() ?: 0L
                if (size <= 0) null else RootBlockDevice(parts[0], size)
            }.distinctBy { it.path }.sortedByDescending { it.sizeBytes }
        }.getOrDefault(emptyList())
    }

    /**
     * Reads [dest].size bytes from an absolute offset by piping dd through a root shell.
     * dd is addressed in 512-byte sectors, so any offset the carver asks for is reachable.
     */
    fun readAt(offset: Long, dest: ByteArray): Int {
        if (offset < 0 || offset >= sizeBytes) return -1
        val sector = 512
        val skip = offset / sector
        val skew = (offset % sector).toInt()
        val wanted = minOf(dest.size.toLong(), sizeBytes - offset).toInt()
        val count = (skew + wanted + sector - 1) / sector

        return runCatching {
            val cmd = "dd if=$path bs=$sector skip=$skip count=$count 2>/dev/null"
            val proc = ProcessBuilder("su", "-c", cmd).start()
            val scratch = ByteArray(count * sector)
            var filled = 0
            DataInputStream(proc.inputStream).use { input ->
                while (filled < scratch.size) {
                    val n = input.read(scratch, filled, scratch.size - filled)
                    if (n <= 0) break
                    filled += n
                }
            }
            proc.waitFor()
            if (filled <= skew) return@runCatching -1
            val n = minOf(wanted, filled - skew)
            System.arraycopy(scratch, skew, dest, 0, n)
            n
        }.getOrDefault(-1)
    }
}
