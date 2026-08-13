package tv.own.owntv.features.shell.components

import androidx.compose.runtime.Immutable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.ui.components.ChannelGenre
import tv.own.owntv.ui.components.NavAccentBar
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.rememberNavLadderColors
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.components.RailPanelFill
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.LocalGlass
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.glass

/**
 * A category as shown in the rail: just its full name, optionally prefixed with an [icon] (the
 * Favorites / History special rails). Category folders render the name alone — no abbreviation
 * badge (#75).
 */
@Immutable
data class RailCategory(
    /** Stable provider/category key. Synthetic rows keep their English key here for filtering and state. */
    val fullName: String,
    val icon: OwnTVIcon? = null,
    @param:androidx.annotation.StringRes val labelRes: Int? = null,
    // Whether to show the genre hint dot. False for synthetic aggregates ("All Channels/Movies/Series")
    // that combine every provider category — those aren't a real provider genre, so no dot.
    val showGenreDot: Boolean = true,
)

private enum class CategoryRailFocusDestination {
    SEARCH,
    SELECTED_CATEGORY,
    FIRST_CATEGORY,
}

/**
 * Layer 2 — the vertical folder rail. Always shows full-name pills (no collapse/expand
 * animation). A pinned [SearchBar] header stays visible above the scrolling [LazyColumn]
 * so long category lists never hide the filter.
 *
 * Performance notes (providers can have hundreds of categories):
 *  - The pills live in a [LazyColumn], so only the visible ones are composed.
 *  - The rail is a fixed column (no overlay) at [Dimens.RailWidth] — it takes its own layout
 *    space and nothing reflows when focus enters/leaves it.
 */
