package com.luapanda.idea.formatter

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.luapanda.idea.settings.LuaPandaSettings
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Action to format the current Lua file using lua-fmt.
 *
 * Uses file extension check instead of LuaFileType to avoid conflict with EmmyLua.
 */
class FormatWithLuaFmtAction : AnAction() {

    private val logger = Logger.getInstance(FormatWithLuaFmtAction::class.java)

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val project = event.project ?: return

        if (!isLuaFile(psiFile.name)) return

        val settings = LuaPandaSettings.getInstance()
        val luaFmtPath = settings.luaFmtPath
        if (luaFmtPath.isBlank()) {
            // Show notification that lua-fmt is not configured
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "lua-fmt path is not configured. Please set it in Settings → Tools → LuaPanda.",
                "lua-fmt Not Configured"
            )
            return
        }

        val document = editor.document
        val originalText = document.text

        try {
            val process = ProcessBuilder(luaFmtPath, "--stdin")
                .redirectErrorStream(false)
                .start()

            // Write original content to stdin
            process.outputStream.bufferedWriter().use { it.write(originalText) }

            // Read formatted output
            val formattedText = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }

            // Read stderr for errors
            val errors = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }

            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("lua-fmt process timed out after 10 seconds, forcibly killed")
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "lua-fmt timed out after 10 seconds and was terminated.",
                    "Formatting Timeout"
                )
                return
            }
            val exitCode = process.exitValue()

            if (exitCode == 0 && formattedText.isNotBlank()) {
                WriteCommandAction.runWriteCommandAction(project, "Format with lua-fmt", null, {
                    document.setText(formattedText)
                })
            } else if (errors.isNotBlank()) {
                logger.warn("lua-fmt error: $errors")
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "lua-fmt formatting failed:\n$errors",
                    "Formatting Error"
                )
            }
        } catch (e: Exception) {
            logger.warn("lua-fmt execution failed", e)
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "Failed to run lua-fmt: ${e.message}",
                "Formatting Error"
            )
        }
    }

    override fun update(event: AnActionEvent) {
        val psiFile = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible = psiFile != null && isLuaFile(psiFile.name)
    }

    private fun isLuaFile(fileName: String): Boolean {
        return fileName.endsWith(".lua", ignoreCase = true)
    }
}
