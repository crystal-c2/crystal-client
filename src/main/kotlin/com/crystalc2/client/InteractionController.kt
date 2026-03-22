package com.crystalc2.client

import beacon.Beacons.BeaconHealth
import beacon.Beacons.BeaconSession
import io.grpc.stub.StreamObserver
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.Duration
import tasks.TaskServiceGrpc
import tasks.Tasks
import tasks.Tasks.TaskRequest
import tasks.Tasks.TaskStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class InteractionController {
    @FXML private lateinit var headerBar: HBox
    @FXML private lateinit var outputPane: VBox
    @FXML private lateinit var scrollPane: ScrollPane
    @FXML private lateinit var inputField: TextField

    private lateinit var session: BeaconSession
    private lateinit var requestStream: StreamObserver<TaskRequest>
    private lateinit var lastSeenLabel: Label
    private lateinit var healthLabel: Label
    private lateinit var lastSeenTicker: Timeline

    private val pendingQueue = ArrayDeque<TaskEntry>()
    private val taskEntries = mutableMapOf<UInt, TaskEntry>()

    private val history = mutableListOf<String>()
    private var historyIndex = -1

    private data class TabState(val prefix: String, val candidates: List<String>, var index: Int)
    private var tabState: TabState? = null

    companion object {
        private val instances = mutableMapOf<Int, InteractionController>()

        fun forBeacon(beaconId: Int): InteractionController? = instances[beaconId]
    }

    fun init(session: BeaconSession) {
        this.session = session
        buildHeader()
        openStream()

        instances[session.beaconId] = this
        ScriptBridge.registerBeaconCharset(session.beaconId, session.charset)
        outputPane.sceneProperty().addListener { _, _, scene ->
            scene ?: return@addListener
            scene.accelerators[KeyCombination.keyCombination("Ctrl+L")] = Runnable {
                outputPane.children.clear()
            }
            scene.windowProperty().addListener { _, _, window ->
                window?.setOnHidden {
                    instances.remove(session.beaconId, this)
                    lastSeenTicker.stop()
                }
            }
        }

        inputField.setOnKeyPressed { e ->
            if (e.code != KeyCode.TAB) tabState = null
            when (e.code) {
                KeyCode.ENTER -> submitCommand()
                KeyCode.UP    -> navigateHistory(1)
                KeyCode.DOWN  -> navigateHistory(-1)
                KeyCode.TAB   -> { e.consume(); handleTabCompletion() }
                else          -> {}
            }
        }
    }

    fun updateSession(updated: BeaconSession) {
        session = updated
        val (healthText, healthColor) = when (updated.health) {
            BeaconHealth.BEACON_HEALTH_ALIVE -> "● Alive"   to "#4ec94e"
            BeaconHealth.BEACON_HEALTH_LOST  -> "◌ Lost"    to "#e5c07b"
            BeaconHealth.BEACON_HEALTH_DEAD  -> "✖ Dead"    to "#f14c4c"
            else                             -> "? Unknown" to "#6e6e6e"
        }
        healthLabel.text = healthText
        healthLabel.style = "-fx-text-fill: $healthColor;"
    }

    fun postScriptOutput(output: String?) {
        Platform.runLater {
            val entry = TaskEntry("<script>")
            outputPane.children.add(entry.node)
            entry.setScriptOutput(output)
            scrollToBottom()
        }
    }

    private fun buildHeader() {
        val (healthText, healthColor) = when (session.health) {
            BeaconHealth.BEACON_HEALTH_ALIVE -> "● Alive"   to "#4ec94e"
            BeaconHealth.BEACON_HEALTH_LOST  -> "◌ Lost"    to "#e5c07b"
            BeaconHealth.BEACON_HEALTH_DEAD  -> "✖ Dead"    to "#f14c4c"
            else                             -> "? Unknown" to "#6e6e6e"
        }
        val adminText = if (session.isAdmin) "  ★" else ""
        val displayUser = if (session.hasImpersonated())
            "${session.impersonated} (via ${session.user})"
        else session.user

        healthLabel = headerChip(healthText, healthColor)
        lastSeenLabel = headerChip("", "#6e6e6e")
        lastSeenTicker = Timeline(
            KeyFrame(Duration.seconds(1.0), { updateLastSeen() })
        ).apply { cycleCount = Timeline.INDEFINITE }

        headerBar.children.addAll(
            headerChip(session.computer + adminText, "#ffffff"),
            headerChip(displayUser, "#9cdcfe"),
            headerChip(session.internalIp, "#cccccc"),
            headerChip("${session.process} (${session.pid})", "#cccccc"),
            healthLabel,
            lastSeenLabel,
        )

        updateLastSeen()
        lastSeenTicker.play()
    }

    private fun updateLastSeen() {
        val elapsed = Instant.now().epochSecond - session.lastSeen.seconds
        lastSeenLabel.text = when {
            elapsed < 60   -> "${elapsed}s ago"
            elapsed < 3600 -> "${elapsed / 60}m ${elapsed % 60}s ago"
            else           -> "${elapsed / 3600}h ${(elapsed % 3600) / 60}m ago"
        }
    }

    private fun headerChip(text: String, color: String) =
        Label(text).apply {
            style = "-fx-text-fill: $color;"
            styleClass.add("interaction-header-chip")
        }

    private fun openStream() {
        requestStream = TaskServiceGrpc.newStub(GrpcClient.channel)
            .streamTasks(object : StreamObserver<Tasks.TaskResponse> {
                override fun onNext(response: Tasks.TaskResponse) {
                    Platform.runLater { handleResponse(response) }
                }
                override fun onError(t: Throwable) { t.printStackTrace() }
                override fun onCompleted() {}
            })
    }

    private fun handleTabCompletion() {
        val text = inputField.text
        if (text.contains(' ')) return
        val state = tabState
        if (state == null) {
            val candidates = ScriptBridge.commandNames()
                .filter { it.startsWith(text) }
                .sorted()
            if (candidates.isEmpty()) return
            tabState = TabState(text, candidates, 0)
        } else {
            state.index = (state.index + 1) % state.candidates.size
        }
        inputField.text = tabState!!.candidates[tabState!!.index]
        inputField.end()
    }

    private fun navigateHistory(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(-1, history.lastIndex)
        inputField.text = if (historyIndex == -1) "" else history[historyIndex]
        inputField.end()
    }

    private fun submitCommand() {
        val cmd = inputField.text.trim()
        if (cmd.isBlank()) return
        inputField.clear()
        if (history.isEmpty() || history.first() != cmd) history.add(0, cmd)
        historyIndex = -1

        val token = cmd.substringBefore(' ')
        val scriptCmd = ScriptBridge.getScriptCommand(token)
        if (scriptCmd != null) {
            val entry = TaskEntry(cmd)
            outputPane.children.add(entry.node)
            ScriptBridge.enqueueEntryHooks(session.beaconId,
                onOutput  = { output -> entry.setScriptOutput(output); scrollToBottom() },
                onPending = { taskId, ts ->
                    entry.setTaskId(taskId)
                    entry.updateStatus(TaskStatus.TASK_STATUS_PENDING, ts)
                },
                onStatus  = { status, ts -> entry.updateStatus(status, ts) },
            )
            val args = cmd.substringAfter(' ', "")
            val result = ScriptBridge.invokeScriptCommand(scriptCmd, session.beaconId, args)
            if (result != null) {
                ScriptBridge.invokeSyncOutput(session.beaconId, result.toString())
            }
            scrollToBottom()
            //return
        }

//        val entry = TaskEntry(cmd)
//        outputPane.children.add(entry.node)
//        pendingQueue.addLast(entry)
//        scrollToBottom()
//
//        requestStream.onNext(
//            TaskRequest.newBuilder()
//                .setBeaconId(session.beaconId)
//                .setTaskType(TaskType.TASK_TYPE_PING)
//                .setCommandLine(cmd)
//                .build()
//        )
    }

    private fun handleResponse(response: Tasks.TaskResponse) {
        val id = response.taskId.toUInt()
        when (response.status) {
            TaskStatus.TASK_STATUS_PENDING -> {
                val entry = pendingQueue.removeFirstOrNull() ?: return
                taskEntries[id] = entry
                entry.setTaskId(id)
                entry.updateStatus(response.status, response.timestamp.seconds)
            }
            TaskStatus.TASK_STATUS_COMPLETE -> {
                taskEntries[id]?.apply {
                    updateStatus(response.status, response.timestamp.seconds)
                    if (response.hasOutput()) setOutput(response.output.toByteArray())
                }
                scrollToBottom()
            }
            else -> taskEntries[id]?.updateStatus(response.status, response.timestamp.seconds)
        }
    }

    private fun scrollToBottom() {
        Platform.runLater { scrollPane.vvalue = 1.0 }
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private class TaskEntry(commandLine: String) {
    private val headerLabel = Label("» $commandLine").apply { styleClass.add("task-command") }
    private val metaLabel   = Label("queuing…").apply { styleClass.add("task-meta") }
    private var hasTaskId   = false
    private val outputLabel = Label().apply {
        styleClass.add("task-output")
        isWrapText = true
        isVisible = false
        isManaged = false
    }
    val node = VBox(4.0, HBox(12.0, headerLabel, metaLabel).apply { alignment = Pos.CENTER_LEFT }, outputLabel).apply {
        styleClass.add("task-entry")
    }

    fun setTaskId(id: UInt) {
        hasTaskId = true
        metaLabel.text = "#$id  pending…"
    }

    fun updateStatus(status: TaskStatus, epochSeconds: Long) {
        val time = TIME_FMT.format(Instant.ofEpochSecond(epochSeconds))
        val (text, color) = when (status) {
            TaskStatus.TASK_STATUS_PENDING  -> "pending…"  to "#6e6e6e"
            TaskStatus.TASK_STATUS_TASKED   -> "tasked"    to "#e5c07b"
            TaskStatus.TASK_STATUS_COMPLETE -> "✓ $time"   to "#4ec94e"
            else                            -> "unknown"   to "#6e6e6e"
        }
        metaLabel.text = "${metaLabel.text.substringBefore(" ")}  $text"
        metaLabel.style = "-fx-text-fill: $color;"
    }

    fun setOutput(bytes: ByteArray) {
        outputLabel.text = String(bytes, Charsets.UTF_8).trimEnd()
        outputLabel.isVisible = true
        outputLabel.isManaged = true
    }

    fun setScriptOutput(text: String?) {
        if (!hasTaskId) {
            metaLabel.isVisible = false
            metaLabel.isManaged = false
        }
        if (text != null) {
            outputLabel.text = text
            outputLabel.isVisible = true
            outputLabel.isManaged = true
        }
    }
}