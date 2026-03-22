package com.crystalc2.client

import javafx.scene.Scene
import javafx.scene.input.KeyCombination

data class Command(
    val name: String,
    val description: String = "",
    val shortcut: KeyCombination? = null,
    val action: () -> Unit
)

object CommandRegistry {
    private val commands = mutableListOf<Command>()

    fun register(vararg cmds: Command) = commands.addAll(cmds)

    fun search(query: String): List<Command> =
        if (query.isBlank()) commands.toList()
        else commands.filter { it.name.contains(query, ignoreCase = true) }

    fun registerAccelerators(scene: Scene) {
        commands.forEach { cmd ->
            cmd.shortcut?.let { scene.accelerators[it] = Runnable { cmd.action() } }
        }
    }
}