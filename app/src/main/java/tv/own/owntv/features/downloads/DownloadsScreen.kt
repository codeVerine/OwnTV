package tv.own.owntv.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.rememberInAppToast
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.OwnTVTheme

/** Phase 12 — the Downloads section: offline movies & episodes with progress and playback. */
@Composable
fun DownloadsScreen(
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: DownloadsViewModel = koinViewModel()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val lastPlayedId by vm.lastPlayedId.collectAsStateWithLifecycle()
    // Global external-player toggle: never mount the fullscreen in-app player (it spins up mpv)
    // when playback is handed to an external app.
    val externalPlayerOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors
    val context = LocalContext.current
    val bulkActionsToast = rememberInAppToast()
    val bulkActionsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var showClearCompleted by remember { mutableStateOf(false) }

    // Grouped rows (Active / Waiting / Completed / Failed) with section headers interleaved.
    val rows = remember(downloads) { buildDownloadRows(downloads) }
    val firstItemId = rows.firstNotNullOfOrNull { (it as? DownloadListRow.Item)?.download?.id }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // Per-row focus restore after a delete (mirrors Movies/Series context-menu restore). Track the
    // download the user acted on; if it's removed from the list, move focus to the nearest surviving
    // download row instead of letting it escape to the sidebar.
    var contextId by remember { mutableStateOf<Long?>(null) }
    var contextIndex by remember { mutableStateOf(-1) }
    val contextFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(rows) {
        val targetId = contextId ?: return@LaunchedEffect
        // The acted-on row's index inside `rows` (headers included). If it's still there, the row's
        // focusRequester keeps focus on it — nothing to do. Only act when it has vanished.
        val stillPresent = rows.any { it is DownloadListRow.Item && it.download.id == targetId }
        if (stillPresent) return@LaunchedEffect
        withFrameNanos { }
        // Find the nearest surviving ITEM row at/after the remembered slot, else the last item.
        val items = rows.filterIsInstance<DownloadListRow.Item>()
        if (items.isEmpty()) {
            contextId = null; contextIndex = -1
            runCatching { firstFocus.requestFocus() }
            return@LaunchedEffect
        }
        val neighbor = items.getOrNull(contextIndex.coerceAtLeast(0)) ?: items.last()
        contextId = neighbor.download.id
        val neighborIdx = rows.indexOfFirst { it is DownloadListRow.Item && it.download.id == neighbor.download.id }
        contextIndex = neighborIdx
        runCatching { listState.scrollToItem(neighborIdx.coerceAtLeast(0)) }
        withFrameNanos { }
        runCatching { contextFocus.requestFocus() }
    }
    // Returning from the player: scroll to and focus the download you just played (index within the
    // grouped rows, so headers don't throw the target off).
    LaunchedEffect(restoreFocus, rows.size) {
        if (!restoreFocus || downloads.isEmpty()) return@LaunchedEffect
        val idx = lastPlayedId?.let { id -> rows.indexOfFirst { it is DownloadListRow.Item && it.download.id == id } } ?: -1
        if (idx >= 0) {
            runCatching { listState.scrollToItem(idx) }
            kotlinx.coroutines.delay(60)
            runCatching { selFocus.requestFocus() }
        }
        onRestored()
    }

    Column(
        modifier = modifier.fillMaxSize().roundedPanel(fillColor = ContentPanelFill)
            // Route spatial D-pad entries to the first download row (entry from the sidebar would
            // otherwise land on whatever row is horizontally aligned). onEnter fires only for
            // directional entry from outside (internal moves don't re-trigger it).
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            // Held Up/Down can outrun the lazy list's composition and escape this pane
            // (landing on the top bar) — trap vertical exits; Left/Right/Back leave normally.
            .trapVerticalFocusExit()
            .focusGroup()
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.ScreenPaddingV),
    ) {
        Text(stringResource(R.string.content_downloads_title), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.content_downloads_description), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))

        storage?.let {
            StorageBar(it)
            Spacer(Modifier.height(16.dp))
        }

        // Bulk queue controls (group feedback: "manage downloads"). Only the relevant actions show:
        // Pause all while anything is queued/running, Resume all while something is parked, Clear
        // completed (destructive — confirmed) while anything completed exists.
        val hasActive = downloads.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
        val hasPaused = downloads.any { it.status == DownloadStatus.PAUSED }
        val hasCompleted = downloads.any { it.status == DownloadStatus.COMPLETED }
        if (hasActive || hasPaused || hasCompleted) {
            val clearCompletedMessage = context.getString(R.string.content_downloads_cleared, 0)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                if (hasActive) {
                    OwnTVButton(stringResource(R.string.content_downloads_pause_all), onClick = { vm.pauseAll() }, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(bulkActionsFocus))
                }
                if (hasPaused) {
                    OwnTVButton(
                        stringResource(R.string.content_downloads_resume_all),
                        onClick = { vm.resumeAll() },
                        style = OwnTVButtonStyle.SECONDARY,
                        modifier = if (!hasActive) Modifier.focusRequester(bulkActionsFocus) else Modifier,
                    )
                }
                if (hasCompleted) {
                    OwnTVButton(
                        stringResource(R.string.content_downloads_clear_completed),
                        onClick = { showClearCompleted = true },
                        style = OwnTVButtonStyle.SECONDARY,
                        modifier = if (!hasActive && !hasPaused) Modifier.focusRequester(bulkActionsFocus) else Modifier,
                    )
                }
            }
        }
        if (showClearCompleted) {
            BulkConfirmDialog(
                title = stringResource(R.string.content_downloads_clear_completed_title),
                message = stringResource(R.string.content_downloads_clear_completed_message),
                confirmLabel = stringResource(R.string.content_downloads_clear_completed),
                onConfirm = {
                    showClearCompleted = false
                    vm.deleteCompleted { count ->
                        bulkActionsToast.show(context.getString(R.string.content_downloads_cleared, count))
                    }
                },
                onDismiss = { showClearCompleted = false },
            )
        }

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.content_downloads_empty), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup()) {
                itemsIndexed(rows, key = { _, r -> r.key }) { index, r ->
                    when (r) {
                        is DownloadListRow.Header -> SectionHeader(r.group, r.count)
                        is DownloadListRow.Item -> {
                            val d = r.download
                            DownloadRow(
                                download = d,
                                focusModifier = when {
                                    d.id == contextId -> Modifier.focusRequester(contextFocus)
                                    d.id == lastPlayedId -> Modifier.focusRequester(selFocus)
                                    d.id == firstItemId -> Modifier.focusRequester(firstFocus)
                                    else -> Modifier
                                },
                                onPlay = { vm.play(d); if (!externalPlayerOn) onFullscreen() },
                                onPlayExternal = { vm.playExternal(d) },
                                onRetry = { vm.retry(d) },
                                onPause = { vm.pause(d) },
                                onResume = { vm.resume(d) },
                                // Capture the row's identity BEFORE the delete so the LaunchedEffect(rows)
                                // above can move focus to the nearest surviving neighbour.
                                onDelete = { contextId = d.id; contextIndex = index; vm.delete(d) },
                            )
                        }
                    }
                }
            }
        }
        InAppToast(bulkActionsToast)
    }
}

