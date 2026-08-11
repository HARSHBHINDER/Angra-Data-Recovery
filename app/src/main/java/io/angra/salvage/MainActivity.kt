package io.angra.salvage

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF07090C)
private val Panel = Color(0xFF0E141B)
private val Line = Color(0xFF1D2A36)
private val Jade = Color(0xFF3DFFA8)
private val Amber = Color(0xFFFFC24B)
private val Dim = Color(0xFF7E93A6)
private val Bone = Color(0xFFD6E4EF)
private val Mono = FontFamily.Monospace

enum class Kind { TRASHED, STRANDED, FOLDER, CARVED }

/** LIST = dense rows, GRID = 3-up tiles, LARGE = one big card per file. */
enum class ViewMode(val label: String, val columns: Int) {
    LIST("LIST", 1), GRID("GRID", 3), LARGE("LARGE", 1)
}

data class Recoverable(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val path: String,
    val kind: Kind,
    /** Set for raw-sector finds, which have no URI until their bytes are materialized. */
    val carved: Carved? = null
)

private val HIDDEN_MARKERS = listOf(
    ".statuses", ".thumbnails", ".trashed", ".nomedia", "cache", "recycle", ".pending"
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    // ---------- MediaStore layers ----------

    private suspend fun query(args: Bundle, kind: Kind): List<Recoverable> =
        withContext(Dispatchers.IO) {
            val cols = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            val out = mutableListOf<Recoverable>()
            runCatching {
                contentResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getLong(2) <= 0L) continue
                        out += Recoverable(
                            MediaStore.Files.getContentUri(
                                MediaStore.VOLUME_EXTERNAL, c.getLong(0)
                            ),
                            c.getString(1) ?: "(unnamed)",
                            c.getLong(2),
                            c.getString(3) ?: "—",
                            kind
                        )
                    }
                }
            }
            out
        }

    /**
     * Android holds deleted media in a trash state for 30-60 days. MATCH_ONLY on
     * QUERY_ARG_MATCH_TRASHED is the only non-root way to see it; createTrashRequest()
     * is the only way to bring it back.
     */
    private suspend fun scanTrashed(): List<Recoverable> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return query(Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }, Kind.TRASHED)
    }

    /**
     * Files still on disk but invisible to galleries: WhatsApp .Statuses, Telegram cache,
     * thumbnail leftovers, .trashed- remnants. Not deleted — stranded, which is exactly
     * why they're reachable without root.
     */
    private suspend fun scanStranded(): List<Recoverable> {
        val needles = arrayOf(
            "%WhatsApp%", "%Statuses%", "%Telegram%", "%thumbnails%", "%Recycle%", "%trashed%"
        )
        val path = MediaStore.Files.FileColumns.RELATIVE_PATH
        return query(Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                needles.joinToString(" OR ") { "$path LIKE ?" }
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, needles)
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )
        }, Kind.STRANDED)
    }

    // ---------- SAF folder layer: every file type, any volume ----------

    /**
     * Recursive walk of a user-picked folder tree — SD card, USB-OTG stick, or any
     * directory. MediaStore only indexes media, so this is what reaches .txt, .md, .pdf,
     * .docx, .zip and everything else. DocumentsContract is used directly rather than
     * DocumentFile because DocumentFile issues one query per node and crawls on big trees.
     */
    private suspend fun scanTree(tree: Uri, onProgress: (Int) -> Unit): List<Recoverable> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Recoverable>()
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            // ponytail: 50k-file ceiling so a pathological tree can't OOM the list.
            val cap = 50_000
            val queue = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))

            while (queue.isNotEmpty() && out.size < cap) {
                val (docId, prefix) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                runCatching {
                    contentResolver.query(children, cols, null, null, null)?.use { c ->
                        while (c.moveToNext() && out.size < cap) {
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: "(unnamed)"
                            val mime = c.getString(2) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                queue += id to "$prefix$name/"
                                continue
                            }
                            val size = c.getLong(3)
                            if (size <= 0L) continue
                            out += Recoverable(
                                DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                name, size, prefix.ifEmpty { "/" }, Kind.FOLDER
                            )
                        }
                    }
                }
                onProgress(out.size)
            }
            out.sortedByDescending { r ->
                val hay = (r.path + r.name).lowercase()
                HIDDEN_MARKERS.count { hay.contains(it) }
            }
        }

    /**
     * Copy selected files into a user-picked destination tree.
     * Refuses to write back onto the volume being recovered from — doing so can overwrite
     * the free space still holding other deleted files.
     */
    private suspend fun export(items: List<Recoverable>, dest: Uri, sourceTree: Uri?): String =
        withContext(Dispatchers.IO) {
            if (sourceTree != null && sameVolume(dest, sourceTree)) {
                return@withContext "REFUSED — pick a destination on a different volume"
            }
            val destDir = DocumentsContract.buildDocumentUriUsingTree(
                dest, DocumentsContract.getTreeDocumentId(dest)
            )
            var ok = 0
            var failed = 0
            for (r in items) {
                val source = uriFor(r) ?: run { failed++; continue }
                val wrote = runCatching {
                    val target = DocumentsContract.createDocument(
                        contentResolver, destDir, "application/octet-stream", r.name
                    ) ?: return@runCatching false
                    contentResolver.openInputStream(source)?.use { input ->
                        contentResolver.openOutputStream(target)?.use { output ->
                            input.copyTo(output)
                        } ?: return@runCatching false
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (wrote) ok++ else failed++
            }
            "EXPORTED $ok" + if (failed > 0) " · $failed failed" else ""
        }

    /** Tree URIs encode their volume in the document id ("primary:DCIM", "1A2B-3C4D:x"). */
    private fun sameVolume(a: Uri, b: Uri): Boolean =
        DocumentsContract.getTreeDocumentId(a).substringBefore(':') ==
            DocumentsContract.getTreeDocumentId(b).substringBefore(':')

    // ---------- deep scan: raw sectors over USB-OTG ----------

    private var usbDevice: UsbBlockDevice? = null

    /** Raw byte source backing the current deep scan — a USB device or a root partition. */
    private var rawRead: ((Long, ByteArray) -> Int)? = null

    /** Materialized carved ranges, keyed by sector offset, so each is extracted at most once. */
    private val materialized = mutableMapOf<Long, Uri>()

    /**
     * Deep scan of a USB mass-storage device. This is the only layer that finds *permanently*
     * deleted files: it reads sectors directly and identifies files by their own headers, so a
     * file with no remaining directory entry is still recoverable. Works on removable media
     * because FAT32/exFAT leave the data intact on delete and cards are not TRIMmed.
     */
    private suspend fun deepScan(
        read: (Long, ByteArray) -> Int,
        total: Long,
        onProgress: (String) -> Unit
    ): List<Recoverable> = withContext(Dispatchers.IO) {
        // Directory entries first: they carry real names and exact sizes.
        val named = fatDeletedEntries(read) { n -> onProgress("$n deleted entries") }

        val carvedHits = carve(
            totalBytes = total,
            read = read,
            onProgress = { scanned, hits ->
                val pct = if (total > 0) scanned * 100 / total else 0
                onProgress("carving $pct% · $hits found")
            }
        )

        // A carved hit at the same offset as a named entry is the same file; keep the named one.
        val namedOffsets = named.map { it.offset }.toHashSet()
        (named + carvedHits.filterNot { it.offset in namedOffsets }).map { c ->
            Recoverable(
                uri = Uri.EMPTY,
                name = c.name,
                sizeBytes = c.length,
                path = if (c.name.startsWith("_")) "deleted entry" else "raw sector",
                kind = Kind.CARVED,
                carved = c
            )
        }
    }

    /**
     * Extracts a carved byte range into cache and returns a URI for it. Everything downstream —
     * thumbnails, preview, export — already speaks URIs, so materializing here means none of
     * those paths need to know that raw sectors exist.
     */
    private suspend fun uriFor(r: Recoverable): Uri? {
        val c = r.carved ?: return r.uri.takeIf { it != Uri.EMPTY }
        materialized[c.offset]?.let { return it }
        val src = rawRead ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val file = java.io.File(cacheDir, "carved_${c.offset}_${c.name}")
                if (!file.exists() || file.length() != c.length) {
                    file.outputStream().use { out ->
                        val buf = ByteArray(SCAN_CHUNK)
                        var written = 0L
                        while (written < c.length) {
                            val want = minOf(buf.size.toLong(), c.length - written).toInt()
                            val slice = if (want == buf.size) buf else ByteArray(want)
                            val got = src(c.offset + written, slice)
                            if (got <= 0) break
                            out.write(slice, 0, got)
                            written += got
                        }
                    }
                }
                Uri.fromFile(file).also { materialized[c.offset] = it }
            }.getOrNull()
        }
    }

    /** Untrash. Android returns an IntentSender so the *user* grants the write, not us. */
    private fun restoreRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty())
            MediaStore.createTrashRequest(contentResolver, uris, false).intentSender
        else null

    // ---------------- UI ----------------

    @Composable
    private fun App() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Jade, background = Ink, surface = Panel,
                onPrimary = Ink, onBackground = Jade, onSurface = Jade
            )
        ) {
            val scope = rememberCoroutineScope()
            var found by remember { mutableStateOf<List<Recoverable>>(emptyList()) }
            var scanning by remember { mutableStateOf(false) }
            var scanned by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            var sourceTree by remember { mutableStateOf<Uri?>(null) }
            var mode by remember { mutableStateOf(ViewMode.LIST) }
            var preview by remember { mutableStateOf<Recoverable?>(null) }
            val selected = remember { mutableStateListOf<Uri>() }

            val perms = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { res ->
                if (res.resultCode == Activity.RESULT_OK) {
                    status = "RESTORED ${selected.size} item(s) to gallery"
                    found = found.filterNot { it.uri in selected }
                    selected.clear()
                } else status = "restore cancelled"
            }

            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { tree ->
                if (tree == null) return@rememberLauncherForActivityResult
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                sourceTree = tree
                selected.clear()
                scanning = true; status = ""
                scope.launch {
                    found = scanTree(tree) { n -> status = "$n files…" }
                    scanning = false; scanned = true
                    status = "${found.size} files in folder"
                }
            }

            val destPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { dest ->
                if (dest == null) return@rememberLauncherForActivityResult
                val items = found.filter { it.uri in selected }
                scope.launch {
                    status = "exporting ${items.size}…"
                    status = export(items, dest, sourceTree)
                    selected.clear()
                }
            }

            LaunchedEffect(Unit) {
                perms.launch(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    ) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }

            preview?.let { PreviewDialog(it) { preview = null } }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Ink, Color(0xFF0A1017), Ink)))
            ) {
                val pulse by rememberInfiniteTransition(label = "p").animateFloat(
                    0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pa"
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(mode.columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val full = GridItemSpan(mode.columns)

                    item(span = { full }) {
                        Column {
                            Spacer(Modifier.height(48.dp))
                            Text(
                                "ANGRA", color = Jade, fontFamily = Mono, fontSize = 34.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 8.sp
                            )
                            Text(
                                "DATA RECOVERY", color = Dim, fontFamily = Mono,
                                fontSize = 12.sp, letterSpacing = 6.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Bar(
                                if (scanning) "SCANNING…" else "▸  SCAN THIS PHONE",
                                filled = false,
                                alpha = if (scanning) pulse else 1f,
                                enabled = !scanning
                            ) {
                                sourceTree = null
                                selected.clear()
                                scanning = true; status = ""
                                scope.launch {
                                    found = scanTrashed() + scanStranded()
                                    scanning = false; scanned = true
                                    val t = found.count { it.kind == Kind.TRASHED }
                                    status = "$t recoverable · ${found.size - t} stranded"
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Bar("▸  SCAN SD CARD / USB / FOLDER", false, enabled = !scanning) {
                                folderPicker.launch(null)
                            }
                            Spacer(Modifier.height(10.dp))
                            Bar("▸  DEEP SCAN — PERMANENTLY DELETED", false, enabled = !scanning) {
                                sourceTree = null
                                selected.clear()
                                startDeepScan(
                                    onStatus = { status = it },
                                    onBusy = { scanning = it },
                                    onDone = { found = it; scanned = true }
                                )
                            }
                            if (status.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    status.uppercase(), color = Amber, fontFamily = Mono,
                                    fontSize = 11.sp, letterSpacing = 1.5.sp
                                )
                            }
                            if (selected.isNotEmpty()) {
                                val restorable =
                                    found.any { it.uri in selected && it.kind == Kind.TRASHED }
                                Spacer(Modifier.height(14.dp))
                                if (restorable) {
                                    Bar("RESTORE ${selected.size} TO GALLERY", true) {
                                        restoreRequest(selected.toList())?.let {
                                            restoreLauncher.launch(
                                                IntentSenderRequest.Builder(it).build()
                                            )
                                        } ?: run { status = "needs Android 11+" }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                Bar("EXPORT ${selected.size} TO FOLDER…", true) {
                                    destPicker.launch(null)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    val groups = found.groupBy { it.kind }
                    listOf(
                        Triple(Kind.TRASHED, "RECOVERABLE", "restorable to gallery"),
                        Triple(Kind.FOLDER, "IN FOLDER", "every file type, hidden ones first"),
                        Triple(
                            Kind.CARVED, "PERMANENTLY DELETED",
                            "found in raw sectors — no directory entry left"
                        ),
                        Triple(Kind.STRANDED, "STRANDED", "on disk, hidden from gallery")
                    ).forEach { (kind, title, sub) ->
                        val list = groups[kind].orEmpty()
                        if (list.isEmpty()) return@forEach
                        val tint = if (kind == Kind.STRANDED) Amber else Jade
                        item(span = { full }) { SectionLabel("$title (${list.size})", sub, tint) }
                        items(list, key = { it.uri.toString() }) { r ->
                            FileCell(
                                r = r,
                                sel = r.uri in selected,
                                tint = tint,
                                mode = mode,
                                onToggle = {
                                    if (r.uri in selected) selected.remove(r.uri)
                                    else selected.add(r.uri)
                                },
                                onPreview = { preview = r }
                            )
                        }
                    }

                    if (scanned && found.isEmpty()) {
                        item(span = { full }) {
                            Text(
                                "\nNOTHING FOUND — no trashed, stranded, or folder files here.",
                                color = Dim, fontFamily = Mono, fontSize = 11.sp
                            )
                        }
                    }

                    item(span = { full }) {
                        Column {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Tap a thumbnail to preview before recovering. Scans this phone " +
                                    "for trashed and stranded media, and any folder, SD card or " +
                                    "USB stick for every file type. Export writes to a different " +
                                    "volume on purpose — writing back to the source can overwrite " +
                                    "what is still recoverable. Files erased from encrypted " +
                                    "internal storage are gone at the hardware level; no app " +
                                    "recovers those. Fully offline, no network permission.",
                                color = Dim.copy(alpha = 0.55f), fontFamily = Mono,
                                fontSize = 9.sp, lineHeight = 14.sp
                            )
                            Spacer(Modifier.height(96.dp))
                        }
                    }
                }

                // Floating view-size switch: LIST -> GRID -> LARGE -> LIST
                if (found.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            mode = ViewMode.entries[(mode.ordinal + 1) % ViewMode.entries.size]
                        },
                        containerColor = Jade,
                        contentColor = Ink,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    ) {
                        Text(
                            mode.label, fontFamily = Mono, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    /**
     * Finds an OTG mass-storage device, gets the user's permission for it, then deep scans.
     * Permission arrives asynchronously via broadcast, so the scan is kicked off from the
     * receiver rather than inline.
     */
    private fun startDeepScan(
        onStatus: (String) -> Unit,
        onBusy: (Boolean) -> Unit,
        onDone: (List<Recoverable>) -> Unit
    ) {
        fun run(read: (Long, ByteArray) -> Int, total: Long, label: String) {
            rawRead = read
            materialized.clear()
            onBusy(true)
            lifecycleScope.launch {
                onStatus("scanning $label…")
                val hits = deepScan(read, total) { onStatus(it) }
                onBusy(false)
                onDone(hits)
                onStatus(
                    if (hits.isEmpty())
                        "nothing recoverable in $label — blocks are encrypted or already erased"
                    else "${hits.size} permanently deleted files found in $label"
                )
            }
        }

        // Internal storage first — that is where downloads and camera files lived. Needs root;
        // Android exposes no unrooted path to raw blocks, only to files with directory entries.
        lifecycleScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootBlockDevice.rootAvailable() }
            val part = if (rooted) withContext(Dispatchers.IO) {
                RootBlockDevice.candidates().firstOrNull()
            } else null

            if (part != null) {
                run({ off, dest -> part.readAt(off, dest) }, part.sizeBytes, part.path)
                return@launch
            }

            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val device = UsbBlockDevice.candidates(usb).firstOrNull()
            if (device == null) {
                onStatus(
                    if (rooted) "root works but no readable partition found"
                    else "internal storage needs root. Otherwise connect a USB drive or SD " +
                        "card reader via OTG to deep scan that instead."
                )
                return@launch
            }

            fun openUsb() {
                val dev = UsbBlockDevice.open(usb, device)
                if (dev == null) {
                    onStatus("could not open USB device for raw reading")
                    onBusy(false)
                    return
                }
                usbDevice?.close()
                usbDevice = dev
                run(
                    { off, dest -> dev.readAt(off, dest) },
                    dev.blockCount * dev.blockSize,
                    "USB storage"
                )
            }

            if (usb.hasPermission(device)) {
                openUsb()
                return@launch
            }

            onStatus("waiting for USB permission…")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) {
                    runCatching { unregisterReceiver(this) }
                    if (usb.hasPermission(device)) openUsb()
                    else onStatus("USB permission denied")
                }
            }
            ContextCompat.registerReceiver(
                this@MainActivity, receiver,
                IntentFilter(UsbBlockDevice.ACTION_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            UsbBlockDevice.requestPermission(this@MainActivity, usb, device)
        }
    }

    override fun onDestroy() {
        usbDevice?.close()
        usbDevice = null
        super.onDestroy()
    }

    // ---------------- preview ----------------

    /** Full-screen preview. Every branch uses a platform decoder — no image or PDF library. */
    @Composable
    private fun PreviewDialog(r: Recoverable, onDismiss: () -> Unit) {
        val context = LocalContext.current
        // Carved ranges have no URI until extracted; everything below then works unchanged.
        val src by produceState<Uri?>(null, r.uri, r.carved?.offset) { value = uriFor(r) }
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Ink)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.name, color = Jade, fontFamily = Mono, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${r.path}  ·  ${Formatter.formatShortFileSize(context, r.sizeBytes)}",
                            color = Dim, fontFamily = Mono, fontSize = 10.sp
                        )
                    }
                    Text(
                        "✕", color = Jade, fontFamily = Mono, fontSize = 22.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val ready = src
                    if (ready == null) {
                        Text(
                            if (r.carved != null) "extracting from raw sectors…" else "opening…",
                            color = Dim, fontFamily = Mono, fontSize = 11.sp
                        )
                    } else when (classify(r.name)) {
                        FileClass.VIDEO, FileClass.AUDIO -> AndroidView(
                            factory = { buildPlayer(it, ready) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        FileClass.PDF -> PdfPager(ready)

                        FileClass.TEXT, FileClass.DOC -> {
                            val text by produceState<String?>(null, ready) {
                                value = withContext(Dispatchers.IO) {
                                    extractText(context, ready, r.name)
                                } ?: "(no readable text in this file)"
                            }
                            Text(
                                text ?: "reading…",
                                color = Bone, fontFamily = Mono, fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        else -> {
                            val bmp by produceState<Bitmap?>(null, ready) {
                                value = withContext(Dispatchers.IO) {
                                    loadThumb(context, ready, r.name, 1440)
                                }
                            }
                            bmp?.let {
                                Image(
                                    it.asImageBitmap(), r.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: TypeBadge(r.name, 64)
                        }
                    }
                }
            }
        }
    }

    /** PDF pages render one at a time — the whole document is never held in memory. */
    @Composable
    private fun PdfPager(uri: Uri) {
        val context = LocalContext.current
        var page by remember { mutableIntStateOf(0) }
        val count by produceState(0, uri) {
            value = withContext(Dispatchers.IO) { pdfPageCount(context, uri) }
        }
        val bmp by produceState<Bitmap?>(null, uri, page) {
            value = withContext(Dispatchers.IO) { pdfPage(context, uri, page, 1080) }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                bmp?.let {
                    Image(
                        it.asImageBitmap(), "page ${page + 1}",
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit
                    )
                } ?: Text("rendering…", color = Dim, fontFamily = Mono, fontSize = 11.sp)
            }
            if (count > 1) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "◀", color = if (page > 0) Jade else Line,
                        fontFamily = Mono, fontSize = 20.sp,
                        modifier = Modifier.clickable(enabled = page > 0) { page-- }
                    )
                    Text("${page + 1} / $count", color = Dim, fontFamily = Mono, fontSize = 12.sp)
                    Text(
                        "▶", color = if (page < count - 1) Jade else Line,
                        fontFamily = Mono, fontSize = 20.sp,
                        modifier = Modifier.clickable(enabled = page < count - 1) { page++ }
                    )
                }
            }
        }
    }

    // ---------------- cells ----------------

    @Composable
    private fun FileCell(
        r: Recoverable,
        sel: Boolean,
        tint: Color,
        mode: ViewMode,
        onToggle: () -> Unit,
        onPreview: () -> Unit
    ) = when (mode) {
        ViewMode.LIST -> ListRow(r, sel, tint, onToggle, onPreview)
        ViewMode.GRID -> Tile(r, sel, tint, 110.dp, onToggle, onPreview)
        ViewMode.LARGE -> Tile(r, sel, tint, 260.dp, onToggle, onPreview)
    }

    @Composable
    private fun ListRow(
        r: Recoverable, sel: Boolean, tint: Color, onToggle: () -> Unit, onPreview: () -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (sel) tint.copy(alpha = 0.12f) else Panel, RoundedCornerShape(3.dp))
                .border(1.dp, if (sel) tint else Line, RoundedCornerShape(3.dp))
                .clickable(onClick = onToggle)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (sel) "◉" else "○", color = tint, fontFamily = Mono, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onPreview)
            ) { Thumb(r, 44) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.name, color = Bone, fontFamily = Mono, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${r.path}  ·  ${Formatter.formatShortFileSize(LocalContext.current, r.sizeBytes)}",
                    color = Dim, fontFamily = Mono, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun Tile(
        r: Recoverable, sel: Boolean, tint: Color, height: Dp,
        onToggle: () -> Unit, onPreview: () -> Unit
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (sel) tint.copy(alpha = 0.12f) else Panel, RoundedCornerShape(4.dp))
                .border(1.dp, if (sel) tint else Line, RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onPreview),
                contentAlignment = Alignment.Center
            ) {
                Thumb(r, if (height > 200.dp) 720 else 240)
                Text(
                    if (sel) "◉" else "○", color = tint, fontFamily = Mono, fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Ink.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                r.name, color = Bone, fontFamily = Mono, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                Formatter.formatShortFileSize(LocalContext.current, r.sizeBytes),
                color = Dim, fontFamily = Mono, fontSize = 9.sp
            )
        }
    }

    /** Decoded off the main thread; falls back to an extension badge when nothing renders. */
    @Composable
    private fun Thumb(r: Recoverable, px: Int) {
        val context = LocalContext.current
        val bmp by produceState<Bitmap?>(null, r.uri, r.carved?.offset, px) {
            val src = uriFor(r) ?: return@produceState
            value = withContext(Dispatchers.IO) { loadThumb(context, src, r.name, px) }
        }
        bmp?.let {
            Image(
                it.asImageBitmap(), r.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: TypeBadge(r.name, if (px > 200) 22 else 11)
    }

    @Composable
    private fun TypeBadge(name: String, fontSize: Int) {
        val ext = name.substringAfterLast('.', "?").uppercase().take(5)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF121A22)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                ext, color = Dim, fontFamily = Mono, fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun Bar(
        label: String,
        filled: Boolean,
        alpha: Float = 1f,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (filled) 48.dp else 56.dp)
                .border(1.dp, Jade.copy(alpha = alpha), RoundedCornerShape(4.dp))
                .background(
                    if (filled) Jade else Jade.copy(alpha = 0.06f),
                    RoundedCornerShape(4.dp)
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (filled) Ink else Jade,
                fontFamily = Mono,
                fontSize = if (filled) 13.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.alpha(alpha)
            )
        }
    }

    @Composable
    private fun SectionLabel(title: String, sub: String, tint: Color) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                title, color = tint, fontFamily = Mono, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp
            )
            Text(sub, color = Dim, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}
