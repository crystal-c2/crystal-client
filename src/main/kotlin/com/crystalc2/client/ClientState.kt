package com.crystalc2.client

import java.io.File

data class ServerEntry(val host: String, val port: Int, val username: String) {
    override fun toString() = "$username@$host:$port"
}

object ClientState {
    private val stateFile = File(System.getProperty("user.home"), ".crystalc2/config.conf")

    private var restoring = false

    // ── Servers ───────────────────────────────────────────────────────────────

    private val _servers = mutableListOf<ServerEntry>()
    val servers: List<ServerEntry> get() = _servers.toList()

    fun saveServer(host: String, port: Int, username: String) {
        val entry = ServerEntry(host, port, username)
        _servers.removeIf { it.host == host && it.port == port && it.username == username }
        _servers.add(0, entry)
        persist()
    }

    // ── Scripts ───────────────────────────────────────────────────────────────

    private val savedScriptPaths = mutableListOf<String>()

    fun save() {
        if (restoring) return
        persist()
    }

    /** Parse the state file — call before the login window so servers are available. */
    fun load() {
        if (!stateFile.exists()) return
        var section = ""
        for (raw in stateFile.readLines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.drop(1).dropLast(1)
                continue
            }
            when (section) {
                "servers" -> {
                    val parts = line.split("|")
                    if (parts.size == 3) {
                        val port = parts[1].toIntOrNull() ?: continue
                        _servers.add(ServerEntry(parts[0], port, parts[2]))
                    }
                }
                "scripts" -> savedScriptPaths.add(line)
            }
        }
    }

    /** Load scripts — call after login so gRPC is available. */
    fun restoreScripts() {
        restoring = true
        try {
            savedScriptPaths.forEach { path ->
                val file = File(path)
                if (file.exists()) ScriptBridge.loadScript(file)
                else System.err.println("[ClientState] Script not found, skipping: $path")
            }
        } finally {
            restoring = false
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun persist() {
        stateFile.parentFile.mkdirs()
        val sb = StringBuilder()
        if (_servers.isNotEmpty()) {
            sb.appendLine("[servers]")
            _servers.forEach { sb.appendLine("${it.host}|${it.port}|${it.username}") }
            sb.appendLine()
        }
        val scripts = ScriptBridge.loadedScripts().ifEmpty { savedScriptPaths.toSet() }
        if (scripts.isNotEmpty()) {
            sb.appendLine("[scripts]")
            scripts.forEach { sb.appendLine(it) }
        }
        stateFile.writeText(sb.toString())
    }
}