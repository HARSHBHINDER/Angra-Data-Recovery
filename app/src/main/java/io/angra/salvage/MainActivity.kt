package io.angra.salvage

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Kind(val title: String, val blurb: String, val tone: Tone) {
    TRASHED("Recoverable", "In Android's trash — restores to your gallery", Tone.GOOD),
    CARVED("Permanently deleted", "Found in raw sectors, no directory entry left", Tone.GOOD),
    FOLDER("In folder", "Every file type on the scanned volume", Tone.NEUTRAL),
    STRANDED("Stranded", "On disk but hidden from your gallery", Tone.WARN)
}

enum class Tone { GOOD, NEUTRAL, WARN }

/** Density of the results grid, cycled from the floating button. */
enum class ViewMode(val label: String, val columns: Int) {
    LIST("List", 1), GRID("Grid", 3), LARGE("Large", 1)
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

/** Strips path separators and control characters before any name becomes a real filename. */
fun safeName(raw: String): String {
    val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").trim().trim('.')
    return cleaned.ifEmpty { "recovered" }.take(120)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AngraTheme { Screen() } }
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
     * QUERY_ARG_MATCH_TRASHED is the only non-root way to see it, and createTrashRequest()
     * the only way to bring it back.
     */
    private suspend fun scanTrashed(): List<Recoverable> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return query(Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }, Kind.TRASHED)
    }

    /**
     * Files still on disk but invisible to galleries: WhatsApp .Statuses, Telegram cache,
     * thumbnail leftovers, .trashed- remnants. Stranded rather than deleted, which is why
     * they are reachable at all without root.
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

    // ---------- SAF folder layer ----------

    /**
     * Recursive walk of a user-picked tree — SD card, USB stick, any directory. MediaStore
     * indexes only media, so this is what reaches .txt, .pdf, .docx, .zip and the rest.
     * DocumentsContract is used directly because DocumentFile issues one query per node and
     * crawls on real trees.
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
            // ponytail: 50k-file ceiling so a pathological tree cannot OOM the list.
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
     * Copies selected files into a user-picked destination tree. Refuses a destination on the
     * volume being recovered from, because writing there can overwrite the free space still
     * holding other deleted files.
     */
    private suspend fun export(items: List<Recoverable>, dest: Uri, sourceTree: Uri?): String =
        withContext(Dispatchers.IO) {
            if (sourceTree != null && sameVolume(dest, sourceTree)) {
                return@withContext "Pick a destination on a different volume"
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
                        contentResolver, destDir, "application/octet-stream", safeName(r.name)
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
            "Exported $ok file(s)" + if (failed > 0) " · $failed failed" else ""
        }

    /** Tree URIs encode their volume in the document id ("primary:DCIM", "1A2B-3C4D:x"). */
    private fun sameVolume(a: Uri, b: Uri): Boolean =
        DocumentsContract.getTreeDocumentId(a).substringBefore(':') ==
            DocumentsContract.getTreeDocumentId(b).substringBefore(':')

    // ---------- deep scan ----------

    private var usbDevice: UsbBlockDevice? = null

    /** Raw byte source backing the current deep scan — a USB device or a root partition. */
    private var rawRead: ((Long, ByteArray) -> Int)? = null

    private val materialized = mutableMapOf<Long, Uri>()

    private val carvedDir: File get() = File(cacheDir, "carved").apply { mkdirs() }

    private suspend fun deepScan(
        read: (Long, ByteArray) -> Int,
        total: Long,
        onProgress: (String) -> Unit
    ): List<Recoverable> = withContext(Dispatchers.IO) {
        val named = fatDeletedEntries(read) { n -> onProgress("$n deleted entries") }

        val carvedHits = carve(
            totalBytes = total,
            read = read,
            onProgress = { scanned, hits ->
                val pct = if (total > 0) scanned * 100 / total else 0
                onProgress("Carving $pct% · $hits found")
            }
        )

        val namedOffsets = named.map { it.offset }.toHashSet()
        (named + carvedHits.filterNot { it.offset in namedOffsets }).map { c ->
            Recoverable(
                uri = Uri.EMPTY,
                name = safeName(c.name),
                sizeBytes = c.length,
                path = if (c.confident) "exact size" else "estimated size",
                kind = Kind.CARVED,
                carved = c
            )
        }
    }

    /**
     * Extracts a carved byte range into private cache and returns a content:// URI for it.
     * Everything downstream already speaks URIs, so materializing here keeps thumbnails,
     * preview and export unaware that raw sectors exist.
     */
    private suspend fun uriFor(r: Recoverable): Uri? {
        val c = r.carved ?: return r.uri.takeIf { it != Uri.EMPTY }
        materialized[c.offset]?.let { return it }
        val src = rawRead ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(carvedDir, "${c.offset}_${safeName(c.name)}")
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
                FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    .also { materialized[c.offset] = it }
            }.getOrNull()
        }
    }

    /**
     * Recovered bytes are the user's private data sitting unencrypted in cache. They are
     * wiped when the app closes, and on demand, rather than left indefinitely.
     */
    private fun wipeCarvedCache(): Int {
        val files = carvedDir.listFiles().orEmpty()
        files.forEach { runCatching { it.delete() } }
        materialized.clear()
        return files.size
    }

    /** Untrash. Android returns an IntentSender so the *user* grants the write, not us. */
    private fun restoreRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty())
            MediaStore.createTrashRequest(contentResolver, uris, false).intentSender
        else null

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
                onStatus("Scanning $label…")
                val hits = deepScan(read, total) { onStatus(it) }
                onBusy(false)
                onDone(hits)
                onStatus(
                    if (hits.isEmpty())
                        "Nothing recoverable in $label — blocks are encrypted or already erased"
                    else "${hits.size} permanently deleted file(s) found"
                )
            }
        }

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
                    if (rooted) "Root works, but no readable partition was found"
                    else "Internal storage needs root. Connect a USB drive or card reader " +
                        "over OTG to deep scan that instead."
                )
                return@launch
            }

            fun openUsb() {
                val dev = UsbBlockDevice.open(usb, device)
                if (dev == null) {
                    onStatus("Could not open the USB device for raw reading")
                    onBusy(false)
                    return
                }
                usbDevice?.close()
                usbDevice = dev
                run({ o, d -> dev.readAt(o, d) }, dev.blockCount * dev.blockSize, "USB storage")
            }

            if (usb.hasPermission(device)) {
                openUsb()
                return@launch
            }

            onStatus("Waiting for USB permission…")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    runCatching { unregisterReceiver(this) }
                    if (usb.hasPermission(device)) openUsb() else onStatus("USB permission denied")
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
        rawRead = null
        wipeCarvedCache()
        super.onDestroy()
    }

    // ================= UI =================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var found by remember { mutableStateOf<List<Recoverable>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var scanned by remember { mutableStateOf(false) }
        var sourceTree by remember { mutableStateOf<Uri?>(null) }
        var mode by remember { mutableStateOf(ViewMode.LIST) }
        var preview by remember { mutableStateOf<Recoverable?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        val selected = remember { mutableStateListOf<Uri>() }
        val snackbar = remember { SnackbarHostState() }
        val grid = rememberLazyGridState()

        fun say(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

        val perms = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

        val restoreLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                say("Restored ${selected.size} file(s) to your gallery")
                found = found.filterNot { keyOf(it) in selected }
                selected.clear()
            } else say("Restore cancelled")
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
            busy = true
            scope.launch {
                found = scanTree(tree) { }
                busy = false; scanned = true
                say("${found.size} file(s) on that volume")
            }
        }

        val destPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { dest ->
            if (dest == null) return@rememberLauncherForActivityResult
            val items = found.filter { keyOf(it) in selected }
            scope.launch {
                busy = true
                val result = export(items, dest, sourceTree)
                busy = false
                selected.clear()
                say(result)
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
        if (showAbout) AboutDialog { showAbout = false }

        Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopBar(
                    selectedCount = selected.size,
                    onClear = { selected.clear() },
                    onAbout = { showAbout = true },
                    onWipe = {
                        val n = wipeCarvedCache()
                        say(if (n > 0) "Cleared $n cached file(s)" else "Cache already empty")
                    }
                )
            },
            bottomBar = {
                ActionBar(
                    visible = selected.isNotEmpty(),
                    count = selected.size,
                    canRestore = found.any { keyOf(it) in selected && it.kind == Kind.TRASHED },
                    onRestore = {
                        // Only real MediaStore rows can be untrashed; carved keys are synthetic.
                        val trashed = found
                            .filter { keyOf(it) in selected && it.kind == Kind.TRASHED }
                            .map { it.uri }
                        restoreRequest(trashed)?.let {
                            restoreLauncher.launch(IntentSenderRequest.Builder(it).build())
                        } ?: say("Restoring to the gallery needs Android 11 or newer")
                    },
                    onExport = { destPicker.launch(null) }
                )
            },
            floatingActionButton = {
                AnimatedVisibility(found.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    SmallFloatingActionButton(
                        onClick = {
                            mode = ViewMode.entries[(mode.ordinal + 1) % ViewMode.entries.size]
                            scope.launch { grid.scrollToItem(0) }
                        },
                        containerColor = Jade,
                        contentColor = Ink
                    ) {
                        Icon(Icons.Default.List, "Change layout: ${mode.label}")
                    }
                }
            }
        ) { pad ->
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(mode.columns),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val full = GridItemSpan(mode.columns)

                item(span = { full }) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        ScanCard(
                            icon = Icons.Default.Search,
                            title = "Scan this phone",
                            subtitle = "Trashed media and stranded files. No root needed.",
                            enabled = !busy
                        ) {
                            sourceTree = null; selected.clear(); busy = true
                            scope.launch {
                                found = scanTrashed() + scanStranded()
                                busy = false; scanned = true
                                val t = found.count { it.kind == Kind.TRASHED }
                                say("$t recoverable · ${found.size - t} stranded")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        ScanCard(
                            icon = Icons.Default.Refresh,
                            title = "Scan a folder, SD card or USB",
                            subtitle = "Every file type on any volume you pick.",
                            enabled = !busy
                        ) { folderPicker.launch(null) }
                        Spacer(Modifier.height(10.dp))
                        ScanCard(
                            icon = Icons.Default.Lock,
                            title = "Deep scan for permanently deleted",
                            subtitle = "Reads raw sectors. Internal storage needs root; " +
                                "USB over OTG does not.",
                            enabled = !busy,
                            accent = Amber
                        ) {
                            sourceTree = null; selected.clear()
                            startDeepScan(
                                onStatus = { say(it) },
                                onBusy = { busy = it },
                                onDone = { found = it; scanned = true }
                            )
                        }

                        AnimatedVisibility(busy, enter = expandVertically(), exit = shrinkVertically()) {
                            Column {
                                Spacer(Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(CircleShape),
                                    color = Jade,
                                    trackColor = Hairline
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                Kind.entries.forEach { kind ->
                    val list = found.filter { it.kind == kind }
                    if (list.isEmpty()) return@forEach
                    item(span = { full }) { SectionHeader(kind, list.size) }
                    items(list, key = { it.uri.toString() + it.carved?.offset }) { r ->
                        FileCell(
                            r = r,
                            selected = keyOf(r) in selected,
                            mode = mode,
                            onToggle = {
                                val k = keyOf(r)
                                if (k in selected) selected.remove(k) else selected.add(k)
                            },
                            onPreview = { preview = r }
                        )
                    }
                }

                if (scanned && found.isEmpty() && !busy) {
                    item(span = { full }) { EmptyState() }
                }

                item(span = { full }) { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    /**
     * Every carved row carries Uri.EMPTY until its bytes are extracted, so selection is keyed
     * by sector offset for those. Without this, selecting one carved file selects them all.
     */
    private fun keyOf(r: Recoverable): Uri =
        r.carved?.let { Uri.parse("carved://${it.offset}") } ?: r.uri

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TopBar(
        selectedCount: Int,
        onClear: () -> Unit,
        onAbout: () -> Unit,
        onWipe: () -> Unit
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Ink, titleContentColor = Bone
            ),
            navigationIcon = {
                if (selectedCount > 0) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, "Clear selection", tint = Jade)
                    }
                }
            },
            title = {
                if (selectedCount > 0) {
                    Text(
                        "$selectedCount selected",
                        style = MaterialTheme.typography.titleMedium, color = Jade
                    )
                } else {
                    Column {
                        Text("ANGRA", style = MaterialTheme.typography.headlineLarge, color = Jade)
                        Text(
                            "DATA RECOVERY",
                            style = MaterialTheme.typography.labelSmall, color = Muted
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onWipe) {
                    Icon(Icons.Default.Delete, "Wipe recovered cache", tint = Muted)
                }
                IconButton(onClick = onAbout) {
                    Icon(Icons.Default.Info, "What this app can recover", tint = Muted)
                }
            }
        )
    }

    @Composable
    private fun ScanCard(
        icon: ImageVector,
        title: String,
        subtitle: String,
        enabled: Boolean,
        accent: Color = Jade,
        onClick: () -> Unit
    ) {
        val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "cardAlpha")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(
                    Brush.horizontalGradient(listOf(Surface2, Surface1))
                )
                .border(1.dp, Hairline, RoundedCornerShape(CardRadius))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.copy(alpha = 0.13f * alpha)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = accent.copy(alpha = alpha)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = Bone.copy(alpha = alpha)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = Muted.copy(alpha = alpha)
                )
            }
        }
    }

    @Composable
    private fun SectionHeader(kind: Kind, count: Int) {
        val tint = when (kind.tone) {
            Tone.GOOD -> Jade
            Tone.WARN -> Amber
            Tone.NEUTRAL -> Bone
        }
        Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    kind.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = tint
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall, color = Muted
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }

    @Composable
    private fun ActionBar(
        visible: Boolean,
        count: Int,
        canRestore: Boolean,
        onRestore: () -> Unit,
        onExport: () -> Unit
    ) {
        AnimatedVisibility(
            visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(color = Surface1, tonalElevation = 6.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Gutter, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (canRestore) {
                        OutlinedButton(
                            onClick = onRestore,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Restore")
                        }
                    }
                    Button(
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Jade, contentColor = Ink)
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Save $count")
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Surface2),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Nothing found", style = MaterialTheme.typography.titleMedium, color = Bone)
            Spacer(Modifier.height(6.dp))
            Text(
                "No trashed, stranded, or carvable files on this source.",
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
        }
    }

    @Composable
    private fun AboutDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Surface1,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Got it", color = Jade) } },
            title = { Text("What this can recover", color = Bone) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutLine(
                        "Trashed media", Jade,
                        "Android keeps deleted photos and video for 30–60 days. These restore fully."
                    )
                    AboutLine(
                        "Stranded files", Amber,
                        "Still on disk but hidden from your gallery — WhatsApp statuses, caches, " +
                            "thumbnail leftovers."
                    )
                    AboutLine(
                        "Permanently deleted", Jade,
                        "Found by reading raw sectors. Works on USB and SD over OTG. Internal " +
                            "storage needs root."
                    )
                    AboutLine(
                        "What nothing can recover", Rust,
                        "Files erased from encrypted internal storage. Android discards each " +
                            "file's key on delete and TRIM zeroes the blocks — no app recovers " +
                            "those, rooted or not."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Runs entirely offline — the app has no internet permission at all. " +
                            "Recovered files are cached privately and wiped when you close it.",
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }
            }
        )
    }

    @Composable
    private fun AboutLine(title: String, tint: Color, body: String) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = tint)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }

    // ---------------- preview ----------------

    @Composable
    private fun PreviewDialog(r: Recoverable, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val src by produceState<Uri?>(null, r.uri, r.carved?.offset) { value = uriFor(r) }
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Ink)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.name, style = MaterialTheme.typography.titleMedium, color = Bone,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${r.path} · ${Formatter.formatShortFileSize(context, r.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close preview", tint = Jade)
                    }
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(CardRadius))
                        .background(Surface1),
                    contentAlignment = Alignment.Center
                ) {
                    val ready = src
                    if (ready == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Jade, strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (r.carved != null) "Extracting from raw sectors…" else "Opening…",
                                style = MaterialTheme.typography.bodySmall, color = Muted
                            )
                        }
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
                                } ?: "No readable text in this file."
                            }
                            Text(
                                text ?: "Reading…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Bone, fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp)
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
                            } ?: TypeBadge(r.name, 44)
                        }
                    }
                }
            }
        }
    }

    /** PDF pages render one at a time — the whole document never sits in memory. */
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
                        it.asImageBitmap(), "Page ${page + 1}",
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit
                    )
                } ?: CircularProgressIndicator(color = Jade, strokeWidth = 2.dp)
            }
            if (count > 1) {
                Row(
                    Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) {
                        Text("Previous", color = if (page > 0) Jade else Hairline)
                    }
                    Text(
                        "${page + 1} / $count",
                        style = MaterialTheme.typography.labelSmall, color = Muted
                    )
                    TextButton(
                        onClick = { if (page < count - 1) page++ }, enabled = page < count - 1
                    ) {
                        Text("Next", color = if (page < count - 1) Jade else Hairline)
                    }
                }
            }
        }
    }

    // ---------------- cells ----------------

    @Composable
    private fun FileCell(
        r: Recoverable,
        selected: Boolean,
        mode: ViewMode,
        onToggle: () -> Unit,
        onPreview: () -> Unit
    ) = when (mode) {
        ViewMode.LIST -> ListRow(r, selected, onToggle, onPreview)
        ViewMode.GRID -> Tile(r, selected, 104.dp, onToggle, onPreview)
        ViewMode.LARGE -> Tile(r, selected, 250.dp, onToggle, onPreview)
    }

    @Composable
    private fun ListRow(
        r: Recoverable, selected: Boolean, onToggle: () -> Unit, onPreview: () -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) Jade.copy(alpha = 0.10f) else Surface1)
                .border(
                    1.dp, if (selected) Jade.copy(alpha = 0.5f) else Hairline,
                    RoundedCornerShape(11.dp)
                )
                .clickable(onClick = onPreview)
                .padding(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
            ) { Thumb(r, 46) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.name, style = MaterialTheme.typography.bodyMedium, color = Bone,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${r.path} · ${Formatter.formatShortFileSize(LocalContext.current, r.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Jade, uncheckedColor = Hairline, checkmarkColor = Ink
                )
            )
        }
    }

    @Composable
    private fun Tile(
        r: Recoverable, selected: Boolean, height: Dp, onToggle: () -> Unit, onPreview: () -> Unit
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Jade.copy(alpha = 0.10f) else Surface1)
                .border(
                    1.dp, if (selected) Jade.copy(alpha = 0.5f) else Hairline,
                    RoundedCornerShape(12.dp)
                )
                .padding(5.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Surface2)
                    .clickable(onClick = onPreview)
            ) {
                Thumb(r, if (height > 200.dp) 720 else 260)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = 0.55f))
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check, if (selected) "Selected" else "Select",
                        tint = if (selected) Jade else Muted,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                r.name, style = MaterialTheme.typography.bodySmall, color = Bone,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp)
            )
            Text(
                Formatter.formatShortFileSize(LocalContext.current, r.sizeBytes),
                style = MaterialTheme.typography.labelSmall, color = Muted,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }

    /** Decoded off the main thread; falls back to a type badge when nothing renders. */
    @Composable
    private fun Thumb(r: Recoverable, px: Int) {
        val context = LocalContext.current
        val bmp by produceState<Bitmap?>(null, r.uri, r.carved?.offset, px) {
            val source = uriFor(r) ?: return@produceState
            value = withContext(Dispatchers.IO) { loadThumb(context, source, r.name, px) }
        }
        bmp?.let {
            Image(
                it.asImageBitmap(), r.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: TypeBadge(r.name, if (px > 200) 20 else 10)
    }

    @Composable
    private fun TypeBadge(name: String, fontSize: Int) {
        val ext = name.substringAfterLast('.', "?").uppercase().take(5)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                ext, color = Muted, fontFamily = Mono,
                fontSize = fontSize.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}
