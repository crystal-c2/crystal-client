package com.crystalc2.client

import javafx.collections.FXCollections
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Window

class CommandPalette {
    private val popup = Popup()
    private val results = FXCollections.observableArrayList<Command>()

    init {
        val input = TextField().apply {
            promptText = "Enter command"
            styleClass.add("command-palette-input")
            textProperty().addListener { _, _, query ->
                results.setAll(CommandRegistry.search(query))
            }
        }

        val listView = ListView(results).apply {
            //prefHeight = 220.0
            styleClass.add("command-palette-list")
            setCellFactory {
                object : ListCell<Command>() {
                    override fun updateItem(cmd: Command?, empty: Boolean) {
                        super.updateItem(cmd, empty)
                        text = if (empty || cmd == null) null
                               else if (cmd.description.isNotBlank()) "${cmd.name} - ${cmd.description}"
                               else cmd.name
                    }
                }
            }
            setOnMouseClicked { execute(selectionModel.selectedItem) }
        }

        input.setOnKeyPressed { e ->
            when (e.code) {
                KeyCode.DOWN   -> listView.selectionModel.selectNext()
                KeyCode.UP     -> listView.selectionModel.selectPrevious()
                KeyCode.ENTER  -> execute(listView.selectionModel.selectedItem)
                KeyCode.ESCAPE -> popup.hide()
                else -> {}
            }
        }

        popup.content.add(VBox(input, listView).apply {
            styleClass.add("command-palette")
        })
        popup.isAutoHide = true

        popup.setOnShown { input.requestFocus() }
    }

    private fun execute(cmd: Command?) {
        cmd ?: return
        popup.hide()
        cmd.action()
    }
}