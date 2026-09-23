package tv.own.owntv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.download.DownloadStripKind
import tv.own.owntv.core.download.DownloadStripState
import tv.own.owntv.ui.theme.OwnTVTheme

/** A compact, non-focusable status strip: icon + label + a thin progress bar. */
@Composable
fun DownloadStatusStrip(state: DownloadStripState, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val accent = if (state.isError) Color(0xFFEF4444) else colors.primary
    val label = when (state.kind) {
        DownloadStripKind.DOWNLOADING -> pluralStringResource(R.plurals.content_downloading_items, state.count, state.count)
        DownloadStripKind.QUEUED -> pluralStringResource(R.plurals.content_queued_items, state.count, state.count)
        DownloadStripKind.PAUSED -> pluralStringResource(R.plurals.content_paused_items, state.count, state.count)
        DownloadStripKind.FAILED -> pluralStringResource(R.plurals.content_downloads_failed, state.count, state.count)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnTVIcon(OwnTVIcon.DOWNLOADS, tint = accent, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            state.progress?.let {
                Text(stringResource(R.string.content_progress_percent, (it * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!state.isError) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.surfaceContainerLowest)) {
                // Determinate when a size is known; otherwise a modest fixed sliver so the user still
                // sees an active bar for a queued item.
                Box(Modifier.fillMaxWidth(state.progress ?: 0.15f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            }
        }
    }
}
