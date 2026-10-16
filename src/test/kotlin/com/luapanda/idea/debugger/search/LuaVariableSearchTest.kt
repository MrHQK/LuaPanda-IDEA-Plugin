package com.luapanda.idea.debugger.search

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for the stack-frame variable search helpers:
 *  - [LuaVariableCollector.childNameAndPath]: builds the Lua access expression of a table member
 *    from the raw key LuaPanda.lua sends (`"str"` for string keys, tostring() otherwise).
 *  - [EntryMatcher]: name/path filtering + highlight ranges used by the search dialog.
 */
class LuaVariableSearchTest {

    private fun entry(
        name: String,
        path: String = name,
        type: String = "number",
        value: String = "1",
        scope: LuaVariableScope = LuaVariableScope.LOCAL,
        depth: Int = 0
    ) = LuaVariableEntry(scope, name, path, type, value, depth)

    // ========================== Path building ==========================

    @Test
    fun `child path - identifier string key uses dot notation`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("self", "\"hp\"")
        assertEquals("hp", name)
        assertEquals("self.hp", path)
    }

    @Test
    fun `child path - string key with space uses bracket notation`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("self.data", "\"hp max\"")
        assertEquals("hp max", name)
        assertEquals("self.data[\"hp max\"]", path)
    }

    @Test
    fun `child path - numeric key is indexed as-is`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("arr", "3")
        assertEquals("3", name)
        assertEquals("arr[3]", path)
    }

    @Test
    fun `child path - non-string non-numeric key keeps raw tostring form`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("t", "true")
        assertEquals("true", name)
        assertEquals("t[true]", path)
    }

    @Test
    fun `child path - lua keyword string key uses bracket notation`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("t", "\"end\"")
        assertEquals("end", name)
        assertEquals("t[\"end\"]", path)
    }

    @Test
    fun `child path - quote in string key is escaped for bracket notation`() {
        // LuaPanda.lua wraps string keys in quotes WITHOUT escaping their content,
        // so the raw key may already contain quote/backslash characters.
        val (name, path) = LuaVariableCollector.childNameAndPath("t", "\"a\\\"b\"")
        assertEquals("""a\"b""", name)
        assertEquals("""t["a\\\"b"]""", path)
    }

    @Test
    fun `child path - metatable key is not addressable by name`() {
        val (name, path) = LuaVariableCollector.childNameAndPath("obj", LuaVariableCollector.METATABLE_KEY)
        assertEquals(LuaVariableCollector.METATABLE_KEY, name)
        assertEquals("getmetatable(obj)", path)
    }

    // ========================== Matching ==========================

    @Test
    fun `matcher - empty query matches everything`() {
        val matcher = EntryMatcher.create("", false, false)
        assertTrue(matcher.matches(entry("hp")))
    }

    @Test
    fun `matcher - plain query matches variable name case-insensitively`() {
        val matcher = EntryMatcher.create("HP", false, false)
        assertTrue(matcher.matches(entry("maxHp", "self.maxHp")))
        assertFalse(matcher.matches(entry("mp", "self.mp")))
    }

    @Test
    fun `matcher - plain query respects match case option`() {
        val matcher = EntryMatcher.create("hp", true, false)
        assertTrue(matcher.matches(entry("hp")))
        assertFalse(matcher.matches(entry("HP")))
    }

    @Test
    fun `matcher - query containing dot matches full path`() {
        val matcher = EntryMatcher.create("self.hp", false, false)
        assertTrue(matcher.matches(entry("hp", "self.hp")))
        // name itself does not contain the query, only the path does
        assertFalse(matcher.matches(entry("hp", "self.other")))
    }

    @Test
    fun `matcher - invalid regex reports error and matches nothing`() {
        val matcher = EntryMatcher.create("[unclosed", false, true)
        assertNotNull(matcher.error)
        assertFalse(matcher.matches(entry("hp")))
    }

    @Test
    fun `matcher - regex query matches name`() {
        val matcher = EntryMatcher.create("^hp\\d+$", false, true)
        assertTrue(matcher.matches(entry("hp1")))
        assertFalse(matcher.matches(entry("hpX")))
    }

    // ========================== Highlighting ==========================

    @Test
    fun `highlight - name mode highlights occurrence inside path`() {
        val matcher = EntryMatcher.create("hp", false, false)
        val ranges = matcher.highlightRanges(entry("hp", "self.player.hp"))
        assertEquals(1, ranges.size)
        assertEquals("hp", "self.player.hp".substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun `highlight - path mode highlights all query occurrences`() {
        val matcher = EntryMatcher.create("self.", false, false)
        val ranges = matcher.highlightRanges(entry("self.self", "self.self.self"))
        assertEquals(2, ranges.size)
    }

    @Test
    fun `highlight - name missing from path yields no ranges`() {
        val matcher = EntryMatcher.create("zzz", false, false)
        assertTrue(matcher.highlightRanges(entry("zzz", "self.hp")).isEmpty())
    }
}
