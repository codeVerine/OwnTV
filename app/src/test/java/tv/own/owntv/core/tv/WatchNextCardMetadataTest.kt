package tv.own.owntv.core.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/**
 * Pure JVM behavior of the Watch Next card metadata merger. The merger is the presentation boundary
 * between TMDB enrichment and the Android TV provider row: it has no access to item ids, stable keys,
 * deep links, progress, duration, ordering, or Watch Next type, so these tests double as the structural
 * guard that enrichment can never retarget a Watch Next entry.
 */
class WatchNextCardMetadataTest {

    private fun movie(
        name: String = "Provider Movie",
        posterUrl: String? = null,
        backdropUrl: String? = null,
    ) = MovieEntity(
        sourceId = 1L,
        name = name,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        streamUrl = "https://example.com/movie.mkv",
    )

    private fun show(
        name: String = "Provider Show",
        posterUrl: String? = null,
        backdropUrl: String? = null,
    ) = SeriesEntity(
        sourceId = 1L,
        name = name,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )

    private fun episode(
        name: String = "Provider Episode Name",
        season: Int = 1,
        number: Int = 2,
    ) = EpisodeEntity(
        seriesId = 1L,
        seasonNumber = season,
        episodeNumber = number,
        name = name,
        streamUrl = "https://example.com/episode.mkv",
    )

    private fun tmdbMovie(
        title: String = "TMDB Movie",
        backdropPath: String? = "/tmdb-backdrop.jpg",
        posterPath: String? = "/tmdb-poster.jpg",
    ) = MetadataCacheEntity(
        key = "movie:872585",
        tmdbId = 872585,
        imdbId = "tt1234567",
        type = "movie",
        title = title,
        year = 2024,
        overview = "overview",
        posterPath = posterPath,
        backdropPath = backdropPath,
        rating = 7.5,
        genresJson = null,
        castJson = null,
        trailerKey = null,
        logoPath = null,
        updatedAt = 1L,
    )

    private fun tmdbShow(
        title: String = "TMDB Show",
        backdropPath: String? = "/tmdb-show-backdrop.jpg",
    ) = MetadataCacheEntity(
        key = "tv:1399",
        tmdbId = 1399,
        imdbId = "tt0944947",
        type = "tv",
        title = title,
        year = 2011,
        overview = "show overview",
        posterPath = "/tmdb-show-poster.jpg",
        backdropPath = backdropPath,
        rating = 9.0,
        genresJson = null,
        castJson = null,
        trailerKey = null,
        logoPath = null,
        updatedAt = 1L,
    )

    private fun tmdbEpisode(
        title: String = "TMDB Episode Title",
        stillPath: String? = "/tmdb-still.jpg",
    ) = MetadataCacheEntity(
        key = "tv:1399:s1e2",
        tmdbId = 1399,
        imdbId = null,
        type = "episode",
        title = title,
        year = 2011,
        overview = "episode overview",
        posterPath = stillPath,
        backdropPath = stillPath,
        rating = 8.0,
        genresJson = null,
        castJson = null,
        trailerKey = null,
        logoPath = null,
        updatedAt = 1L,
    )

    @Test
    fun matchedMoviePrefersTmdbTitleAndHorizontalBackdrop() {
        val card = movieWatchNextCardMetadata(
            movie = movie(name = "Provider Title", posterUrl = "https://provider/poster.jpg", backdropUrl = "https://provider/backdrop.jpg"),
            tmdb = tmdbMovie(title = "TMDB Title", backdropPath = "/real-backdrop.jpg"),
        )

        assertEquals("TMDB Title", card.title)
        assertEquals("https://image.tmdb.org/t/p/w780/real-backdrop.jpg", card.artworkUrl)
        assertEquals(WatchNextArtShape.LANDSCAPE, card.shape)
    }

    @Test
    fun movieWithBlankTmdbFallsBackToProviderBackdropAndPoster() {
        // TMDB matched but its presentation fields are blank/missing: provider art must survive.
        val tmdb = tmdbMovie(title = "", backdropPath = null, posterPath = "  ")
        val withBackdrop = movieWatchNextCardMetadata(
            movie = movie(name = "Provider Title", backdropUrl = "https://provider/backdrop.jpg", posterUrl = "https://provider/poster.jpg"),
            tmdb = tmdb,
        )
        assertEquals("Provider Title", withBackdrop.title)
        assertEquals("https://provider/backdrop.jpg", withBackdrop.artworkUrl)
        assertEquals(WatchNextArtShape.LANDSCAPE, withBackdrop.shape)

        // No provider backdrop: legacy poster fallback stays poster-shaped.
        val posterOnly = movieWatchNextCardMetadata(
            movie = movie(name = "Provider Title", backdropUrl = null, posterUrl = "https://provider/poster.jpg"),
            tmdb = tmdb,
        )
        assertEquals("https://provider/poster.jpg", posterOnly.artworkUrl)
        assertEquals(WatchNextArtShape.POSTER, posterOnly.shape)
    }

