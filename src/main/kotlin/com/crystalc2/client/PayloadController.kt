package com.crystalc2.client

import com.google.protobuf.Empty
import crystalpalace.spec.Capability
import crystalpalace.spec.LinkSpec
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.stage.FileChooser
import java.io.File
import javafx.util.StringConverter
import listeners.Listeners.ListenerEvent
import listeners.Listeners.ListenerEventType
import listeners.Listeners.ListenerInfo
import listeners.ListenerServiceGrpc

class PayloadController {

    @FXML private lateinit var buildButton: Button
    @FXML private lateinit var listenerCombo: ComboBox<ListenerInfo>
    @FXML private lateinit var archCheck: CheckBox
    @FXML private lateinit var sleepField: TextField
    @FXML private lateinit var jitterField: TextField
    @FXML private lateinit var socksCheck: CheckBox

    @FXML
    fun initialize() {
        archCheck.selectedProperty().addListener { _, _, checked ->
            archCheck.text = if (checked) "x64" else "x86"
        }

        listenerCombo.apply {
            val display: (ListenerInfo?) -> String = { it?.let { l -> "${l.name} (${l.coffName})" } ?: "" }
            converter = object : StringConverter<ListenerInfo>() {
                override fun toString(l: ListenerInfo?) = display(l)
                override fun fromString(s: String?) = null
            }
            setCellFactory {
                object : ListCell<ListenerInfo>() {
                    override fun updateItem(l: ListenerInfo?, empty: Boolean) {
                        super.updateItem(l, empty)
                        text = if (empty || l == null) null else display(l)
                    }
                }
            }
            buttonCell = object : ListCell<ListenerInfo>() {
                override fun updateItem(l: ListenerInfo?, empty: Boolean) {
                    super.updateItem(l, empty)
                    text = if (empty || l == null) null else display(l)
                }
            }
            valueProperty().addListener { _, _, v ->
                buildButton.isDisable = v == null
            }
        }

        ListenerServiceGrpc.newStub(GrpcClient.channel)
            .listenerEvents(Empty.getDefaultInstance(), object : StreamObserver<ListenerEvent> {
                override fun onNext(ev: ListenerEvent) {
                    Platform.runLater {
                        when (ev.type) {
                            ListenerEventType.LISTENER_EVENT_ADDED -> {
                                listenerCombo.items.removeIf { it.id == ev.listener.id }
                                listenerCombo.items.add(ev.listener)
                            }
                            ListenerEventType.LISTENER_EVENT_DELETED -> {
                                val selected = listenerCombo.value
                                listenerCombo.items.removeIf { it.id == ev.listener.id }
                                if (selected?.id == ev.listener.id) listenerCombo.value = null
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
    fun onBuild() {
        try {
            val listener = listenerCombo.value ?: return

            val spec = LinkSpec.Parse("resources/agent.spec")
            spec.addLogger(CrystalConsoleLogger)

            val arch = if (archCheck.isSelected) "x64" else "x86"
            val cap = Capability.None(arch)

            val params = HashMap<String, Any>()
            params["%UDC2"] = File("resources", listener.coffName).absolutePath

            params["%SLEEP"] = sleepField.text.trim().ifEmpty { "05" }
            params["%JITTER"] = jitterField.text.trim().ifEmpty { "00" }
            params["\$PUBKEY"] = listener.publicKey.toByteArray()

            val extensions = buildList {
                if (socksCheck.isSelected) add("socks.spec")
            }

            params["%EXTENSION"] = extensions.joinToString(",")

            val payload = spec.run(cap, params)

            val file = FileChooser().apply {
                title = "Save Payload"
                initialFileName = "beacon.$arch.bin"
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("Binary Files", "*.bin"),
                    FileChooser.ExtensionFilter("All Files", "*.*")
                )
            }.showSaveDialog(buildButton.scene.window) ?: return

            file.writeBytes(payload)
        } catch (exception: Exception) {
            print(exception.message)
        }
    }
}