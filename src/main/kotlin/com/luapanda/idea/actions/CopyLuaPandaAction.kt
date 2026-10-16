package com.luapanda.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

/**
 * Action to copy LuaPanda.lua debugger file to the current project.
 * Available in Tools menu.
 */
class CopyLuaPandaAction : AnAction() {

    private val logger = Logger.getInstance(CopyLuaPandaAction::class.java)

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val basePath = project.basePath ?: return

        // Read LuaPanda.lua from plugin resources
        val resourceStream = javaClass.classLoader.getResourceAsStream("debugger/LuaPanda.lua")
        if (resourceStream == null) {
            Messages.showErrorDialog(
                project,
                "Could not find LuaPanda.lua in plugin resources.",
                "Copy Failed"
            )
            return
        }

        val content = resourceStream.bufferedReader().use { it.readText() }

        val targetDir = File(basePath)
        val targetFile = File(targetDir, "LuaPanda.lua")

        if (targetFile.exists()) {
            val overwrite = Messages.showYesNoDialog(
                project,
                "LuaPanda.lua already exists in the project root. Overwrite?",
                "File Exists",
                Messages.getQuestionIcon()
            )
            if (overwrite != Messages.YES) return
        }

        try {
            targetFile.writeText(content)
            // Refresh VFS
            ApplicationManager.getApplication().invokeLater {
                VfsUtil.markDirtyAndRefresh(false, false, false, targetFile)
            }
            Messages.showInfoMessage(
                project,
                "LuaPanda.lua has been copied to:\n${targetFile.absolutePath}\n\n" +
                        "Add the following to your Lua script to start debugging:\n" +
                        "require(\"LuaPanda\").start(\"127.0.0.1\", 8818)",
                "Copy Successful"
            )
        } catch (e: Exception) {
            logger.error("Failed to copy LuaPanda.lua", e)
            Messages.showErrorDialog(
                project,
                "Failed to copy LuaPanda.lua: ${e.message}",
                "Copy Failed"
            )
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}
