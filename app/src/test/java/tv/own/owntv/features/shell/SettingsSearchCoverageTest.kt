package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search is a second, hand-written list sitting beside the real rows, and it had quietly
 * stopped matching them: four whole screens — Recording, OpenSubtitles, Glass Effect and Content
 * menus — could not be found by name at all, and neither could two thirds of the Video player rows,
 * Multiview and the Live TV engine picker among them. The header offers to search "every setting".
 *
 * The Video player half is now derived from `VIDEO_QUICK_ROWS`, so it cannot drift again. The screens
 * cannot be derived — each is its own tab with its own entry — so this test is what holds them.
 *
 * Source-scanned rather than executed: the screen is a 4000-line composable that needs a whole
 * Android graph to run, and the thing worth checking is the text itself.
 */
class SettingsSearchCoverageTest {

    private val source: String by lazy {
        val file = File("src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt")
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        file.readText()
    }

    /** Just the search-results block, so a tab opened from a normal row does not count as covered. */
    private val searchBlock: String by lazy {
        val from = source.indexOf("val searchResults")
        val to = source.indexOf("val tokens = searchQuery", from)
        assertTrue("could not find the search block — has it been renamed?", from in 0 until to)
        source.substring(from, to)
    }

    /**
     * Tabs deliberately absent from search. Backup and Local sync live under ⋯ More, not Settings, and
     * the code says so: a result that lands somewhere the row is not is worse than no result.
     */
    private val notSearchable = setOf("ROOT", "BACKUP", "LOCAL_SYNC")

    @Test
    fun `every settings screen can be found by search`() {
        val declared = Regex("""private enum class SettingsTab \{([^}]*)\}""")
            .find(source)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        assertTrue("could not read the SettingsTab enum", declared.size > 10)

        val reachable = Regex("""SettingsTab\.([A-Z_]+)""").findAll(searchBlock)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            "settings screens with no search entry — nothing on them is findable by name",
            emptyList<String>(),
            declared.filterNot { it in notSearchable || it in reachable }.sorted(),
        )
    }

    @Test
    fun `the video player rows are derived rather than listed by hand`() {
        assertTrue(
            "the search block no longer builds its Video player entries from VIDEO_QUICK_ROWS — " +
                "deriving them is what stops that half drifting out of step with the screen",
            searchBlock.contains("VIDEO_QUICK_ROWS"),
        )
    }

    /**
     * Each key skipped by the derivation must still be covered by a bespoke entry, or it silently
     * vanishes from search — the exact failure this whole test guards against, one row at a time.
     */
    @Test
    fun `every row excluded from the derivation still has an entry of its own`() {
        val excluded = Regex("""val bespokeVideoKeys = setOf\(([^)]*)\)""")
            .find(searchBlock)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            .orEmpty()
        assertTrue("could not read bespokeVideoKeys", excluded.isNotEmpty())

        val screen = File("src/main/java/tv/own/owntv/features/settings/VideoPlayerSettingsScreen.kt").readText()
        val titleOf = excluded.associateWith { key ->
            Regex("""VideoQuickRef\("$key",\s*[A-Z_]+,\s*OwnTVIcon\.[A-Z_]+,\s*(R\.string\.[a-z_0-9]+)""")
                .find(screen)?.groupValues?.get(1)
        }
        assertEquals(
            "bespokeVideoKeys names a row that is not in VIDEO_QUICK_ROWS",
            emptyList<String>(),
            titleOf.filterValues { it == null }.keys.sorted(),
        )
        // A bespoke entry may label the row with the short "quick" variant of the same string —
        // `settings_quick_channel_numbers` where the catalogue says `settings_channel_numbers`. Same
        // setting, two ids, so compare the setting rather than the id.
        fun normalize(res: String) = res.removePrefix("R.string.").removePrefix("settings_").removePrefix("quick_")
        val present = Regex("""R\.string\.[a-z_0-9]+""").findAll(searchBlock).map { normalize(it.value) }.toSet()
        val uncovered = titleOf.filterValues { it != null && normalize(it) !in present }
        assertEquals(
            "rows skipped by the derivation with no bespoke entry left — they are unfindable",
            emptyList<String>(),
            uncovered.keys.sorted(),
        )
    }
}