@Composable
fun CategoryRail(
    categories: List<RailCategory>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onFocused: () -> Unit = {},
    focusCategoryIndex: Int? = null,
    onFocusCategoryHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Caller-supplied list state. Defaulted so existing callers are unchanged, but Live/Movies/Series
    // pass their own so CH+- key paging can drive the rail's scroll position from the screen.
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    // Column width. Defaults to the stock rail width; Live/Movies/Series override it when the user has
    // turned on manual panel widths for that section (see PanelWidths.kt).
    width: androidx.compose.ui.unit.Dp = Dimens.RailWidthFixed,
    // Browse screens place this column inside one shared content panel. Overlays keep the standalone
    // panel so they remain independently raised above the screen beneath them.
    showPanel: Boolean = true,
) {
    val colors = OwnTVTheme.colors
    var hasFocus by remember { mutableStateOf(false) }
    // Folder search (for big libraries). Filters the rail by name but keeps each folder's ORIGINAL
    // index, so selection highlighting and onSelect still map correctly. Reset when the rail loses
    // focus, so it's fresh every time you open it.
    var query by remember { mutableStateOf("") }
    val visible = remember(categories, query) {
        filterCategories(categories, query)
    }
    // Phase 2 — the rail is a FIXED full-label column (no collapse/abbreviation overlay), so it never
    // reflows the layout on the D-pad. Always "expanded" = full category names.
    val expanded = true

    val selectedFocus = remember { FocusRequester() }
    val firstCategoryFocus = remember { FocusRequester() }
    val requestedCategoryFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var focusDestination by remember { mutableStateOf<CategoryRailFocusDestination?>(null) }
    var focusGeneration by remember { mutableStateOf(0) }
    var focusedCategoryIndex by remember { mutableStateOf<Int?>(null) }
    val requestedVisible = focusCategoryIndex?.let { visible.indexOf(it) } ?: -1

    // Keep the selected category in view when the selection changes — both for the initial load /
    // restored state (rail not yet focused) AND when CH+- paging selects a far-away category while the
    // rail IS focused. While the user D-pads inside, focus handles scrolling for adjacent moves; this
    // covers the case where a CH key changes selectedIndex by a large jump.
    LaunchedEffect(selectedIndex, visible) {
        val selectedVisible = visible.indexOf(selectedIndex)
        if (selectedVisible >= 0) {
            runCatching { listState.scrollToItem(selectedVisible) }
        }
    }

    LaunchedEffect(focusCategoryIndex, hasFocus, visible, focusDestination, focusGeneration, focusedCategoryIndex) {
        if (focusDestination == CategoryRailFocusDestination.SEARCH) return@LaunchedEffect
        val requestedIndex = focusCategoryIndex ?: return@LaunchedEffect
        val generation = focusGeneration
        if (!hasFocus || requestedIndex !in categories.indices) {
            onFocusCategoryHandled()
            return@LaunchedEffect
        }
        if (visible.isEmpty()) {
            onFocusCategoryHandled()
            return@LaunchedEffect
        }
        val target = requestedVisible.takeIf { it >= 0 } ?: 0
        runCatching { listState.scrollToItem(target) }
        withFrameNanos { }
        if (
            !hasFocus ||
            generation != focusGeneration ||
            focusCategoryIndex != requestedIndex
        ) return@LaunchedEffect
        runCatching {
            if (requestedVisible >= 0) requestedCategoryFocus.requestFocus() else firstCategoryFocus.requestFocus()
        }
        if (generation == focusGeneration && focusCategoryIndex == requestedIndex) onFocusCategoryHandled()
    }

    LaunchedEffect(focusDestination, hasFocus, visible, focusGeneration, selectedIndex) {
        val destination = focusDestination ?: return@LaunchedEffect
        val generation = focusGeneration
        when (destination) {
            CategoryRailFocusDestination.SEARCH -> {
                if (hasFocus && generation == focusGeneration && focusDestination == destination) {
                    runCatching { searchFocus.requestFocus() }
                }
            }

            CategoryRailFocusDestination.SELECTED_CATEGORY,
            CategoryRailFocusDestination.FIRST_CATEGORY,
            -> if (hasFocus && visible.isNotEmpty()) {
                val focusedCategoryAtStart = focusedCategoryIndex
                val selectedVisible = visible.indexOf(selectedIndex)
                val focusSelected =
                    destination == CategoryRailFocusDestination.SELECTED_CATEGORY && selectedVisible >= 0
                val target = if (focusSelected) selectedVisible else 0
                runCatching { listState.scrollToItem(target) }
                withFrameNanos { }
                if (
                    !hasFocus ||
                    generation != focusGeneration ||
                    focusDestination != destination ||
                    focusedCategoryIndex != focusedCategoryAtStart
                ) return@LaunchedEffect
                runCatching {
                    if (focusSelected) selectedFocus.requestFocus() else firstCategoryFocus.requestFocus()
                }
            }

            null -> Unit
        }
        if (generation == focusGeneration && focusDestination == destination) focusDestination = null
    }

    // Fixed full-label column in the screen's Row — a real grid column (no overlay), so it takes its own
    // space and nothing reflows when focus enters/leaves it.
    val railModifier = modifier.fillMaxHeight().width(width)
    Box(
        modifier = if (showPanel) {
            railModifier.roundedPanel(fillColor = RailPanelFill, surface = GlassSurface.SIDEBAR)
        } else {
            railModifier
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged {
                    // Spatial D-pad entry may initially land on the category aligned with the content
                    // row. Redirect every entry to Search so returning from content is predictable and
                    // category filtering is always immediately available. Internal moves between Search
                    // and category pills don't re-trigger this. The destination is requested after the
                    // focus transaction completes.
                    val entered = it.hasFocus && !hasFocus
                    hasFocus = it.hasFocus
                    if (it.hasFocus) {
                        onFocused()
                    } else {
                        query = ""
                        focusDestination = null
                        focusGeneration++
                    }
                    if (entered) {
                        onFocusCategoryHandled()
                        focusDestination = CategoryRailFocusDestination.SEARCH
                    }
                }
                // Held Up/Down can outrun the lazy list's composition and escape the rail (landing
                // on the top bar) — trap vertical exits; Left/Right/Back still leave normally.
                .trapVerticalFocusExit()
                .focusGroup()
        ) {
            // Category-search field pinned above the scrolling list so it stays visible as the
            // user scrolls through a long category rail.
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(tv.own.owntv.R.string.content_search_categories),
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown && visible.isNotEmpty()) {
                            focusDestination = if (selectedIndex in visible) {
                                CategoryRailFocusDestination.SELECTED_CATEGORY
                            } else {
                                CategoryRailFocusDestination.FIRST_CATEGORY
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .focusRequester(searchFocus)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = if (showPanel) {
                    PaddingValues(vertical = Dimens.GapLarge, horizontal = 10.dp)
                } else {
                    PaddingValues(0.dp)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall),
            ) {
                items(count = visible.size, key = { visible[it] }) { i ->
                    val index = visible[i]
                    RailPill(
                        category = categories[index],
                        // RailPill only lights the green "active" fill when this pill is BOTH the current
                        // category AND focused — so the highlight always follows focus and nothing is auto-lit.
                        selected = index == selectedIndex,
                        expanded = expanded,
                        onClick = { onSelect(index) },
                        onFocusStateChanged = { focused ->
                            if (focused) {
                                if (focusCategoryIndex != null) {
                                    if (focusCategoryIndex != index) focusGeneration++
                                    onFocusCategoryHandled()
                                }
                                focusedCategoryIndex = index
                            } else if (focusedCategoryIndex == index) {
                                focusedCategoryIndex = null
                            }
                        },
                        modifier = when {
                            i == requestedVisible && requestedVisible >= 0 -> Modifier.focusRequester(requestedCategoryFocus)
                            index == selectedIndex -> Modifier.focusRequester(selectedFocus)
                            i == 0 -> Modifier.focusRequester(firstCategoryFocus)
                            else -> Modifier
                        },
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            stringResource(tv.own.owntv.R.string.content_no_categories_match),
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Exposed for testing: filters category indices by a query string, case-insensitive on fullName. */
fun filterCategories(categories: List<RailCategory>, query: String): List<Int> {
    val q = query.trim()
    return if (q.isEmpty()) categories.indices.toList()
    else categories.indices.filter { categories[it].fullName.contains(q, ignoreCase = true) }
}

@Composable
private fun RailPill(
    category: RailCategory,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Box-style corners (8.dp), close to the live-TV channel list item, not an over-rounded pill.
    val shape = if (expanded) RoundedCornerShape(8.dp) else CircleShape
    // Glass effect: when the PANELS surface is glassy, the focused/active highlight renders as a
    // frosted glass slice (via Modifier.glass) with a bright white rim, matching the sidebar.
    val panelsGlassy = LocalGlass.current.isGlassy(GlassSurface.PANELS)
    // Shared 4-state nav ladder (see NavLadder.kt) — identical treatment to the sidebar nav items so
    // both panels read the same (#47): active+focused (full fill) → focused cursor (outline) →
    // selected-idle (tonal fill + left accent bar) → idle. Focus fills snap in both material modes
    // so an old category cannot leave a dark plate behind while LazyColumn moves the next one into view.
    val ladder = rememberNavLadderColors(
        selected = selected,
        focused = focused,
    )
    val activeSelected = selected && focused
    val highlighted = focused || selected

    Box(
        modifier = modifier
            .onFocusChanged { onFocusStateChanged(it.isFocused) }
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier.size(Dimens.RailPillSize))
            .clip(shape)
            // Frosted glass fill when the panel is glassy (idle pills have a transparent ladder fill,
            // which glass() skips); plain tonal fill otherwise.
            .glass(surface = GlassSurface.PANELS, baseFill = ladder.container, shape = shape)
            .then(
                when {
                    panelsGlassy && highlighted -> Modifier.border(Dimens.FocusBorderWidth, Color.White.copy(alpha = 0.35f), shape)
                    ladder.focusBorder != null -> Modifier.border(Dimens.FocusBorderWidth, ladder.focusBorder, shape)
                    else -> Modifier
                }
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // Persistent left accent bar marking the active category (only in the expanded full-label rail —
        // a vertical bar on a compact circle pill would look wrong). Hidden in glass mode: the frosted
        // highlight already marks the active pill and the accent bar clashes (matches the sidebar).
        NavAccentBar(visible = ladder.showAccentBar && expanded && !panelsGlassy)

        Row(
            modifier = Modifier
                .then(if (expanded) Modifier.fillMaxWidth() else Modifier.size(Dimens.RailPillSize))
                .then(if (expanded) Modifier.padding(horizontal = 10.dp, vertical = 8.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            // Favorites / History carry an [icon] inline before the name; category folders show the
            // name alone with no abbreviation badge (#75).
            if (category.icon != null) {
                OwnTVIcon(icon = category.icon, tint = ladder.icon, filled = activeSelected, modifier = Modifier.size(if (expanded) 20.dp else Dimens.RailPillSize / 2))
                if (expanded) Spacer(Modifier.width(8.dp))
            } else if (expanded && category.showGenreDot) {
                // Genre hint dot (Sport/News/Movies/Action/…); unknown categories show the grey
                // "Other" dot rather than an empty slot, so every row has a consistent marker.
                val genreDot = ChannelGenre.fromCategory(category.fullName).dot
                Box(Modifier.size(8.dp).clip(CircleShape).background(genreDot))
                Spacer(Modifier.width(10.dp))
            }
            if (expanded) {
                Text(
                    text = category.labelRes?.let { stringResource(it) } ?: category.fullName,
                    color = ladder.content,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
