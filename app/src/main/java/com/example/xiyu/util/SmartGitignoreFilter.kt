package com.example.xiyu.util

object SmartGitignoreFilter {

    private val defaultJunkSegments = setOf(
        ".git",
        "node_modules",
        ".gradle",
        "build",
        ".idea",
        "__pycache__",
        ".next",
        ".nuxt",
        ".cache",
        ".dart_tool",
        "Pods"
    )

    private val defaultJunkFileNames = setOf(
        ".ds_store",
        "thumbs.db",
        "desktop.ini"
    )

    private val defaultJunkExtensions = setOf(
        "iml",
        "pyc",
        "pyo"
    )

    data class ParsedRules(
        val directoryNames: Set<String> = emptySet(),
        val exactNames: Set<String> = emptySet(),
        val extensions: Set<String> = emptySet()
    )

    fun parseGitignoreText(gitignoreContent: String?): ParsedRules {
        if (gitignoreContent.isNullOrBlank()) return ParsedRules()
        val dirs = mutableSetOf<String>()
        val exacts = mutableSetOf<String>()
        val exts = mutableSetOf<String>()

        gitignoreContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .forEach { rawLine ->
                val line = rawLine.removePrefix("./").removePrefix("/")
                when {
                    line.endsWith("/") -> {
                        val dirName = line.removeSuffix("/").substringAfterLast('/')
                        if (dirName.isNotBlank() && !dirName.contains("*")) {
                            dirs.add(dirName.lowercase())
                        }
                    }
                    line.startsWith("*.") -> {
                        val ext = line.removePrefix("*.").lowercase()
                        if (ext.isNotBlank() && !ext.contains("/")) {
                            exts.add(ext)
                        }
                    }
                    !line.contains("*") -> {
                        exacts.add(line.lowercase())
                    }
                }
            }
        return ParsedRules(directoryNames = dirs, exactNames = exacts, extensions = exts)
    }

    fun shouldIgnore(
        relativePath: String,
        smartFilterEnabled: Boolean,
        customRules: ParsedRules = ParsedRules()
    ): Boolean {
        if (!smartFilterEnabled) return false
        val normalized = relativePath.replace("\\", "/").removePrefix("./").removePrefix("/")
        if (normalized.isBlank()) return true

        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return true

        // Check directory segments (all except final file name if it's a file path)
        for (i in 0 until segments.size - 1) {
            val seg = segments[i].lowercase()
            if (seg in defaultJunkSegments || seg in customRules.directoryNames) {
                return true
            }
        }

        // Also skip if path directly targets a junk folder
        val firstOrLast = segments.last().lowercase()
        if (firstOrLast in defaultJunkSegments || firstOrLast in customRules.directoryNames) {
            return true
        }
        if (firstOrLast in defaultJunkFileNames || normalized.lowercase() in customRules.exactNames || firstOrLast in customRules.exactNames) {
            return true
        }

        val ext = firstOrLast.substringAfterLast('.', "")
        if (ext.isNotEmpty() && (ext in defaultJunkExtensions || ext in customRules.extensions)) {
            return true
        }

        return false
    }
}
