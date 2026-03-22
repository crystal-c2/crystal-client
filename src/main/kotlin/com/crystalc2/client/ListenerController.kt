package com.crystalc2.client

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Dialog
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import listeners.Listeners
import listeners.Listeners.ListenerInfo
import listeners.Listeners.ListenerStatus
import listeners.ListenerServiceGrpc
import java.io.File

class ListenerController {
    @FXML private lateinit var cardPane: FlowPane
    @FXML private lateinit var newListenerButton: Button

    private val cards = mutableMapOf<Int, VBox>()

    @FXML
    fun initialize() {
        ListenerServiceGrpc.newStub(GrpcClient.channel)
            .listenerEvents(Empty.getDefaultInstance(), object : StreamObserver<Listeners.ListenerEvent> {
                override fun onNext(ev: Listeners.ListenerEvent) {
                    Platform.runLater {
                        when (ev.type) {
                            Listeners.ListenerEventType.LISTENER_EVENT_ADDED -> {
                                val l = ev.listener
                                if (l.coffName.isNotBlank() && !l.coffBytes.isEmpty) {
                                    val dir = File("resources").also { it.mkdirs() }
                                    File(dir, l.coffName).writeBytes(l.coffBytes.toByteArray())
                                }
                                addCard(l)
                            }
                            Listeners.ListenerEventType.LISTENER_EVENT_DELETED -> {
                                val name = ev.listener.coffName
                                if (name.isNotBlank()) File("resources", name).delete()
                                removeCard(ev.listener.id)
                            }
                            else -> {}
                        }
                    }
                }
                override fun onError(t: Throwable) { t.printStackTrace() }
                override fun onCompleted() {}
            })
    }

    @FXML
    fun onNewListener() {
        val dialog = Dialog<ButtonType>().apply {
            title = "New Listener"
            headerText = null
            isResizable = false
            dialogPane.style = "-fx-background-color: #252526;"
            dialogPane.stylesheets.add(
                CrystalC2.getResource("styles.css")!!.toExternalForm()
            )
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }

        val nameField = TextField().apply {
            styleClass.add("listener-form-field")
            promptText = "name"
        }
        val portField = TextField().apply {
            styleClass.add("listener-form-field")
            promptText = "port"
        }

        var coffFile: File? = null
        val coffLabel = Label("no file selected").apply { styleClass.add("card-coff") }
        val browseBtn = Button("Browse…").apply {
            styleClass.add("scripts-load-btn")
            setOnAction {
                val chooser = FileChooser().apply {
                    title = "Select UDC2 Library"
                    extensionFilters.addAll(
                        FileChooser.ExtensionFilter("ZIP", "*.zip"),
                        FileChooser.ExtensionFilter("All Files", "*.*")
                    )
                }
                coffFile = chooser.showOpenDialog(dialog.dialogPane.scene.window)
                coffLabel.text = coffFile?.name ?: "no file selected"
            }
        }

        val coffRow = HBox(8.0, coffLabel, browseBtn).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(coffLabel, Priority.ALWAYS)
        }

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            style = "-fx-padding: 16 20;"
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 50.0 },
                ColumnConstraints().apply { hgrow = Priority.ALWAYS; minWidth = 220.0 }
            )
            add(Label("Name").apply { styleClass.add("listener-form-label") }, 0, 0)
            add(nameField, 1, 0)
            add(Label("Port").apply { styleClass.add("listener-form-label") }, 0, 1)
            add(portField, 1, 1)
            add(Label("UDC2").apply { styleClass.add("listener-form-label") }, 0, 2)
            add(coffRow, 1, 2)
        }
        dialog.dialogPane.content = grid

        val okBtn = dialog.dialogPane.lookupButton(ButtonType.OK) as Button
        okBtn.isDisable = true
        fun validate() { okBtn.isDisable = nameField.text.isBlank() || portField.text.toIntOrNull() == null }
        nameField.textProperty().addListener { _, _, _ -> validate() }
        portField.textProperty().addListener { _, _, _ -> validate() }

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return

        val req = Listeners.CreateListenerRequest.newBuilder()
            .setName(nameField.text.trim())
            .setBindPort(portField.text.trim().toInt())
        coffFile?.let { f ->
            req.setCoffName(f.name)
            req.setCoffBytes(ByteString.copyFrom(f.readBytes()))
        }
        ListenerServiceGrpc.newStub(GrpcClient.channel)
            .createListener(req.build(), object : StreamObserver<Empty> {
                override fun onNext(v: Empty) {}
                override fun onError(t: Throwable) { t.printStackTrace() }
                override fun onCompleted() {}
            })
    }

    private fun addCard(listener: ListenerInfo) {
        val (statusText, statusColor, accentColor) = when (listener.status) {
            ListenerStatus.LISTENER_STATUS_RUNNING -> Triple("● Running", "#4ec94e", "#4ec94e")
            ListenerStatus.LISTENER_STATUS_STOPPED -> Triple("○ Stopped", "#a0a0a0", "#555555")
            ListenerStatus.LISTENER_STATUS_ERROR   -> Triple("✖ Error",   "#f14c4c", "#f14c4c")
            else                                   -> Triple("? Unknown", "#a0a0a0", "#555555")
        }

        val statusLabel = Label(statusText).apply {
            style = "-fx-text-fill: $statusColor; -fx-font-size: 11px;"
        }

        val deleteBtn = Button("✕").apply {
            styleClass.add("scripts-unload-btn")
            setOnAction {
                ListenerServiceGrpc.newStub(GrpcClient.channel)
                    .deleteListener(
                        Listeners.DeleteListenerRequest.newBuilder().setId(listener.id).build(),
                        object : StreamObserver<Empty> {
                            override fun onNext(v: Empty) {}
                            override fun onError(t: Throwable) { t.printStackTrace() }
                            override fun onCompleted() {}
                        }
                    )
            }
        }

        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val topRow = HBox(spacer, statusLabel, deleteBtn).apply {
            alignment = Pos.CENTER_RIGHT
            spacing = 8.0
        }

        val nameLabel = Label(listener.name).apply { styleClass.add("card-name") }
        val portLabel = Label("${listener.bindPort}").apply { styleClass.add("card-port") }
        val coffLabel = Label(listener.coffName.ifBlank { "-" }).apply {
            styleClass.add("card-coff")
            isWrapText = false
        }

        val card = VBox(8.0, topRow, nameLabel, portLabel, coffLabel).apply {
            styleClass.add("listener-card")
            style = "-fx-border-color: $accentColor transparent transparent transparent;"
            prefWidth = 220.0
        }

        cards[listener.id] = card
        cardPane.children.add(card)
    }

    private fun removeCard(id: Int) {
        cards.remove(id)?.let { cardPane.children.remove(it) }
    }
}