package com.crystalc2.client

import crystalpalace.spec.SpecLogger
import crystalpalace.spec.SpecMessage
import javafx.application.Platform

/**
 * Implements SpecLogger to route CrystalPalace spec messages into the scripts
 * output console. Register on a SpecProgram with program.addLogger(this).
 */
object CrystalConsoleLogger : SpecLogger {

    private val ansiPattern = Regex("\u001B\\[[0-9;]*m")

    override fun logSpecMessage(msg: SpecMessage) {
        val clean = ansiPattern.replace(msg.message, "")
        val prefix = if (msg.type == SpecMessage.MESSAGE_WARN) "[!] " else ""
        val line   = "$prefix$clean\n"
        val sink   = ScriptBridge.consoleOutput
        if (sink != null) {
            Platform.runLater { sink(line) }
        } else {
            print(line)
        }
    }
}