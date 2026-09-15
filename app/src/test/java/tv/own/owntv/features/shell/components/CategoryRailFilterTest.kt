package tv.own.owntv.features.shell.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryRailFilterTest {

    private val categories = listOf(
        RailCategory("Sport"),
        RailCategory("News"),
        RailCategory("Movies"),
        RailCategory("Action Movies"),
        RailCategory("Kids"),
        RailCategory("Music"),
    )

    @Test
    fun `empty query returns all indices`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), filterCategories(categories, ""))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), filterCategories(categories, "  "))
    }

    @Test
    fun `exact match returns single index`() {
        assertEquals(listOf(0), filterCategories(categories, "Sport"))
    }

    @Test
    fun `case-insensitive match`() {
        assertEquals(listOf(0), filterCategories(categories, "SPORT"))
        assertEquals(listOf(0), filterCategories(categories, "sport"))
        assertEquals(listOf(0), filterCategories(categories, "SpOrT"))
    }

    @Test
    fun `substring match returns multiple indices`() {
        assertEquals(listOf(2, 3), filterCategories(categories, "Movies"))
        assertEquals(listOf(2, 3), filterCategories(categories, "ovie"))
    }

    @Test
    fun `prefix match`() {
        assertEquals(listOf(0), filterCategories(categories, "Spo"))
    }

    @Test
    fun `suffix match`() {
        assertEquals(listOf(5), filterCategories(categories, "sic"))
    }

    @Test
    fun `no match returns empty list`() {
        assertEquals(emptyList<Int>(), filterCategories(categories, "Documentary"))
        assertEquals(emptyList<Int>(), filterCategories(categories, "xyz"))
    }

    @Test
    fun `empty category list returns empty indices for any query`() {
        assertEquals(emptyList<Int>(), filterCategories(emptyList(), ""))
        assertEquals(emptyList<Int>(), filterCategories(emptyList(), "Sport"))
    }

    @Test
    fun `whitespace-only query treated as empty`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), filterCategories(categories, "   "))
    }

    @Test
    fun `leading and trailing whitespace trimmed`() {
        assertEquals(listOf(0), filterCategories(categories, "  Sport  "))
    }

    @Test
    fun `special regex characters treated as literal`() {
        // Category names with parentheses, dots, etc. should match literally
        val special = listOf(RailCategory("News (US)"), RailCategory("Movies.com"))
        assertEquals(listOf(0), filterCategories(special, "News (US)"))
        assertEquals(listOf(1), filterCategories(special, "Movies.com"))
        assertEquals(listOf(0), filterCategories(special, "(US)"))
    }

    @Test
    fun `preserves original indices after filtering`() {
        // When filtering "Movies" from [Sport=0, News=1, Movies=2, Action Movies=3, Kids=4, Music=5]
        // we should get [2, 3], keeping the original category indices for selection.
        assertEquals(listOf(2, 3), filterCategories(categories, "Movies"))
    }
}