    @Test
    fun nullMovieMetadataStillYieldsPublishableProviderCard() {
        // Null tmdb covers metadata disabled, unmatched, and enrichment failure caught by the repository.
        val card = movieWatchNextCardMetadata(movie = movie(name = "Provider Title", posterUrl = "https://provider/poster.jpg"), tmdb = null)

        assertEquals("Provider Title", card.title)
        assertEquals("https://provider/poster.jpg", card.artworkUrl)
        assertEquals(WatchNextArtShape.POSTER, card.shape)

        // No provider art at all: null art, no exception, card still buildable.
        val noArt = movieWatchNextCardMetadata(movie = movie(name = "Provider Title", posterUrl = null, backdropUrl = null), tmdb = null)
        assertEquals("Provider Title", noArt.title)
        assertNull(noArt.artworkUrl)
    }

    @Test
    fun matchedEpisodePrefersTmdbTitleStillAndShowContainer() {
        val card = episodeWatchNextCardMetadata(
            show = show(name = "Provider Show"),
            episode = episode(name = "Provider Episode Name"),
            showMeta = tmdbShow(title = "TMDB Show"),
            episodeMeta = tmdbEpisode(title = "TMDB Episode Title", stillPath = "/episode-still.jpg"),
        )

        assertEquals("TMDB Episode Title", card.title)
        assertEquals("TMDB Show", card.containerTitle)
        assertEquals("https://image.tmdb.org/t/p/w780/episode-still.jpg", card.artworkUrl)
        assertEquals(WatchNextArtShape.LANDSCAPE, card.shape)
    }

    @Test
    fun episodeWithoutStillFallsBackToMatchedShowBackdrop() {
        val card = episodeWatchNextCardMetadata(
            show = show(name = "Provider Show"),
            episode = episode(name = "Provider Episode Name"),
            showMeta = tmdbShow(title = "TMDB Show", backdropPath = "/show-backdrop.jpg"),
            episodeMeta = tmdbEpisode(title = "TMDB Episode Title", stillPath = null),
        )

        assertEquals("TMDB Episode Title", card.title)
        assertEquals("https://image.tmdb.org/t/p/w780/show-backdrop.jpg", card.artworkUrl)
        assertEquals(WatchNextArtShape.LANDSCAPE, card.shape)
    }

    @Test
    fun partialEpisodeMetadataFallsBackFieldByFieldToProvider() {
        // Episode matched with title but no still; show unmatched: art comes from provider, container from provider.
        val episodeOnly = episodeWatchNextCardMetadata(
            show = show(name = "Provider Show", backdropUrl = "https://provider/backdrop.jpg", posterUrl = "https://provider/poster.jpg"),
            episode = episode(name = "Provider Episode Name"),
            showMeta = null,
            episodeMeta = tmdbEpisode(title = "TMDB Episode Title", stillPath = null),
        )
        assertEquals("TMDB Episode Title", episodeOnly.title)
        assertEquals("Provider Show", episodeOnly.containerTitle)
        assertEquals("https://provider/backdrop.jpg", episodeOnly.artworkUrl)
        assertEquals(WatchNextArtShape.LANDSCAPE, episodeOnly.shape)

        // Show matched but episode unmatched: episode display title from provider, container from TMDB show.
        val showOnly = episodeWatchNextCardMetadata(
            show = show(name = "Provider Show", backdropUrl = null, posterUrl = "https://provider/poster.jpg"),
            episode = episode(name = "Provider Episode Name"),
            showMeta = tmdbShow(title = "TMDB Show", backdropPath = null),
            episodeMeta = null,
        )
        assertEquals("Provider Episode Name", showOnly.title)
        assertEquals("TMDB Show", showOnly.containerTitle)
        assertEquals("https://provider/poster.jpg", showOnly.artworkUrl)
        assertEquals(WatchNextArtShape.POSTER, showOnly.shape)

        // Neither matched: provider episode name, provider show container, provider poster stays poster-shaped.
        val providerOnly = episodeWatchNextCardMetadata(
            show = show(name = "Provider Show", posterUrl = "https://provider/poster.jpg"),
            episode = episode(name = "Provider Episode Name"),
            showMeta = null,
            episodeMeta = null,
        )
        assertEquals("Provider Episode Name", providerOnly.title)
        assertEquals("Provider Show", providerOnly.containerTitle)
        assertEquals("https://provider/poster.jpg", providerOnly.artworkUrl)
        assertEquals(WatchNextArtShape.POSTER, providerOnly.shape)
    }
}
