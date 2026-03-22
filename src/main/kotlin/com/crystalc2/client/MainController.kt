package com.crystalc2.client

import beacon.BeaconServiceGrpc
import beacon.Beacons.BeaconHealth
import beacon.Beacons.BeaconSession
import beacon.Beacons.DeleteBeaconRequest
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin


class MainController {
    @FXML private lateinit var commandInput: TextField
    @FXML private lateinit var commandList: ListView<Command>
    @FXML private lateinit var graphPane: Pane

    // 10 tag slots: index = tag number, value = beaconId (null if free)
    private val tagSlots    = arrayOfNulls<Int>(10)
    // reverse: beaconId -> tag number
    private val beaconTags  = mutableMapOf<Int, Int>()
    // latest BeaconSession proto per beaconId
    private val sessionSessions = mutableMapOf<Int, BeaconSession>()

    private val listenerNodes   = mutableMapOf<String, VBox>()
    private val beaconGraphNodes = mutableMapOf<Int, VBox>()
    private val arrowLines      = mutableListOf<Line>()

    private lateinit var firewallImage: Image
    private lateinit var windowsImage:  Image
    private lateinit var computerImage: Image
    private lateinit var hackedImage:   Image

    companion object {
        private const val FIREWALL_SIZE = 80.0
        private const val WIN_SIZE      = 80.0
        private const val OVERLAY_SIZE  = 80.0
        private const val LISTENER_W    = 100.0
        private const val BEACON_W      = 180.0
        private const val BEACON_LABEL_H = 34.0   // approx height of the two text labels below the icon
        private const val BEACON_V_GAP  = 40.0   // vertical gap between stacked beacons
        private const val ROW_GAP       = 40.0   // fixed padding between listener groups
        private const val BEACON_OFFSET = 80.0   // horizontal distance from listener to first beacon
        private const val PAD_X         = 40.0
        private const val PAD_Y         = 40.0
    }

    @FXML
    fun initialize() {
        firewallImage = loadImage("firewall.png")
        windowsImage  = loadImage("windows.png")
        computerImage = loadImage("computer.png")
        hackedImage   = loadImage("hacked.png")

        commandList.setCellFactory { CommandCell() }
        commandList.isVisible = false
        commandList.isManaged = false

        fun showList() { commandList.isVisible = true;  commandList.isManaged = true  }
        fun hideList() { commandList.isVisible = false; commandList.isManaged = false }

        commandInput.setOnMouseClicked { showList() }
        commandInput.focusedProperty().addListener { _, _, _ ->
            Platform.runLater { if (!commandInput.isFocused && !commandList.isFocused) hideList() }
        }
        commandList.focusedProperty().addListener { _, _, _ ->
            Platform.runLater { if (!commandInput.isFocused && !commandList.isFocused) hideList() }
        }

        commandInput.textProperty().addListener { _, _, query ->
            commandList.items.setAll(CommandRegistry.search(query))
        }
        commandList.items.setAll(CommandRegistry.search(""))
        commandInput.setOnKeyPressed { e ->
            when (e.code) {
                KeyCode.ESCAPE -> { hideList(); commandInput.parent.requestFocus() }
                KeyCode.DOWN   -> commandList.selectionModel.selectNext()
                KeyCode.UP     -> commandList.selectionModel.selectPrevious()
                KeyCode.ENTER  -> execute(commandList.selectionModel.selectedItem)
                else -> {}
            }
        }
        commandList.setOnMouseClicked { execute(commandList.selectionModel.selectedItem) }

        graphPane.sceneProperty().addListener { _, _, scene ->
            scene ?: return@addListener
            for (i in 0..9) {
                scene.accelerators[KeyCombination.keyCombination("Ctrl+Shift+$i")] = Runnable {
                    val beaconId = tagSlots[i] ?: return@Runnable
                    sessionSessions[beaconId]?.let { CrystalC2.openInteractionWindow(it) }
                }
            }
        }

        BeaconServiceGrpc.newStub(GrpcClient.channel)
            .streamSessions(Empty.getDefaultInstance(), object : StreamObserver<BeaconSession> {
                override fun onNext(session: BeaconSession) {
                    Platform.runLater { upsertBeaconNode(session) }
                }
                override fun onError(t: Throwable) { t.printStackTrace() }
                override fun onCompleted() {}
            })
    }

    fun focusCommandPalette() {
        commandInput.requestFocus()
        commandList.isVisible = true
        commandList.isManaged = true
    }

    private fun execute(cmd: Command?) {
        cmd ?: return
        commandInput.clear()
        commandInput.parent.requestFocus()
        cmd.action()
    }

