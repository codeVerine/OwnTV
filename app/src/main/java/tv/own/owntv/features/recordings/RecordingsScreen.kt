package tv.own.owntv.features.recordings

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.recording.RecordingRules
import tv.own.owntv.core.recording.RecordingStorageInfo
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.format.formatBestDateTime
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.OwnTVTheme
import java.text.NumberFormat
import java.util.Locale

/**
 * Everything the recorder has done, is doing, or could not do.
 *
 * Five groups rather than the four Downloads has, and the fifth is the one that matters: **Missed**.
 * A download that fails can be tried again tomorrow; a live programme that was not recorded is gone,
 * so "it did not happen, and here is why" is a result the user has to be told rather than an error
 * to retry. That is why every missed and failed row carries its reason in words.
 */
@Composable
fun RecordingsScreen(
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Draw only the list. Downloads hosts this as its **Live TV** tab and already owns the panel,
     * the title, the storage bar and the folder button — a recording is a download of a live
     * channel, so it shares that screen's chrome rather than repeating it inside it.
     */
    embedded: Boolean = false,
    vm: RecordingsViewModel = koinViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val externalPlayerOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val listRows = remember(rows) { buildRecordingRows(rows) }
    val firstItemId = listRows.firstNotNullOfOrNull { (it as? RecordingListRow.Item)?.recording?.id }

    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val contextFocus = remember { FocusRequester() }

    // Acting on a row can remove it — stopping a recording moves it out of "Recording now", deleting
    // takes it away entirely. Without this, focus escapes to the sidebar every time. Same shape as
    // the Downloads screen's restore.
    var contextId by remember { mutableStateOf<Long?>(null) }
    var contextIndex by remember { mutableStateOf(-1) }
    // The status the acted-on row had when it was acted on. Stop does not remove a row — it moves it
    // from "Recording now" to "Recorded" — so the restore below never ran, while the button that had
    // focus (Stop) was replaced by different ones (Play, Delete) and focus went with it. Comparing
    // the status is what tells the two cases apart: the row survived, but not the thing focused.
    var contextStatus by remember { mutableStateOf<RecordingStatus?>(null) }
    LaunchedEffect(listRows) {
        val target = contextId ?: return@LaunchedEffect
        val items = listRows.filterIsInstance<RecordingListRow.Item>()
        val survivor = items.firstOrNull { it.recording.id == target }
        if (survivor != null) {
            // Only when its buttons have actually changed — the list also updates as bytes arrive,
            // and re-grabbing focus on every tick would drag the user back here mid-navigation.
            if (survivor.recording.status != contextStatus) {
                contextStatus = survivor.recording.status
                withFrameNanos { }
                runCatching { contextFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (items.isEmpty()) {
            contextId = null
            contextIndex = -1
            runCatching { firstFocus.requestFocus() }
            return@LaunchedEffect
        }
        val neighbour = items.getOrNull(contextIndex.coerceAtLeast(0)) ?: items.last()
        contextId = neighbour.recording.id
        contextIndex = listRows.indexOfFirst {
            it is RecordingListRow.Item && it.recording.id == neighbour.recording.id
        }
        runCatching { listState.scrollToItem(contextIndex.coerceAtLeast(0)) }
        withFrameNanos { }
        runCatching { contextFocus.requestFocus() }
    }

    // Arriving on the screen has to land focus on the first row itself. The `onEnter` below only
    // fires when focus *moves into* the group, and opening this from More gives it nothing to move
    // from — so the screen opened with nothing focused at all and the first OK press went nowhere,
    // until a stray RIGHT happened to put focus somewhere. Waits a frame so the row exists to take
    // it, and stands aside once a row has been acted on, which the restore above already owns.
    // Embedded, the host owns arrival focus — it lands on its tab strip, and grabbing focus down
    // into this list from here would pull the user past the tabs and the folder button every time.
    LaunchedEffect(firstItemId, embedded) {
        if (embedded || firstItemId == null || contextId != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.roundedPanel(fillColor = ContentPanelFill))
            // Both of these belong to whoever owns the screen, and embedded that is Downloads. Kept
            // here they made a second trap inside the host's: once focus was in this list, Up could
            // not climb back out to the tab strip or the folder button — the list became a dead end.
            .then(
                if (embedded) {
                    Modifier
                } else {
                    Modifier
                        .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
                        .trapVerticalFocusExit()
                },
            )
            .focusGroup()
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .then(
                if (embedded) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.ScreenPaddingV)
                },
            ),
    ) {
        if (!embedded) {
            Text(
                stringResource(R.string.recording_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.recording_description),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            storage?.let {
                StorageBar(it)
                Spacer(Modifier.height(16.dp))
            }
        }

        if (listRows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.recording_empty),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.focusGroup(),
            ) {
                itemsIndexed(listRows, key = { _, r -> r.key }) { index, r ->
                    when (r) {
                        is RecordingListRow.Header -> SectionHeader(r.group, r.count)
                        is RecordingListRow.Item -> {
                            val recording = r.recording
                            RecordingRow(
                                recording = recording,
                                focusModifier = when {
                                    recording.id == contextId -> Modifier.focusRequester(contextFocus)
                                    recording.id == firstItemId -> Modifier.focusRequester(firstFocus)
                                    else -> Modifier
                                },
                                onPlay = { vm.play(recording); if (!externalPlayerOn) onFullscreen() },
                                onStop = { contextId = recording.id; contextIndex = index; contextStatus = recording.status; vm.stop(recording) },
                                onCancel = { contextId = recording.id; contextIndex = index; contextStatus = recording.status; vm.cancel(recording) },
                                onRetry = { contextId = recording.id; contextIndex = index; contextStatus = recording.status; vm.retry(recording) },
                                onDelete = { contextId = recording.id; contextIndex = index; contextStatus = recording.status; vm.delete(recording) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Back returns to the More hub rather than to the rail, the same way every other More page does.
    // Not when embedded: as a tab inside Downloads there is no page of its own to leave, and taking
    // Back here swallowed it — the remote's Back key did nothing at all on the whole screen.
    if (!embedded) androidx.activity.compose.BackHandler(onBack = onBack)
}

/** The five groups, in the order the screen shows them: most urgent first. */
private enum class RecordingGroup(val labelRes: Int) {
    NOW(R.string.recording_group_now),
    SCHEDULED(R.string.recording_group_scheduled),
    COMPLETED(R.string.recording_group_completed),
    FAILED(R.string.recording_group_failed),
    MISSED(R.string.recording_group_missed),
}

private sealed interface RecordingListRow {
    val key: String

    data class Header(val group: RecordingGroup, val count: Int) : RecordingListRow {
        override val key get() = "rhdr_$group"
    }

    data class Item(val recording: RecordingEntity) : RecordingListRow {
        override val key get() = "r_${recording.id}"
    }
}

/**
 * Group the rows, with a header before each group that has anything in it.
 *
 * Cancelled recordings are not shown at all. The user cancelled it; a list that keeps reminding them
 * of something they deliberately called off is a list they stop reading.
 */
private fun buildRecordingRows(recordings: List<RecordingEntity>): List<RecordingListRow> {
    val now = recordings.filter { it.status == RecordingStatus.RECORDING }
    // Soonest first: a scheduled list is read forwards, unlike the finished ones.
    val scheduled = recordings.filter { it.status == RecordingStatus.SCHEDULED }.sortedBy { it.startMs }
    val completed = recordings.filter { it.status == RecordingStatus.COMPLETED }
    val failed = recordings.filter { it.status == RecordingStatus.FAILED }
    val missed = recordings.filter { it.status == RecordingStatus.MISSED }
    return buildList {
        listOf(
            RecordingGroup.NOW to now,
            RecordingGroup.SCHEDULED to scheduled,
            RecordingGroup.COMPLETED to completed,
            RecordingGroup.FAILED to failed,
            RecordingGroup.MISSED to missed,
        ).forEach { (group, list) ->
            if (list.isNotEmpty()) {
                add(RecordingListRow.Header(group, list.size))
                list.forEach { add(RecordingListRow.Item(it)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(group: RecordingGroup, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            stringResource(group.labelRes).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = OwnTVTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.content_downloads_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = OwnTVTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingEntity,
    focusModifier: Modifier,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp, 78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            if (!recording.channelIconUrl.isNullOrBlank()) {
                AsyncImage(model = recording.channelIconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                OwnTVIcon(OwnTVIcon.LIVE_TV, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                recording.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recording.channelName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            StatusLine(recording)
            // The whole path, not a crumb. "TV" says which folder inside the download root; it does
            // not say which disk, and on a television with an internal drive and a USB stick that is
            // the only part worth reading. Two lines so a long path is shown rather than clipped.
            recording.filePath?.takeIf { it.isNotBlank() }?.let { path ->
                Spacer(Modifier.height(2.dp))
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // One primary action per state, and never more than the state can honestly offer.
        when (recording.status) {
            // No icon: it sits next to Delete, and the glyph made Play the wider of the two for no
            // reason a viewer could name.
            RecordingStatus.COMPLETED -> OwnTVButton(
                stringResource(R.string.content_downloads_play),
                onClick = onPlay,
                modifier = focusModifier,
            )
            RecordingStatus.RECORDING -> OwnTVButton(
                stringResource(R.string.recording_stop),
                onClick = onStop,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = focusModifier,
            )
            RecordingStatus.SCHEDULED -> OwnTVButton(
                stringResource(R.string.common_cancel),
                onClick = onCancel,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = focusModifier,
            )
            // A missed or failed recording can only be retried while its window is somehow still
            // open, which for a live programme is rare — but the row must not be a dead end.
            RecordingStatus.FAILED, RecordingStatus.MISSED -> OwnTVButton(
                stringResource(R.string.common_retry),
                onClick = onRetry,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = focusModifier,
            )
            RecordingStatus.CANCELLED -> Unit
        }
        // Nothing to delete while it is only scheduled: there is no file yet, and Cancel is the
        // action that means "do not do this".
        if (recording.status != RecordingStatus.SCHEDULED) {
            Spacer(Modifier.width(10.dp))
            OwnTVButton(
                stringResource(R.string.common_delete),
                onClick = onDelete,
                style = OwnTVButtonStyle.SECONDARY,
            )
        }
    }
}

/** The line that says where this recording got to, or why it never did. */
@Composable
private fun StatusLine(recording: RecordingEntity) {
    val colors = OwnTVTheme.colors
    val context = LocalContext.current
    when (recording.status) {
        // With the size as it grows. A live recording has no known total to show a percentage
        // against — it ends when the programme does — so the bytes on disk are the only honest sign
        // that it is actually moving, which is the whole reason for showing them.
        RecordingStatus.RECORDING -> Text(
            if (recording.bytes > 0) {
                stringResource(R.string.recording_group_now) +
                    stringResource(R.string.content_epg_bits_separator) +
                    stringResource(R.string.common_size_mb, sizeMb(recording.bytes))
            } else {
                stringResource(R.string.recording_group_now)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.primary,
            fontWeight = FontWeight.SemiBold,
        )
        RecordingStatus.SCHEDULED -> Text(
            stringResource(R.string.recording_scheduled_for, whenText(recording.startMs)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        // How long it runs for *and* how big it is. The length is what the programme was; the size is
        // what it cost, and on a 48 GB stick that is the number that decides what gets deleted next.
        RecordingStatus.COMPLETED -> Text(
            buildList {
                add(
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.recording_minutes,
                        recordedMinutes(recording),
                        recordedMinutes(recording),
                    ),
                )
                if (recording.bytes > 0) add(stringResource(R.string.common_size_mb, sizeMb(recording.bytes)))
            }.joinToString(stringResource(R.string.content_epg_bits_separator)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.primary,
            fontWeight = FontWeight.SemiBold,
        )
        // The whole point of the Missed group: the reason, in words, not a code.
        RecordingStatus.FAILED, RecordingStatus.MISSED -> Text(
            RecordingRules.displayTextOf(recording.failure, context.resources)
                ?: stringResource(R.string.recording_failed_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFEF4444),
        )
        RecordingStatus.CANCELLED -> Unit
    }
}

/** Megabytes to one decimal, formatted for the locale. Same shape as the Downloads screen's. */
private fun sizeMb(bytes: Long): String =
    java.text.NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(bytes / 1_048_576.0)

@Composable
private fun StorageBar(info: RecordingStorageInfo) {
    val colors = OwnTVTheme.colors
    val used = (info.totalBytes - info.freeBytes).coerceAtLeast(0L)
    val fraction = if (info.totalBytes > 0) (used.toFloat() / info.totalBytes).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.content_downloads_storage),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(
                    R.string.content_downloads_storage_free,
                    gigabytes(info.freeBytes),
                    gigabytes(info.totalBytes),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.surfaceContainerLowest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.primary),
            )
        }
    }
}

/**
 * When a scheduled recording starts — day, date and time.
 *
 * A skeleton through the app's own formatter rather than a date and a time glued together: the order
 * of the parts, the separator and whether the clock is 12- or 24-hour are all decided by the user's
 * locale, and concatenating them here would put the time in the wrong place in several of the
 * languages OwnTV ships.
 */
@Composable
private fun whenText(atMs: Long): String =
    formatBestDateTime(LocalContext.current, "EEEdMMMjm", atMs)

/**
 * How many minutes were actually captured — from the clock where the recorder wrote one, and from
 * the programme's own length where it did not. Never zero: a recording that exists ran for some part
 * of a minute, and "0 minutes" would read as a failure that it is not.
 */
private fun recordedMinutes(recording: RecordingEntity): Int {
    val started = recording.startedAt
    val ended = recording.endedAt
    val span = if (started != null && ended != null && ended > started) {
        ended - started
    } else {
        recording.programmeStopMs - recording.programmeStartMs
    }
    return (span / 60_000L).toInt().coerceAtLeast(1)
}

private fun decimal(value: Double): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)

private fun gigabytes(bytes: Long): String = decimal(bytes.coerceAtLeast(0) / 1_073_741_824.0)
