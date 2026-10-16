package com.luapanda.idea.debugger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LuaValueFormatter] — validates correct formatting of Lua variable values
 * for display in the debugger UI.
 *
 * Key invariant for string formatting:
 *   XStringValuePresentation.renderValue() calls renderer.renderStringValue() which
 *   AUTOMATICALLY wraps the value in quotes. Therefore formatString() must return
 *   the RAW content WITHOUT quotes.
 */
class LuaValueFormatterTest {

    // ========================== String formatting ==========================

    @Test
    fun `string - Lua-quoted value should have outer quotes stripped`() {
        // LuaPanda.lua often returns string values already wrapped in quotes
        val result = LuaValueFormatter.formatValue("\"hello world\"", "string")
        assertEquals("hello world", result)
    }

    @Test
    fun `string - raw value without quotes should be returned as-is`() {
        val result = LuaValueFormatter.formatValue("hello world", "string")
        assertEquals("hello world", result)
    }

    @Test
    fun `string - empty quoted string should produce empty result`() {
        // Lua returns "" for empty strings
        val result = LuaValueFormatter.formatValue("\"\"", "string")
        assertEquals("", result)
    }

    @Test
    fun `string - completely empty value should remain empty`() {
        val result = LuaValueFormatter.formatValue("", "string")
        assertEquals("", result)
    }

    @Test
    fun `string - single quote char should not be stripped`() {
        // A single " is not a quoted pair
        val result = LuaValueFormatter.formatValue("\"", "string")
        assertEquals("\"", result)
    }

    @Test
    fun `string - value with escaped quotes inside should strip outer only`() {
        // Lua returns: "say \"hi\""
        val result = LuaValueFormatter.formatValue("\"say \\\"hi\\\"\"", "string")
        assertEquals("say \\\"hi\\\"", result)
    }

    @Test
    fun `string - value with internal quotes but no outer wrapping`() {
        val result = LuaValueFormatter.formatValue("he said \"yes\"", "string")
        assertEquals("he said \"yes\"", result)
    }

    @Test
    fun `string - unicode content preserved`() {
        val result = LuaValueFormatter.formatValue("\"你好世界\"", "string")
        assertEquals("你好世界", result)
    }

    @Test
    fun `string - long value should be truncated`() {
        val longStr = "a".repeat(600)
        val result = LuaValueFormatter.formatValue(longStr, "string")
        assertTrue(result.length <= 513) // 512 + "…"
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `string - long quoted value should strip quotes then truncate`() {
        val longStr = "\"" + "b".repeat(600) + "\""
        val result = LuaValueFormatter.formatValue(longStr, "string")
        // Outer quotes stripped, then truncated
        assertFalse(result.startsWith("\""))
        assertTrue(result.endsWith("…"))
    }

    // ========================== Number formatting ==========================

    @Test
    fun `number - integer-like float should display without decimal`() {
        assertEquals("10", LuaValueFormatter.formatValue("10.0", "number"))
    }

    @Test
    fun `number - float should preserve decimal`() {
        assertEquals("3.14", LuaValueFormatter.formatValue("3.14", "number"))
    }

    @Test
    fun `number - plain integer string`() {
        assertEquals("42", LuaValueFormatter.formatValue("42", "number"))
    }

    @Test
    fun `number - scientific notation preserved`() {
        assertEquals("1e10", LuaValueFormatter.formatValue("1e10", "number"))
    }

    // ========================== Boolean formatting ==========================

    @Test
    fun `boolean - true lowercased`() {
        assertEquals("true", LuaValueFormatter.formatValue("True", "boolean"))
    }

    @Test
    fun `boolean - false lowercased`() {
        assertEquals("false", LuaValueFormatter.formatValue("False", "bool"))
    }

    // ========================== Nil formatting ==========================

    @Test
    fun `nil - always displays nil keyword`() {
        assertEquals("nil", LuaValueFormatter.formatValue("anything", "nil"))
    }

    // ========================== Table formatting ==========================

    @Test
    fun `table - address format preserved`() {
        val addr = "table: 0x000001A3"
        assertEquals(addr, LuaValueFormatter.formatValue(addr, "table"))
    }

    @Test
    fun `table - colon format preserved`() {
        val addr = "table:0x000001A3"
        assertEquals(addr, LuaValueFormatter.formatValue(addr, "table"))
    }

    // ========================== Function formatting ==========================

    @Test
    fun `function - address format preserved`() {
        val addr = "function: 0xDEADBEEF"
        assertEquals(addr, LuaValueFormatter.formatValue(addr, "function"))
    }

    // ========================== Type case-insensitivity ==========================

    @Test
    fun `type matching is case-insensitive`() {
        // "String" should match "string" branch
        val result = LuaValueFormatter.formatValue("\"test\"", "String")
        assertEquals("test", result)
    }

    @Test
    fun `type NUMBER matches number branch`() {
        assertEquals("5", LuaValueFormatter.formatValue("5.0", "NUMBER"))
    }
}