    private fun upsertBeaconNode(session: BeaconSession) {
        val id = session.beaconId
        beaconGraphNodes.remove(id)?.let { graphPane.children.remove(it) }
        sessionSessions[id] = session
        InteractionController.forBeacon(id)?.updateSession(session)

        if (!beaconTags.containsKey(id)) {
            val slot = tagSlots.indexOfFirst { it == null }
            if (slot != -1) { tagSlots[slot] = id; beaconTags[id] = slot }
        }

        if (!listenerNodes.containsKey(session.listener)) {
            val node = buildListenerNode(session.listener)
            listenerNodes[session.listener] = node
            graphPane.children.add(node)
        }

        val node = buildBeaconGraphNode(session)
        beaconGraphNodes[id] = node
        graphPane.children.add(node)

        layoutGraph()
    }

    private fun removeSession(id: Int) {
        beaconGraphNodes.remove(id)?.let { graphPane.children.remove(it) }
        sessionSessions.remove(id)
        beaconTags.remove(id)?.let { tagSlots[it] = null }

        val activeListeners = sessionSessions.values.map { it.listener }.toSet()
        listenerNodes.keys.toList().forEach { name ->
            if (name !in activeListeners) graphPane.children.remove(listenerNodes.remove(name))
        }

        layoutGraph()
    }

    private fun deleteBeacon(id: Int) {
        BeaconServiceGrpc.newStub(GrpcClient.channel)
            .deleteBeacon(
                DeleteBeaconRequest.newBuilder().setBeaconId(id).build(),
                object : StreamObserver<Empty> {
                    override fun onNext(v: Empty) {}
                    override fun onError(t: Throwable) { t.printStackTrace() }
                    override fun onCompleted() { Platform.runLater { removeSession(id) } }
                }
            )
    }

    private fun buildListenerNode(name: String): VBox {
        val icon = ImageView(firewallImage).apply {
            fitWidth = FIREWALL_SIZE; fitHeight = FIREWALL_SIZE; isPreserveRatio = true
        }
        val label = Label(name).apply {
            style = "-fx-text-fill: #cccccc; -fx-font-size: 11px;"
            maxWidth = LISTENER_W
            isWrapText = true
            alignment = Pos.CENTER
        }
        return VBox(4.0, icon, label).apply {
            alignment = Pos.CENTER
            prefWidth = LISTENER_W
        }
    }

    private fun buildBeaconGraphNode(session: BeaconSession): VBox {
        val base = ImageView(windowsImage).apply {
            fitWidth = WIN_SIZE; fitHeight = WIN_SIZE; isPreserveRatio = true
        }
        val overlay = ImageView(if (session.isAdmin) hackedImage else computerImage).apply {
            fitWidth = OVERLAY_SIZE; fitHeight = OVERLAY_SIZE; isPreserveRatio = true
        }
        val tag = beaconTags[session.beaconId]
        val tagLabel = tag?.let {
            Label("#$it").apply {
                style = "-fx-text-fill: #9cdcfe; -fx-font-size: 10px; -fx-font-family: monospace;"
                StackPane.setAlignment(this, Pos.TOP_LEFT)
                StackPane.setMargin(this, javafx.geometry.Insets(10.0, 0.0, 0.0, 35.0))
            }
        }
        val iconStack = StackPane().apply {
            children.addAll(base, overlay)
            if (tagLabel != null) children.add(tagLabel)
            prefWidth = WIN_SIZE; prefHeight = WIN_SIZE
            alignment = Pos.CENTER
            if (session.health == BeaconHealth.BEACON_HEALTH_DEAD) {
                effect = javafx.scene.effect.ColorAdjust().apply { saturation = -0.5; brightness = -0.6 }
            }
        }
        val userLabel = Label("${session.user}").apply {
            style = "-fx-text-fill: #9cdcfe; -fx-font-size: 11px;"
            maxWidth = BEACON_W
            alignment = Pos.CENTER
        }
        val computerLabel = Label("${session.computer}").apply {
            style = "-fx-text-fill: #9cdcfe; -fx-font-size: 11px;"
            maxWidth = BEACON_W
            alignment = Pos.CENTER
        }
        val processLabel = Label("${session.process} (${session.pid})").apply {
            style = "-fx-text-fill: #cccccc; -fx-font-size: 10px; -fx-font-family: monospace;"
            maxWidth = BEACON_W
            alignment = Pos.CENTER
        }
        val contextMenu = ContextMenu(
            MenuItem("Remove").apply { setOnAction { deleteBeacon(session.beaconId) } }
        )
        return VBox(4.0, iconStack, userLabel, computerLabel, processLabel).apply {
            alignment = Pos.CENTER
            prefWidth = BEACON_W
            setOnMouseClicked { if (it.clickCount == 2) CrystalC2.openInteractionWindow(session) }
            setOnContextMenuRequested { e -> contextMenu.show(this, e.screenX, e.screenY) }
        }
    }

