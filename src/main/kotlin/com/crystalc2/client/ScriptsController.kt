package com.crystalc2.client

import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File

class ScriptsController {

    @FXML private lateinit var cardPane: FlowPane
    @FXML private lateinit var loadButton: Button
    @FXML private lateinit var consoleArea: TextArea
    @FXML private lateinit var clearConsoleButton: Button

    private val cards = mutableMapOf<String, VBox>() // canonical path -> card

    @FXML
    fun initialize() {
        ScriptBridge.consoleOutput = { text -> consoleArea.appendText(text) }
        // Reflect any scripts already loaded before this window opened
        ScriptBridge.loadedScripts().forEach { path -> addCard(File(path)) }
    }

    @FXML
    fun onLoadScript() {
        val chooser = FileChooser().apply {
            title = "Load Sleep Script"
            extensionFilters.add(FileChooser.ExtensionFilter("Sleep Scripts", "*.sl"))
        }
        val file = chooser.showOpenDialog(loadButton.scene.window) ?: return
        val canonical = file.canonicalPath
        if (canonical in cards) return // already loaded

        if (ScriptBridge.loadScript(file) == 1) {
            addCard(file)
        }
    }

    @FXML
    fun onClearConsole() {
        consoleArea.clear()
    }

    private fun addCard(file: File) {
        val canonical = file.canonicalPath

        val statusLabel = Label("● Loaded").apply {
            style = "-fx-text-fill: #4ec94e; -fx-font-size: 11px;"
        }

        val unloadBtn = Button("✕").apply {
            styleClass.add("scripts-unload-btn")
            setOnAction {
                ScriptBridge.unload(canonical)
                removeCard(canonical)
            }
        }

        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val topRow = HBox(spacer, statusLabel, unloadBtn).apply {
            alignment = Pos.CENTER_RIGHT
            spacing = 8.0
        }

        val nameLabel = Label(file.name).apply { styleClass.add("card-name") }

        val pathLabel = Label(canonical).apply {
            styleClass.add("card-coff")
            isWrapText = true
        }

        val card = VBox(8.0, topRow, nameLabel, pathLabel).apply {
            styleClass.add("listener-card")
            style = "-fx-border-color: #4ec94e transparent transparent transparent;"
            prefWidth = 280.0
        }

        cards[canonical] = card
        cardPane.children.add(card)
    }

    private fun removeCard(canonical: String) {
        cards.remove(canonical)?.let { cardPane.children.remove(it) }
    }
}