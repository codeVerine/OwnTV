package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * TMDB content languages (ISO 639-1, region-qualified where TMDB's coverage is meaningfully better for
 * one — e.g. pt-BR). "" keeps TMDB's own default (en-US), which is what installs used before this setting
 * existed, so an upgrade never silently changes anyone's metadata.
 *
 * Distinct from VideoPlayerSettingsScreen's LANGUAGES list, which uses 3-letter codes for audio/subtitle
 * track matching — TMDB only accepts 2-letter tags.
 */
private val TMDB_LANGUAGE_CODES = listOf(
    "", MetadataConfig.LANGUAGE_AUTO, "ar", "bg", "zh", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "he", "hi", "hu", "id", "it", "ja", "ko", "lv", "lt", "ms", "no", "fa", "pl", "pt-BR", "pt-PT", "ro", "ru", "sr", "sk", "sl", "es", "es-MX", "sv", "th", "tr", "uk", "vi",
)

@Composable
private fun tmdbLangName(code: String): String = stringResource(
    when (code) {
        "" -> R.string.settings_language_default
        MetadataConfig.LANGUAGE_AUTO -> R.string.settings_language_device
        "ar" -> R.string.settings_language_arabic
        "bg" -> R.string.settings_language_bulgarian
        "zh" -> R.string.settings_language_chinese
        "hr" -> R.string.settings_language_croatian
        "cs" -> R.string.settings_language_czech
        "da" -> R.string.settings_language_danish
        "nl" -> R.string.settings_language_dutch
        "en" -> R.string.settings_language_english
        "et" -> R.string.settings_language_estonian
        "fi" -> R.string.settings_language_finnish
        "fr" -> R.string.settings_language_french
        "de" -> R.string.settings_language_german
        "el" -> R.string.settings_language_greek
        "he" -> R.string.settings_language_hebrew
        "hi" -> R.string.settings_language_hindi
        "hu" -> R.string.settings_language_hungarian
        "id" -> R.string.settings_language_indonesian
        "it" -> R.string.settings_language_italian
        "ja" -> R.string.settings_language_japanese
        "ko" -> R.string.settings_language_korean
        "lv" -> R.string.settings_language_latvian
        "lt" -> R.string.settings_language_lithuanian
        "ms" -> R.string.settings_language_malay
        "no" -> R.string.settings_language_norwegian
        "fa" -> R.string.settings_language_persian
        "pl" -> R.string.settings_language_polish
        "pt-BR" -> R.string.settings_language_portuguese_brazil
        "pt-PT" -> R.string.settings_language_portuguese_portugal
        "ro" -> R.string.settings_language_romanian
        "ru" -> R.string.settings_language_russian
        "sr" -> R.string.settings_language_serbian
        "sk" -> R.string.settings_language_slovak
        "sl" -> R.string.settings_language_slovenian
        "es" -> R.string.settings_language_spanish
        "es-MX" -> R.string.settings_language_spanish_latam
        "sv" -> R.string.settings_language_swedish
        "th" -> R.string.settings_language_thai
        "tr" -> R.string.settings_language_turkish
        "uk" -> R.string.settings_language_ukrainian
        "vi" -> R.string.settings_language_vietnamese
        else -> R.string.settings_language_default
    },
)

/**
 * Settings → Metadata (TMDB): the metadata mode, two advanced access tiers (own TMDB key / self-host URL),
 * and a manual "look up title" test that proves the configured tier reaches TMDB end-to-end. Detail, Home,
 * and Android TV Watch Next surfaces resolve metadata lazily when they need it.
 *
 * Precedence (plan §4): self-host URL > own key > the default caching Worker (zero setup).
 */
private fun metadataModeLabelRes(mode: tv.own.owntv.core.metadata.MetadataMode): Int = when (mode) {
    tv.own.owntv.core.metadata.MetadataMode.PROVIDER -> R.string.settings_metadata_provider_only
    tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB -> R.string.settings_metadata_provider_plus_tmdb
    tv.own.owntv.core.metadata.MetadataMode.TMDB_ONLY -> R.string.settings_metadata_tmdb_only
}

