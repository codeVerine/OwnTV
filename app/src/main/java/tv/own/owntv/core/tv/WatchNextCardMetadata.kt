package tv.own.owntv.core.tv

import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataImages

/** Artwork orientation for an Android TV Watch Next card: 16:9 landscape or movie poster. */
enum class WatchNextArtShape {
    LANDSCAPE,
    POSTER,
}

/**
 * Presentation-only fields for an Android TV Watch Next card, merged from TMDB enrichment and safe
 * provider fallbacks. Deliberately contains no item id, stable key, deep-link identity, progress,
 * duration, ordering timestamp, or Watch Next type — enrichment can never change which item a Watch
 * Next entry points at, nor its resume/ordering behavior.
 */
data class WatchNextCardMetadata(
    val title: String? = null,
    /** Episode description container (show name); null for movies. */
    val containerTitle: String? = null,
    val artworkUrl: String? = null,
    val shape: WatchNextArtShape = WatchNextArtShape.LANDSCAPE,
)

/**
 * Merge TMDB movie metadata over provider fields. Blank strings count as missing at every step, and the
 * merge is null-safe end to end: null [tmdb] (metadata disabled / unmatched / failed enrichment) yields
 * a fully provider-driven card, and a movie with no artwork at all yields null art rather than failing.
 */
fun movieWatchNextCardMetadata(movie: MovieEntity, tmdb: MetadataCacheEntity?): WatchNextCardMetadata {
    val tmdbTitle = tmdb?.title?.takeIf { it.isNotBlank() }
    val tmdbBackdrop = tmdb?.let { MetadataImages.backdrop(it.backdropPath, size = "w780") }
    val providerBackdrop = movie.backdropUrl?.takeIf { it.isNotBlank() }
    val providerPoster = movie.posterUrl?.takeIf { it.isNotBlank() }
    val artwork = tmdbBackdrop ?: providerBackdrop ?: providerPoster
    return WatchNextCardMetadata(
        title = tmdbTitle ?: movie.name,
        artworkUrl = artwork,
        shape = if (artwork != null && artwork == providerPoster) WatchNextArtShape.POSTER else WatchNextArtShape.LANDSCAPE,
    )
}

/**
 * Merge TMDB show/episode metadata over provider fields for an episode card. Artwork prefers the
 * episode still (16:9), then the matched show's backdrop, then provider backdrop, then the legacy
 * provider poster. The matched show title feeds the description container; per-episode fields fall
 * back field-by-field to provider values. Blank-tolerant and null-safe like the movie merge.
 */
fun episodeWatchNextCardMetadata(
    show: SeriesEntity,
    episode: EpisodeEntity,
    showMeta: MetadataCacheEntity?,
    episodeMeta: MetadataCacheEntity?,
): WatchNextCardMetadata {
    val tmdbEpisodeTitle = episodeMeta?.title?.takeIf { it.isNotBlank() }
    val tmdbShowTitle = showMeta?.title?.takeIf { it.isNotBlank() }
    val providerEpisodeName = episode.name.takeIf { it.isNotBlank() }
    val providerShowName = show.name.takeIf { it.isNotBlank() }

    val episodeStill = episodeMeta?.let {
        MetadataImages.backdrop(it.backdropPath, size = "w780") ?: MetadataImages.backdrop(it.posterPath, size = "w780")
    }
    val tmdbShowBackdrop = showMeta?.let { MetadataImages.backdrop(it.backdropPath, size = "w780") }
    val providerBackdrop = show.backdropUrl?.takeIf { it.isNotBlank() }
    val providerPoster = show.posterUrl?.takeIf { it.isNotBlank() }
    val artwork = episodeStill ?: tmdbShowBackdrop ?: providerBackdrop ?: providerPoster

    return WatchNextCardMetadata(
        title = tmdbEpisodeTitle ?: providerEpisodeName ?: tmdbShowTitle ?: providerShowName ?: "",
        containerTitle = tmdbShowTitle ?: providerShowName ?: "",
        artworkUrl = artwork,
        shape = if (artwork != null && artwork == providerPoster) WatchNextArtShape.POSTER else WatchNextArtShape.LANDSCAPE,
    )
}
