package io.angra.salvage

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TIMEOUT = 20_000

/**
 * Raw sector access to a USB mass-storage device (stick, or SD card in a reader) over OTG.
 *
 * This is the only path on a non-rooted phone to bytes the filesystem has already forgotten.
 * Android's file APIs — File, MediaStore, SAF — can only hand back files that still have a
 * directory entry, so a permanently deleted file is invisible to all of them. Talking SCSI
 * over the USB Host API bypasses the filesystem entirely and reads the medium as numbers.
 *
 * Protocol is USB Bulk-Only Transport: a 31-byte Command Block Wrapper out, a data phase,
 * then a 13-byte Command Status Wrapper back.
 */
class UsbBlockDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : AutoCloseable {

    var blockSize: Int = 512
        private set

    /** Total addressable blocks. blockCount * blockSize = capacity. */
    var blockCount: Long = 0
        private set

    private var tag = 1

    companion object {
        const val ACTION_PERMISSION = "io.angra.salvage.USB_PERMISSION"

        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
        private const val SUBCLASS_SCSI = 0x06
        private const val PROTOCOL_BOT = 0x50

        /** Mass-storage devices speaking SCSI over Bulk-Only Transport. */
        fun candidates(usb: UsbManager): List<UsbDevice> =
            usb.deviceList.values.filter { dev ->
                (0 until dev.interfaceCount).any { i -> isMassStorage(dev.getInterface(i)) }
            }

        private fun isMassStorage(f: UsbInterface) =
            f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                f.interfaceSubclass == SUBCLASS_SCSI &&
                f.interfaceProtocol == PROTOCOL_BOT

        fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice) {
            val intent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(device, intent)
        }

        /** Claims the interface and reads capacity. Null when the device won't talk to us. */
        fun open(usb: UsbManager, device: UsbDevice): UsbBlockDevice? {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { isMassStorage(it) } ?: return null

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) return null

            val conn = usb.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return null
            }

            val dev = UsbBlockDevice(conn, iface, inEp, outEp)
            return if (runCatching { dev.readCapacity() }.isSuccess) dev
            else {
                dev.close()
                null
            }
        }
    }

    /** SCSI READ CAPACITY (10): last logical block address, then bytes per block. */
    private fun readCapacity() {
        val cmd = ByteArray(10).also { it[0] = 0x25 }
        val data = ByteArray(8)
        if (!transfer(cmd, data, dataIn = true)) throw IOException("READ CAPACITY failed")
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.int.toLong() and 0xFFFFFFFFL
        blockSize = buf.int
        if (blockSize <= 0 || blockSize > 8192) blockSize = 512
        blockCount = lastLba + 1
    }

    /**
     * SCSI READ (10). [dest] size must be a multiple of blockSize; reading large multi-block
     * chunks is what keeps a full-card scan to minutes rather than hours.
     */
    fun readBlocks(lba: Long, dest: ByteArray): Boolean {
        val blocks = dest.size / blockSize
        if (blocks !in 1..65535) return false
        val cmd = ByteArray(10)
        cmd[0] = 0x28
        cmd[2] = (lba ushr 24).toByte()
        cmd[3] = (lba ushr 16).toByte()
        cmd[4] = (lba ushr 8).toByte()
        cmd[5] = lba.toByte()
        cmd[7] = (blocks ushr 8).toByte()
        cmd[8] = blocks.toByte()
        return transfer(cmd, dest, dataIn = true)
    }

    /**
     * Read from an arbitrary byte offset. SCSI only addresses whole blocks, so this reads the
     * enclosing block range and shifts the result — the carver works in byte offsets and would
     * otherwise have to know the geometry.
     */
    fun readAt(offset: Long, dest: ByteArray): Int {
        if (offset < 0) return -1
        val total = blockCount * blockSize
        if (offset >= total) return -1

        val firstLba = offset / blockSize
        val skew = (offset % blockSize).toInt()
        val wanted = minOf(dest.size.toLong(), total - offset).toInt()
        val blocks = ((skew + wanted + blockSize - 1) / blockSize).coerceAtLeast(1)

        val scratch = ByteArray(blocks * blockSize)
        if (!readBlocks(firstLba, scratch)) return -1
        val n = minOf(wanted, scratch.size - skew)
        System.arraycopy(scratch, skew, dest, 0, n)
        return n
    }

    /** One transport round trip: CBW out, data phase, CSW in. True when SCSI status is good. */
    private fun transfer(command: ByteArray, data: ByteArray, dataIn: Boolean): Boolean {
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(tag++)
            putInt(data.size)
            put(if (dataIn) 0x80.toByte() else 0x00)
            put(0)                          // LUN
            put(command.size.toByte())
            put(command)
        }.array()

        if (connection.bulkTransfer(outEp, cbw, cbw.size, TIMEOUT) != cbw.size) return false

        var moved = 0
        while (moved < data.size) {
            val chunk = ByteArray(data.size - moved)
            val n = connection.bulkTransfer(
                if (dataIn) inEp else outEp, chunk, chunk.size, TIMEOUT
            )
            if (n <= 0) break
            System.arraycopy(chunk, 0, data, moved, n)
            moved += n
        }

        val csw = ByteArray(13)
        if (connection.bulkTransfer(inEp, csw, csw.size, TIMEOUT) != csw.size) return false
        val status = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (status.int != CSW_SIGNATURE) return false
        status.int  // tag
        status.int  // residue
        return status.get() == 0.toByte() && moved == data.size
    }

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }
}
