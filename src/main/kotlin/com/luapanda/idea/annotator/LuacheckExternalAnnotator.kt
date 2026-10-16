package com.luapanda.idea.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.luapanda.idea.settings.LuaPandaSettings
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * External annotator that runs luacheck for static analysis.
 *
 * Collects diagnostics from luacheck and displays them as IDE warnings/errors.
 * Uses file extension check instead of LuaFileType to avoid conflict with EmmyLua.
 */
class LuacheckExternalAnnotator : ExternalAnnotator<LuacheckExternalAnnotator.CollectedInfo, List<LuacheckExternalAnnotator.LuacheckResult>>() {

    private val logger = Logger.getInstance(LuacheckExternalAnnotator::class.java)

    data class CollectedInfo(
        val filePath: String,
        val fileText: String
    )

    data class LuacheckResult(
        val line: Int,
        val column: Int,
        val endColumn: Int,
        val code: String,
        val message: String,
        val severity: HighlightSeverity
    )

    override fun collectInformation(file: PsiFile): CollectedInfo? {
        if (file.name.endsWith(".lua", ignoreCase = true).not()) return null
        val virtualFile = file.virtualFile ?: return null
        return CollectedInfo(
            filePath = virtualFile.path,
            fileText = file.text
        )
    }

    override fun doAnnotate(collectedInfo: CollectedInfo?): List<LuacheckResult>? {
        if (collectedInfo == null) return null

        val settings = LuaPandaSettings.getInstance()
        val luacheckPath = settings.luacheckPath
        if (luacheckPath.isBlank()) return null

        try {
            val process = ProcessBuilder(
                luacheckPath,
                "--formatter", "plain",
                "--codes",
                "--ranges",
                "--no-color",
                collectedInfo.filePath
            ).redirectErrorStream(true).start()

            val results = mutableListOf<LuacheckResult>()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.useLines { lines ->
                for (line in lines) {
                    parseLuacheckLine(line)?.let { results.add(it) }
                }
            }

            process.waitFor()
            return results
        } catch (e: Exception) {
            logger.debug("luacheck execution failed: ${e.message}")
            return null
        }
    }

    override fun apply(file: PsiFile, results: List<LuacheckResult>?, holder: AnnotationHolder) {
        if (results == null) return
        val document = file.viewProvider.document ?: return

        for (result in results) {
            val lineIndex = result.line - 1 // 0-based
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue

            val lineStartOffset = document.getLineStartOffset(lineIndex)
            val lineEndOffset = document.getLineEndOffset(lineIndex)

            val startOffset = (lineStartOffset + result.column - 1).coerceIn(lineStartOffset, lineEndOffset)
            val endOffset = (lineStartOffset + result.endColumn).coerceIn(startOffset, lineEndOffset)

            val range = TextRange(startOffset, endOffset.coerceAtLeast(startOffset + 1))

            holder.newAnnotation(result.severity, "(${result.code}) ${result.message}")
                .range(range)
                .create()
        }
    }

    /**
     * Parse a single luacheck output line.
     * Format: filename:line:column-endColumn: (Wcode) message
     */
    private fun parseLuacheckLine(line: String): LuacheckResult? {
        // Pattern: file:line:col-endcol: (Wcode) message
        val match = LUACHECK_LINE_REGEX.matchEntire(line) ?: return null

        val lineNum = match.groupValues[1].toIntOrNull() ?: return null
        val col = match.groupValues[2].toIntOrNull() ?: return null
        val endCol = match.groupValues[3].toIntOrNull() ?: return null
        val code = match.groupValues[4]
        val message = match.groupValues[5]

        val severity = if (code.startsWith("E")) HighlightSeverity.ERROR else HighlightSeverity.WARNING

        return LuacheckResult(lineNum, col, endCol, code, message, severity)
    }

    companion object {
        /** Pre-compiled regex for luacheck output line parsing (avoids recompilation per line) */
        private val LUACHECK_LINE_REGEX = Regex("""^.+:(\d+):(\d+)-(\d+):\s+\(([EW]\d+)\)\s+(.+)$""")
    }
}