private fun metadataTierLabelRes(tier: tv.own.owntv.core.metadata.MetadataConfig.Tier): Int = when (tier) {
    tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER -> R.string.settings_tier_default
    tv.own.owntv.core.metadata.MetadataConfig.Tier.OWN_KEY -> R.string.settings_tier_key
    tv.own.owntv.core.metadata.MetadataConfig.Tier.SELF_HOST -> R.string.settings_tier_self_host
}

@Composable
fun MetadataSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val vm: SettingsViewModel = koinViewModel()
    val mode by vm.metadataMode.collectAsStateWithLifecycle()
    val storedKey by vm.tmdbApiKey.collectAsStateWithLifecycle()
    val storedUrl by vm.metadataServerUrl.collectAsStateWithLifecycle()
    val tier by vm.metadataTier.collectAsStateWithLifecycle()
    val testState by vm.metadataTest.collectAsStateWithLifecycle()
    val language by vm.metadataLanguage.collectAsStateWithLifecycle()

    var showLangPicker by remember { mutableStateOf(false) }
    var langPickerWasOpen by remember { mutableStateOf(false) }
    val langRowFocus = remember { FocusRequester() }

    // Seed the editable fields once; local edit → Save persists (same pattern as NetworkSettingsScreen).
    var seeded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val defaultTestTitle = stringResource(R.string.settings_metadata_test_title)
    var testTitle by remember(defaultTestTitle) { mutableStateOf(defaultTestTitle) }
    // Advanced options are hidden by default. Auto-expand if the user already has a key/URL saved, so the
    // fields aren't silently hidden when they're actually in use.
    var showAdvanced by remember { mutableStateOf(false) }
    LaunchedEffect(storedKey, storedUrl) {
        if (!seeded) {
            key = storedKey; url = storedUrl
            if (storedKey.isNotBlank() || storedUrl.isNotBlank()) showAdvanced = true
            seeded = true
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            // onEnter + focusGroup: a safety net for two dispose-on-collapse paths — (1) toggling
            // "Advanced options" off while focus is on a field inside it, and (2) switching Metadata
            // mode to PROVIDER (mode.enrich=false), which disposes the whole advanced block + the row
            // the user clicked. Either path leaves focus dangling; onEnter recaptures it onto the
            // always-composed first mode row whenever directional focus re-enters the group.
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Header(stringResource(R.string.settings_metadata), onBack)
        Spacer(Modifier.height(8.dp))

        GroupLabel(stringResource(R.string.settings_metadata_source))
        Text(
            stringResource(R.string.settings_metadata_source_description),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        tv.own.owntv.core.metadata.MetadataMode.entries.forEachIndexed { i, m ->
            val selected = m == mode
            Row2(
                icon = if (m == tv.own.owntv.core.metadata.MetadataMode.PROVIDER) OwnTVIcon.PLAYLIST else OwnTVIcon.VIDEO,
                title = stringResource(metadataModeLabelRes(m)),
                desc = when (m) {
                    tv.own.owntv.core.metadata.MetadataMode.PROVIDER -> stringResource(R.string.settings_metadata_provider_description)
                    tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB -> stringResource(R.string.settings_metadata_provider_tmdb_description)
                    tv.own.owntv.core.metadata.MetadataMode.TMDB_ONLY -> stringResource(R.string.settings_metadata_tmdb_only_description)
                },
                chip = if (selected) stringResource(R.string.settings_selected) else null, primaryChip = selected,
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = { vm.setMetadataMode(m); vm.resetMetadataTest() },
            )
        }

        // The advanced TMDB tier fields only make sense when TMDB is on (mode != Provider).
        if (mode.enrich) {
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_active_tmdb_source, stringResource(metadataTierLabelRes(tier))),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
        )

        Spacer(Modifier.height(16.dp))
        Row2(
            icon = OwnTVIcon.SUBTITLE,
            title = stringResource(R.string.settings_metadata_language),
            desc = stringResource(R.string.settings_metadata_language_description),
            chip = tmdbLangName(language), chevron = true,
            modifier = Modifier.focusRequester(langRowFocus),
            onClick = { showLangPicker = true },
        )

        Spacer(Modifier.height(16.dp))
        Row2(
            icon = OwnTVIcon.SETTINGS,
            title = stringResource(R.string.settings_advanced_options),
            desc = stringResource(R.string.settings_advanced_metadata_description),
            chip = if (showAdvanced) stringResource(R.string.common_on) else stringResource(R.string.common_off), primaryChip = showAdvanced,
            onClick = { showAdvanced = !showAdvanced },
        )
        if (showAdvanced) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_metadata_server_description),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OwnTVTextField(
                value = key,
                onValueChange = { key = it },
                label = stringResource(R.string.settings_tmdb_api_key),
                placeholder = stringResource(R.string.settings_metadata_optional),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OwnTVTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.settings_self_host_url),
                placeholder = "https://your-worker.example.workers.dev",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OwnTVButton(stringResource(R.string.common_save), onClick = {
                vm.setTmdbApiKey(key)
                vm.setMetadataServerUrl(url)
                vm.resetMetadataTest()
            })
        }

        Spacer(Modifier.height(20.dp))
        GroupLabel(stringResource(R.string.settings_test))
        OwnTVTextField(
            value = testTitle,
            onValueChange = { testTitle = it },
            label = stringResource(R.string.settings_lookup_movie),
            placeholder = stringResource(R.string.settings_metadata_test_title),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OwnTVButton(
                label = if (testState is SettingsViewModel.MetadataTestState.Testing) stringResource(R.string.settings_looking_up) else stringResource(R.string.settings_test_lookup),
                onClick = { vm.testMetadataLookup(testTitle) },
                style = OwnTVButtonStyle.SECONDARY,
            )
            MetadataTestLabel(testState)
        }
        } // end if (mode.enrich)

        Spacer(Modifier.height(24.dp))
        // TMDB attribution (plan §8) — logo + line, required by TMDB's API terms.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(tv.own.owntv.R.drawable.ic_tmdb_logo),
            contentDescription = stringResource(R.string.settings_metadata),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_tmdb_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }

    if (showLangPicker) {
        // searchable: the list is long enough that D-pad scrolling to e.g. Ukrainian is tedious.
        PickerDialog(
            title = stringResource(R.string.settings_metadata_language),
            options = TMDB_LANGUAGE_CODES.map { it to tmdbLangName(it) },
            selected = language,
            searchable = true,
            onSelect = {
                if (it != language) vm.setMetadataLanguage(it)
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }
    // Return focus to the language row after the dialog closes, rather than letting it fall to the
    // screen's first mode row (same pattern as WeatherSettingsScreen's location dialog). Gated on
    // langPickerWasOpen so this doesn't fire on first composition and steal focus from firstFocus.
    LaunchedEffect(showLangPicker) {
        if (showLangPicker) {
            langPickerWasOpen = true
        } else if (langPickerWasOpen) {
            langPickerWasOpen = false
            kotlinx.coroutines.delay(80)
            runCatching { langRowFocus.requestFocus() }
        }
    }
}

@Composable
private fun MetadataTestLabel(state: SettingsViewModel.MetadataTestState) {
    val colors = OwnTVTheme.colors
    val (text, color) = when (state) {
        is SettingsViewModel.MetadataTestState.Ok -> stringResource(
            R.string.settings_metadata_match_result,
            state.title,
            state.year?.let { stringResource(R.string.settings_metadata_year, it) } ?: "",
            state.tmdbId,
        ) to colors.primary
        is SettingsViewModel.MetadataTestState.Fail -> when (val failure = state.failure) {
            SettingsViewModel.MetadataFailure.EmptyTitle -> stringResource(R.string.settings_metadata_empty_title)
            SettingsViewModel.MetadataFailure.ServerUnavailable -> stringResource(R.string.settings_metadata_server_unavailable)
            is SettingsViewModel.MetadataFailure.NoMatch -> stringResource(R.string.settings_metadata_no_match, failure.query)
            is SettingsViewModel.MetadataFailure.Unknown -> failure.rawMessage ?: stringResource(R.string.settings_metadata_lookup_failed)
        } to androidx.compose.ui.graphics.Color(0xFFEF4444)
        else -> null to colors.onSurfaceVariant
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
