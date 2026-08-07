package io.angra.salvage

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF07090C)
private val Panel = Color(0xFF0E141B)
private val Line = Color(0xFF1D2A36)
private val Jade = Color(0xFF3DFFA8)
private val Amber = Color(0xFFFFC24B)
private val Dim = Color(0xFF7E93A6)
private val Mono = FontFamily.Monospace

enum class Kind { TRASHED, STRANDED, FOLDER }

data class Recoverable(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val path: String,
    val kind: Kind
)

/** Directory or filename markers that mean "on disk but hidden from the user". */
private val HIDDEN_MARKERS = listOf(
    ".statuses", ".thumbnails", ".trashed", ".nomedia", "cache", "recycle", ".pending"
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    // ---------- MediaStore layers ----------

    /** One cursor walk for both MediaStore scans — they differ only in the query args. */
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
            // Raise it when someone actually hits it with a real card.
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
            // Hidden/orphaned files first — those are what someone scanning a card wants.
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
    private suspend fun export(
        items: List<Recoverable>,
        dest: Uri,
        sourceTree: Uri?
    ): String = withContext(Dispatchers.IO) {
        if (sourceTree != null && sameVolume(dest, sourceTree)) {
            return@withContext "REFUSED — pick a destination on a different volume"
        }
        val destDir = DocumentsContract.buildDocumentUriUsingTree(
            dest, DocumentsContract.getTreeDocumentId(dest)
        )
        var ok = 0
        var failed = 0
        for (r in items) {
            val wrote = runCatching {
                val target = DocumentsContract.createDocument(
                    contentResolver, destDir, "application/octet-stream", r.name
                ) ?: return@runCatching false
                contentResolver.openInputStream(r.uri)?.use { input ->
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

            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Ink, Color(0xFF0A1017), Ink)))
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    "ANGRA", color = Jade, fontFamily = Mono, fontSize = 34.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 8.sp
                )
                Text(
                    "DATA RECOVERY", color = Dim, fontFamily = Mono,
                    fontSize = 12.sp, letterSpacing = 6.sp
                )
                Spacer(Modifier.height(28.dp))

                val pulse by rememberInfiniteTransition(label = "p").animateFloat(
                    0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pa"
                )

                Bar(
                    label = if (scanning) "SCANNING…" else "▸  SCAN THIS PHONE",
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
                Bar("▸  SCAN SD CARD / USB / FOLDER", filled = false, enabled = !scanning) {
                    folderPicker.launch(null)
                }

                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        status.uppercase(), color = Amber, fontFamily = Mono,
                        fontSize = 11.sp, letterSpacing = 1.5.sp
                    )
                }

                if (selected.isNotEmpty()) {
                    val restorable = found.any { it.uri in selected && it.kind == Kind.TRASHED }
                    Spacer(Modifier.height(14.dp))
                    if (restorable) {
                        Bar("RESTORE ${selected.size} TO GALLERY", filled = true) {
                            restoreRequest(selected.toList())?.let {
                                restoreLauncher.launch(IntentSenderRequest.Builder(it).build())
                            } ?: run { status = "needs Android 11+" }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Bar("EXPORT ${selected.size} TO FOLDER…", filled = true) {
                        destPicker.launch(null)
                    }
                }

                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val groups = found.groupBy { it.kind }
                    listOf(
                        Triple(Kind.TRASHED, "RECOVERABLE", "restorable to gallery"),
                        Triple(Kind.FOLDER, "IN FOLDER", "every file type, hidden ones first"),
                        Triple(Kind.STRANDED, "STRANDED", "on disk, hidden from gallery")
                    ).forEach { (kind, title, sub) ->
                        val list = groups[kind].orEmpty()
                        if (list.isEmpty()) return@forEach
                        val tint = if (kind == Kind.STRANDED) Amber else Jade
                        item { SectionLabel("$title (${list.size})", sub, tint) }
                        items(list) { r ->
                            FileRow(r, r.uri in selected, tint) {
                                if (r.uri in selected) selected.remove(r.uri)
                                else selected.add(r.uri)
                            }
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                    if (scanned && found.isEmpty()) {
                        item {
                            Text(
                                "\nNOTHING FOUND — no trashed, stranded, or folder files here.",
                                color = Dim, fontFamily = Mono, fontSize = 11.sp
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Scans this phone for trashed and stranded media, and any folder, " +
                                "SD card or USB stick for every file type. Export writes to a " +
                                "different volume on purpose — writing back to the source can " +
                                "overwrite what is still recoverable. Files erased from " +
                                "encrypted internal storage are gone at the hardware level; no " +
                                "app recovers those. Fully offline, no network permission.",
                            color = Dim.copy(alpha = 0.55f), fontFamily = Mono,
                            fontSize = 9.sp, lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
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
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(
                title, color = tint, fontFamily = Mono, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp
            )
            Text(sub, color = Dim, fontFamily = Mono, fontSize = 10.sp)
        }
    }

    @Composable
    private fun FileRow(r: Recoverable, sel: Boolean, tint: Color, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (sel) tint.copy(alpha = 0.12f) else Panel, RoundedCornerShape(3.dp))
                .border(1.dp, if (sel) tint else Line, RoundedCornerShape(3.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (sel) "◉" else "○", color = tint, fontFamily = Mono, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.name, color = Color(0xFFD6E4EF), fontFamily = Mono, fontSize = 12.sp,
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
}
