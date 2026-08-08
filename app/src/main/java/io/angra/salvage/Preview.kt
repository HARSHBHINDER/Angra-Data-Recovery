package io.angra.salvage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Decoded thumbnails, capped at an eighth of heap. Scrolling a 5k-file card re-decodes otherwise. */
private val thumbCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8).toInt()
) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

enum class FileClass { IMAGE, VIDEO, AUDIO, PDF, DOC, TEXT, ARCHIVE, OTHER }

fun classify(name: String): FileClass = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "tiff" -> FileClass.IMAGE
    "mp4", "mkv", "3gp", "avi", "mov", "webm", "m4v", "flv", "ts" -> FileClass.VIDEO
    "mp3", "aac", "wav", "ogg", "m4a", "flac", "opus", "amr", "wma" -> FileClass.AUDIO
    "pdf" -> FileClass.PDF
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods" -> FileClass.DOC
    "txt", "md", "csv", "json", "xml", "log", "html", "htm", "kt", "java", "py",
    "js", "ts", "c", "cpp", "h", "sh", "yml", "yaml", "ini", "cfg" -> FileClass.TEXT
    "zip", "rar", "7z", "tar", "gz", "apk" -> FileClass.ARCHIVE
    else -> FileClass.OTHER
}

/**
 * Thumbnail for anything the platform can decode, with no version gates and no image
 * library: BitmapFactory for stills, MediaMetadataRetriever for video frames and embedded
 * album art, PdfRenderer for the first page of a PDF. Works for both MediaStore and SAF
 * URIs, which loadThumbnail() does not do uniformly across API levels.
 */
fun loadThumb(context: Context, uri: Uri, name: String, px: Int): Bitmap? {
    val key = "$uri@$px"
    thumbCache.get(key)?.let { return it }

    val bmp = runCatching {
        when (classify(name)) {
            FileClass.IMAGE -> decodeSampled(context, uri, px)
            FileClass.VIDEO -> retriever(context, uri) { it.getFrameAtTime(0) }
            FileClass.AUDIO -> retriever(context, uri) { r ->
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            FileClass.PDF -> pdfPage(context, uri, 0, px)
            else -> null
        }
    }.getOrNull()

    if (bmp != null) thumbCache.put(key, bmp)
    return bmp
}

/** Two-pass decode so a 108MP photo never lands in memory at full size. */
private fun decodeSampled(context: Context, uri: Uri, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > px * 2 && bounds.outHeight / sample > px * 2) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}

private fun <T> retriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        block(r)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/** Render one PDF page. android.graphics.pdf ships with the platform — no PDF library needed. */
fun pdfPage(context: Context, uri: Uri, index: Int, widthPx: Int): Bitmap? =
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { doc ->
                if (index !in 0 until doc.pageCount) return@use null
                doc.openPage(index).use { page ->
                    val h = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

fun pdfPageCount(context: Context, uri: Uri): Int =
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        } ?: 0
    }.getOrDefault(0)

/**
 * Text preview. Plain files are read directly; Office files are ZIP containers, so the
 * document part is unzipped and its tags stripped — enough to confirm "this is the file
 * I lost" without shipping a document renderer.
 */
fun extractText(context: Context, uri: Uri, name: String, limit: Int = 20_000): String? =
    runCatching {
        when (classify(name)) {
            FileClass.TEXT -> context.contentResolver.openInputStream(uri)
                ?.use { it.readAtMost(limit).decodeToString() }

            FileClass.DOC -> {
                val wanted = listOf(
                    "word/document.xml", "xl/sharedStrings.xml",
                    "ppt/slides/slide1.xml", "content.xml"
                )
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        generateSequence { zip.nextEntry }
                            .firstOrNull { e -> wanted.any { e.name.equals(it, true) } }
                            ?.let { zip.readAtMost(limit * 4).decodeToString() }
                    }
                }?.let { xml ->
                    xml.replace(Regex("<[^>]{0,4000}>"), " ")
                        .replace(Regex("\\s{2,}"), " ")
                        .trim()
                        .take(limit)
                }
            }

            else -> null
        }?.takeIf { it.isNotBlank() }
    }.getOrNull()

/** Reads at most [limit] bytes so a 2 GB "text" file can't take the app down. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buf = ByteArray(limit)
    var n = 0
    while (n < limit) {
        val r = read(buf, n, limit - n)
        if (r <= 0) break
        n += r
    }
    return buf.copyOf(n)
}

/** VideoView covers both video and audio playback; MediaController supplies the transport UI. */
fun buildPlayer(context: Context, uri: Uri): VideoView {
    val view = VideoView(context)
    view.setVideoURI(uri)
    view.setMediaController(MediaController(context).also { it.setAnchorView(view) })
    view.setOnPreparedListener { mp: MediaPlayer ->
        mp.isLooping = false
        view.start()
    }
    return view
}