    private fun layoutGraph() {
        graphPane.children.removeAll(arrowLines)
        arrowLines.clear()

        val listenerNames     = sessionSessions.values.map { it.listener }.distinct().sorted()
        val beaconsByListener = sessionSessions.values.groupBy { it.listener }

        val beaconStartX      = PAD_X + LISTENER_W + BEACON_OFFSET
        val beaconIconLeft    = beaconStartX + BEACON_W / 2 - WIN_SIZE / 2
        val listenerIconRight = PAD_X + LISTENER_W / 2 + FIREWALL_SIZE / 2

        var cursor = PAD_Y

        listenerNames.forEach { name ->
            val beacons     = beaconsByListener[name] ?: emptyList()
            val n           = beacons.size.coerceAtLeast(1)
            val slotH       = WIN_SIZE + BEACON_LABEL_H   // full visual height of one beacon node
            val groupHeight = n * slotH + (n - 1) * BEACON_V_GAP
            val listenerCenterY = cursor + groupHeight / 2

            // listener vertically centred in its group
            listenerNodes[name]?.let { node ->
                node.layoutX = PAD_X
                node.layoutY = listenerCenterY - FIREWALL_SIZE / 2
            }

            // fan arrowhead endpoints evenly along the right edge of the firewall icon
            val n2          = beacons.size
            val fanSpread   = minOf(FIREWALL_SIZE * 0.7, (n2 - 1) * 16.0)
            val fanStep     = if (n2 > 1) fanSpread / (n2 - 1) else 0.0
            val fanStartY   = listenerCenterY - fanSpread / 2

            // beacons stacked vertically, diagonal arrow to listener icon right edge
            beacons.forEachIndexed { j, session ->
                beaconGraphNodes[session.beaconId]?.let { node ->
                    val beaconTop     = cursor + j * (slotH + BEACON_V_GAP)
                    val beaconCenterY = beaconTop + WIN_SIZE / 2
                    node.layoutX = beaconStartX
                    node.layoutY = beaconTop
                    val endY = fanStartY + j * fanStep
                    arrowLines.addAll(buildArrow(beaconIconLeft, beaconCenterY, listenerIconRight, endY))
                }
            }

            cursor += groupHeight + ROW_GAP
        }

        // insert arrows behind all nodes
        arrowLines.forEachIndexed { i, line -> graphPane.children.add(i, line) }

        graphPane.prefWidth  = maxOf(400.0, beaconStartX + BEACON_W + PAD_X)
        graphPane.prefHeight = maxOf(300.0, cursor + PAD_Y)
    }

    private fun buildArrow(x1: Double, y1: Double, x2: Double, y2: Double): List<Line> {
        val color  = Color.web("#4e4e4e")
        val angle  = atan2(y2 - y1, x2 - x1)
        val spread = Math.PI / 6.0
        val len    = 10.0
        return listOf(
            Line(x1, y1, x2, y2).apply { stroke = color; strokeWidth = 1.5; isMouseTransparent = true },
            Line(x2, y2, x2 - len * cos(angle - spread), y2 - len * sin(angle - spread))
                .apply { stroke = color; strokeWidth = 1.5; isMouseTransparent = true },
            Line(x2, y2, x2 - len * cos(angle + spread), y2 - len * sin(angle + spread))
                .apply { stroke = color; strokeWidth = 1.5; isMouseTransparent = true }
        )
    }

    private fun loadImage(name: String): Image =
        Image(CrystalC2.getResource(name)?.toExternalForm() ?: error("image not found: $name"))
}


private class CommandCell : ListCell<Command>() {
    private val nameLabel = Label().apply { styleClass.add("command-name") }
    private val descLabel = Label().apply { styleClass.add("command-desc") }
    private val shortcutBox = HBox(4.0).apply { alignment = Pos.CENTER_RIGHT }
    private val row = HBox(8.0).apply {
        alignment = Pos.CENTER_LEFT
        val left = HBox(8.0, nameLabel, descLabel).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        children.addAll(left, shortcutBox)
    }

    override fun updateItem(cmd: Command?, empty: Boolean) {
        super.updateItem(cmd, empty)
        if (empty || cmd == null) { graphic = null; return }
        nameLabel.text = cmd.name
        descLabel.text = cmd.description.ifBlank { null }
        shortcutBox.children.clear()
        cmd.shortcut?.displayText?.split("+")?.forEachIndexed { i, key ->
            if (i > 0) shortcutBox.children.add(Label("+").apply { styleClass.add("shortcut-sep") })
            shortcutBox.children.add(Label(key.trim()).apply { styleClass.add("shortcut-key") })
        }
        graphic = row
        text = null
    }
}