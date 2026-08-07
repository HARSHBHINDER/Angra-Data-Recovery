package io.angra.salvage

import android.app.Activity
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

data class Recoverable(val uri: Uri, val name: String, val sizeBytes: Long, val path: String)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    /** One cursor walk for both scans — they differ only in the query args. */
    private suspend fun query(args: Bundle): List<Recoverable> = withContext(Dispatchers.IO) {
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
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, c.getLong(0)),
                        c.getString(1) ?: "(unnamed)",
                        c.getLong(2),
                        c.getString(3) ?: "—"
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
        })
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
        })
    }

    /** Untrash. Android returns an IntentSender so the *user* grants the write, not us. */
    private fun restoreRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty())
            MediaStore.createTrashRequest(contentResolver, uris, false).intentSender
        else null

    @Composable
    private fun App() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Jade, background = Ink, surface = Panel,
                onPrimary = Ink, onBackground = Jade, onSurface = Jade
            )
        ) {
            val scope = rememberCoroutineScope()
            var trashed by remember { mutableStateOf<List<Recoverable>>(emptyList()) }
            var stranded by remember { mutableStateOf<List<Recoverable>>(emptyList()) }
            var scanning by remember { mutableStateOf(false) }
            var scanned by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            val selected = remember { mutableStateListOf<Uri>() }

            val perms = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { res ->
                if (res.resultCode == Activity.RESULT_OK) {
                    status = "RESTORED ${selected.size} item(s) to gallery"
                    trashed = trashed.filterNot { it.uri in selected }
                    selected.clear()
                } else status = "restore cancelled"
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
                    label = if (scanning) "SCANNING…" else "▸  BEGIN SCAN",
                    filled = false,
                    alpha = if (scanning) pulse else 1f,
                    enabled = !scanning
                ) {
                    scanning = true; status = ""
                    scope.launch {
                        trashed = scanTrashed()
                        stranded = scanStranded()
                        scanning = false; scanned = true
                        status = "${trashed.size} recoverable · ${stranded.size} stranded"
                    }
                }

                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        status.uppercase(), color = Amber, fontFamily = Mono,
                        fontSize = 11.sp, letterSpacing = 1.5.sp
                    )
                }

                if (selected.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Bar("RESTORE ${selected.size} SELECTED", filled = true) {
                        restoreRequest(selected.toList())?.let {
                            restoreLauncher.launch(IntentSenderRequest.Builder(it).build())
                        } ?: run { status = "needs Android 11+" }
                    }
                }

                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trashed.isNotEmpty()) {
                        item { SectionLabel("RECOVERABLE", "restorable to gallery", Jade) }
                        items(trashed) { r ->
                            FileRow(r, r.uri in selected, Jade) {
                                if (r.uri in selected) selected.remove(r.uri)
                                else selected.add(r.uri)
                            }
                        }
                    }
                    if (stranded.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            SectionLabel("STRANDED", "on disk, hidden from gallery", Amber)
                        }
                        items(stranded) { FileRow(it, false, Amber, null) }
                    }
                    if (scanned && trashed.isEmpty() && stranded.isEmpty()) {
                        item {
                            Text(
                                "\nNOTHING FOUND — no trashed or stranded media on this device.",
                                color = Dim, fontFamily = Mono, fontSize = 11.sp
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Recovers media Android still holds: trashed items (30–60 day " +
                                "window) and stranded files hidden from your gallery. Files " +
                                "erased from encrypted internal storage are gone at the " +
                                "hardware level — no app recovers those. Fully offline, no " +
                                "network permission.",
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
                .height(if (filled) 48.dp else 60.dp)
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
                fontSize = if (filled) 13.sp else 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (filled) 2.sp else 3.sp,
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
    private fun FileRow(r: Recoverable, sel: Boolean, tint: Color, onClick: (() -> Unit)?) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (sel) tint.copy(alpha = 0.12f) else Panel, RoundedCornerShape(3.dp))
                .border(1.dp, if (sel) tint else Line, RoundedCornerShape(3.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onClick != null) {
                Text(if (sel) "◉" else "○", color = tint, fontFamily = Mono, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
            }
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