/**
 * Yes/No confirmation for the destructive bulk action (Clear completed). Mirrors the Customize
 * screen's confirm scrims so D-pad focus and Back behave the same way.
 */
@Composable
private fun BulkConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val confirmFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 480.dp, padding = 28.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
                Spacer(Modifier.weight(1f))
                OwnTVButton(confirmLabel, onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus))
            }
        }
    }
}

/** A display row in the grouped Downloads list: either a section header or a download. */
private enum class DownloadGroup(val labelRes: Int) {
    ACTIVE(R.string.content_downloads_active),
    WAITING(R.string.content_downloads_waiting),
    COMPLETED(R.string.content_downloads_completed_group),
    FAILED(R.string.content_downloads_failed_group),
}

private sealed interface DownloadListRow {
    val key: String
    data class Header(val group: DownloadGroup, val count: Int) : DownloadListRow {
        override val key get() = "hdr_$group"
    }
    data class Item(val download: DownloadEntity) : DownloadListRow {
        override val key get() = "d_${download.id}"
    }
}

/** Groups downloads into Active / Waiting / Completed / Failed with a header before each non-empty group. */
private fun buildDownloadRows(downloads: List<DownloadEntity>): List<DownloadListRow> {
    val active = downloads.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED }
    val waiting = downloads.filter { it.status == DownloadStatus.QUEUED }
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }
    return buildList {
        listOf(DownloadGroup.ACTIVE to active, DownloadGroup.WAITING to waiting, DownloadGroup.COMPLETED to completed, DownloadGroup.FAILED to failed).forEach { (group, list) ->
            if (list.isNotEmpty()) {
                add(DownloadListRow.Header(group, list.size))
                list.forEach { add(DownloadListRow.Item(it)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(group: DownloadGroup, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(stringResource(group.labelRes).uppercase(), style = MaterialTheme.typography.titleSmall, color = OwnTVTheme.colors.primary, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.content_downloads_count, count), style = MaterialTheme.typography.labelMedium, color = OwnTVTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun StorageBar(info: tv.own.owntv.core.download.DownloadStorageInfo) {
    val colors = OwnTVTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.content_downloads_storage), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.content_downloads_storage_free, storageSize(info.freeBytes, stringResource(R.string.content_downloads_unknown_size)), storageSize(info.totalBytes, stringResource(R.string.content_downloads_unknown_size))), style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceContainerLowest)) {
            Box(Modifier.fillMaxWidth(info.usedFraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.primary))
        }
    }
}

private fun storageSize(bytes: Long, unknown: String): String =
    if (bytes <= 0) unknown else java.text.NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(bytes / 1_073_741_824.0)

@Composable
private fun DownloadRow(
    download: DownloadEntity,
    onPlay: () -> Unit, onPlayExternal: () -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp, 78.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLowest), contentAlignment = Alignment.Center) {
            if (!download.posterUrl.isNullOrBlank()) AsyncImage(model = download.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            else OwnTVIcon(OwnTVIcon.MOVIES, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(download.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            folderCrumb(download.filePath, stringResource(R.string.content_downloads_folder_separator))?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            StatusLine(download)
        }
        Spacer(Modifier.width(12.dp))
        when (download.status) {
            DownloadStatus.COMPLETED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OwnTVButton(stringResource(R.string.content_downloads_play), onClick = onPlay, icon = OwnTVIcon.PLAY, modifier = focusModifier)
                // Phase B: one-off external playback, independent of the global "External player" toggle.
                OwnTVButton(stringResource(R.string.content_downloads_external), onClick = onPlayExternal, style = OwnTVButtonStyle.SECONDARY)
            }
            DownloadStatus.FAILED -> OwnTVButton(stringResource(R.string.common_retry), onClick = onRetry, style = OwnTVButtonStyle.SECONDARY, modifier = focusModifier)
            DownloadStatus.PAUSED -> OwnTVButton(stringResource(R.string.common_resume), onClick = onResume, style = OwnTVButtonStyle.SECONDARY, modifier = focusModifier)
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> OwnTVButton(stringResource(R.string.content_downloads_pause), onClick = onPause, style = OwnTVButtonStyle.SECONDARY, modifier = focusModifier)
        }
        Spacer(Modifier.width(10.dp))
        OwnTVButton(stringResource(R.string.common_delete), onClick = onDelete, style = OwnTVButtonStyle.SECONDARY)
    }
}

/** Shows the folder path of a download, e.g. "Series › Game of Thrones › Season 6". */
private fun folderCrumb(filePath: String?, separator: String): String? {
    val parts = filePath?.substringBeforeLast('/')?.split('/')?.filter { it.isNotBlank() } ?: return null
    val idx = parts.indexOfLast { it == "Movies" || it == "Series" }
    val rel = if (idx >= 0) parts.subList(idx, parts.size) else parts.takeLast(3)
    return rel.joinToString(separator).ifBlank { null }
}

@Composable
private fun StatusLine(d: DownloadEntity) {
    val colors = OwnTVTheme.colors
    when (d.status) {
        DownloadStatus.COMPLETED -> Text(stringResource(R.string.content_downloads_completed, sizeMb(d.totalBytes, stringResource(R.string.content_downloads_unknown_size))), style = MaterialTheme.typography.bodySmall, color = colors.primary, fontWeight = FontWeight.SemiBold)
        DownloadStatus.FAILED -> Text(stringResource(R.string.content_downloads_failure_message), style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF4444))
        DownloadStatus.QUEUED -> Text(stringResource(R.string.content_downloads_queued), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        else -> { // RUNNING / PAUSED
            val frac = if (d.totalBytes > 0) (d.downloadedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else 0f
            Column {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.surfaceContainerLowest)) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.primary))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (d.totalBytes > 0) stringResource(R.string.content_downloads_progress, (frac * 100).toInt(), sizeMb(d.downloadedBytes, stringResource(R.string.content_downloads_unknown_size)), sizeMb(d.totalBytes, stringResource(R.string.content_downloads_unknown_size)))
                    else stringResource(R.string.content_downloads_progress_unknown, sizeMb(d.downloadedBytes, stringResource(R.string.content_downloads_unknown_size))),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sizeMb(bytes: Long, unknown: String): String =
    if (bytes <= 0) unknown else java.text.NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(bytes / 1_048_576.0)
