package com.crystalc2.client

import com.google.protobuf.ByteString
import crystalpalace.spec.SpecLogger
import crystalpalace.spec.SpecMessage
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import sleep.bridges.SleepClosure
import sleep.bridges.io.IOObject
import sleep.interfaces.Function
import sleep.interfaces.Loadable
import sleep.runtime.Scalar
import sleep.runtime.ScriptInstance
import sleep.runtime.ScriptLoader
import sleep.runtime.SleepUtils
import tasks.TaskServiceGrpc
import tasks.Tasks
import tasks.Tasks.TaskRequest
import tasks.Tasks.TaskStatus
import tasks.Tasks.TaskType
import java.io.File
import java.util.Stack

data class ScriptCommand(
    val name: String,
    val description: String,
    val longDescription: String? = null,
    val closure: SleepClosure? = null,
    val handler: ((beaconId: Int, args: String) -> String?)? = null,
)

object ScriptBridge {
    private val loader = ScriptLoader()

    /** Set by ScriptsController when the scripts window is open. Receives raw text from script print/println. */
    var consoleOutput: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) ConsoleOutputStream.flushPending(value)
        }

    private object ConsoleOutputStream : java.io.OutputStream() {
        private val buf     = StringBuilder()
        private val pending = StringBuilder()

        override fun write(b: Int) {
            val c = (b and 0xFF).toChar()
            buf.append(c)
            if (c == '\n') flushBuffer()
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            val text = String(bytes, off, len)
            buf.append(text)
            if ('\n' in text) flushBuffer()
        }

        override fun flush() = flushBuffer()

        fun flushPending(sink: (String) -> Unit) {
            if (pending.isEmpty()) return
            val text = pending.toString()
            pending.clear()
            Platform.runLater { sink(text) }
        }

        private fun flushBuffer() {
            if (buf.isEmpty()) return
            val text = buf.toString()
            buf.clear()
            val sink = consoleOutput
            if (sink != null) Platform.runLater { sink(text) }
            else pending.append(text)
        }
    }

    private val registeredFunctions = mutableMapOf<String, (List<Any?>) -> Any?>()
    private val fileInstances = mutableMapOf<String, ScriptInstance>()
    private val scriptCommands = mutableMapOf<String, ScriptCommand>()

    // Task stream shared across all script-initiated beacon tasks
    private val scriptTaskQueue = ArrayDeque<Pair<Int, SleepClosure?>>()
    private val scriptTaskCallbacks = mutableMapOf<UInt, Pair<Int, SleepClosure?>>()
    private var scriptTaskStream: StreamObserver<TaskRequest>? = null

    // Hooks bundle for one script-initiated task entry in the interact view
    private data class EntryHooks(
        val onOutput:  (String?) -> Unit,
        val onPending: (taskId: UInt, timestamp: Long) -> Unit,
        val onStatus:  (status: TaskStatus, timestamp: Long) -> Unit,
    )

    // Queued per beacon in submission order; dequeued when PENDING is acknowledged
    private val pendingHookQueues = mutableMapOf<Int, ArrayDeque<EntryHooks>>()
    // Promoted to task-keyed once we know the task ID
    private val taskHooks = mutableMapOf<UInt, EntryHooks>()
    // Activated just before the closure is called at COMPLETE so blog() can find it
    private val activeOutputs = mutableMapOf<Int, (String?) -> Unit>()

    fun enqueueEntryHooks(
        beaconId:  Int,
        onOutput:  (String?) -> Unit,
        onPending: (UInt, Long) -> Unit,
        onStatus:  (TaskStatus, Long) -> Unit,
    ) {
        pendingHookQueues.getOrPut(beaconId) { ArrayDeque() }
            .addLast(EntryHooks(onOutput, onPending, onStatus))
    }

    /** Pop without firing — used when a sync command returns a value directly. */
    fun invokeSyncOutput(beaconId: Int, output: String) {
        pendingHookQueues[beaconId]?.removeFirstOrNull()?.onOutput?.invoke(output)
    }

    fun consumeActiveOutput(beaconId: Int): ((String?) -> Unit)? = activeOutputs.remove(beaconId)

    private fun ensureTaskStream(): StreamObserver<TaskRequest> =
        scriptTaskStream ?: TaskServiceGrpc.newStub(GrpcClient.channel)
            .streamTasks(object : StreamObserver<Tasks.TaskResponse> {
                override fun onNext(response: Tasks.TaskResponse) {
                    val id = response.taskId.toUInt()
                    when (response.status) {
                        TaskStatus.TASK_STATUS_PENDING -> {
                            val (beaconId, closure) = scriptTaskQueue.removeFirst()
                            scriptTaskCallbacks[id] = beaconId to closure
                            pendingHookQueues[beaconId]?.removeFirstOrNull()?.let { hooks ->
                                taskHooks[id] = hooks
                                Platform.runLater { hooks.onPending(id, response.timestamp.seconds) }
                            }
                        }
                        TaskStatus.TASK_STATUS_TASKED -> {
                            taskHooks[id]?.let { hooks ->
                                Platform.runLater { hooks.onStatus(TaskStatus.TASK_STATUS_TASKED, response.timestamp.seconds) }
                            }
                        }
                        TaskStatus.TASK_STATUS_COMPLETE -> {
                            val (beaconId, closure) = scriptTaskCallbacks.remove(id) ?: return
                            val hooks = taskHooks.remove(id)
                            val output = if (response.hasOutput())
                                String(response.output.toByteArray(), Charsets.UTF_8).trimEnd()
                            else ""
                            Platform.runLater {
                                hooks?.onStatus(TaskStatus.TASK_STATUS_COMPLETE, response.timestamp.seconds)
                                if (closure != null) {
                                    if (hooks != null) activeOutputs[beaconId] = hooks.onOutput
                                    val stack = Stack<Scalar>().apply {
                                        push(toScalar(output))   // $2
                                        push(toScalar(beaconId)) // $1
                                    }
                                    closure.callClosure("&callback", closure.owner, stack)
                                    activeOutputs.remove(beaconId) // clean up if blog() was never called
                                } else {
                                    // No callback — write output directly to the entry
                                    hooks?.onOutput?.invoke(output)
                                        ?: InteractionController.forBeacon(beaconId)?.postScriptOutput(output)
                                }
                            }
                        }
                        else -> {}
                    }
                }
                override fun onError(t: Throwable) {
                    System.err.println("[ScriptBridge] task stream error: ${t.message}")
                    scriptTaskStream = null
                }
                override fun onCompleted() { scriptTaskStream = null }
            }).also { scriptTaskStream = it }

    init {
        val consolePrintStream = java.io.PrintStream(ConsoleOutputStream, true)
        System.setOut(consolePrintStream)
        System.setErr(consolePrintStream)
        loader.addGlobalBridge(KotlinFunctionBridge())
        scriptCommands["help"] = ScriptCommand("help", "List available beacon commands",
            handler = { _, args ->
                val token = args.trim()
                if (token.isBlank()) {
                    scriptCommands.values
                        .filter { it.name != "help" }
                        .sortedBy { it.name }
                        .joinToString("\n") { "${it.name.padEnd(16)}${it.description}" }
                        .ifEmpty { "No commands registered." }
                } else {
                    val cmd = scriptCommands[token]
                    when {
                        cmd == null                 -> "Unknown command: $token"
                        cmd.longDescription != null -> cmd.longDescription
                        else                        -> cmd.description
                    }
                }
            }
        )
        scriptCommands["exit"] = ScriptCommand("exit", "Exit Beacon", "Task the current Beacon to exit",
            handler = { bid, _ ->
                scriptTaskQueue.addLast(bid to null)
                ensureTaskStream().onNext(
                    TaskRequest.newBuilder()
                        .setBeaconId(bid)
                        .setTaskType(TaskType.TASK_TYPE_EXIT)
                        .setCommandLine("exit")
                        .build()
                )
                null
            }
        )
        scriptCommands["sleep"] = ScriptCommand("sleep", "Change the sleep and/or jitter", "sleep [interval] <jitter>",
            handler = { bid, args ->
                val tokens = args.trim().split(Regex("\\s+"))
                val interval = tokens.getOrNull(0)?.toIntOrNull()
                    ?: return@ScriptCommand "interval is required"
                val jitter = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                val taskData = java.io.ByteArrayOutputStream().also {
                    it.write(beInt(interval))
                    it.write(beInt(jitter))
                }.toByteArray()
                scriptTaskQueue.addLast(bid to null)
                ensureTaskStream().onNext(
                    TaskRequest.newBuilder()
                        .setBeaconId(bid)
                        .setTaskType(TaskType.TASK_TYPE_SLEEP)
                        .setTaskData(ByteString.copyFrom(taskData))
                        .setCommandLine("sleep $interval $jitter")
                        .build()
                )
                null
            }
        )
        register("register_command") { args ->
            val name     = args.getOrNull(0) as? String ?: return@register null
            val desc     = args.getOrNull(1) as? String ?: ""
            val longDesc = args.getOrNull(2) as? String
            val closure  = args.getOrNull(3) as? SleepClosure ?: return@register null
            scriptCommands[name] = ScriptCommand(name, desc, longDesc, closure)
            null
        }
        register("bexit") { args ->
            val bid = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return@register null
            val closure  = args.getOrNull(1) as? SleepClosure ?: return@register null
            scriptTaskQueue.add(bid to closure)
            ensureTaskStream().onNext(
                TaskRequest.newBuilder()
                    .setBeaconId(bid)
                    .setTaskType(TaskType.TASK_TYPE_EXIT)
                    .setCommandLine("exit")
                    .build()
            )
            null
        }
        register("bsleep") { args ->
            val bid = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return@register null
            val closure  = args.getOrNull(1) as? SleepClosure ?: return@register null
            scriptTaskQueue.add(bid to closure)
            ensureTaskStream().onNext(
                TaskRequest.newBuilder()
                    .setBeaconId(bid)
                    .setTaskType(TaskType.TASK_TYPE_SLEEP)
                    .setCommandLine("sleep")
                    .build()
            )
            null
        }
        register("binline_execute") { args ->
            val bid        = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return@register null
            val bof        = extractBytes(args.getOrNull(1)) ?: return@register null
            val packedArgs = extractBytes(args.getOrNull(2)) ?: ByteArray(0)
            val callback   = args.getOrNull(3) as? SleepClosure

            val taskData = java.io.ByteArrayOutputStream().also {
                it.write(beInt(bof.size))
                it.write(bof)
                it.write(packedArgs)
            }.toByteArray()

            scriptTaskQueue.addLast(bid to callback)
            ensureTaskStream().onNext(
                TaskRequest.newBuilder()
                    .setBeaconId(bid)
                    .setTaskType(TaskType.TASK_TYPE_PICO)
                    .setTaskData(ByteString.copyFrom(taskData))
                    .setCommandLine("inline_execute")
                    .build()
            )
            null
        }
        register("bof_pack") { args ->
            val beaconId = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return@register null
            val format   = args.getOrNull(1) as? String ?: return@register null
            val charset  = charsetForBeacon(beaconId)
            val data     = java.io.ByteArrayOutputStream()
            var idx      = 2
            for (ch in format) {
                val arg = args.getOrNull(idx++)
                when (ch) {
                    'b' -> {
                        val bytes = extractBytes(arg) ?: ByteArray(0)
                        data.write(beInt(bytes.size))
                        data.write(bytes)
                    }
                    'i' -> data.write(beInt(arg?.toString()?.toIntOrNull() ?: 0))
                    's' -> data.write(beShort(arg?.toString()?.toIntOrNull() ?: 0))
                    'z' -> {
                        val bytes = (arg?.toString() ?: "").toByteArray(charset)
                        data.write(beInt(bytes.size + 1))
                        data.write(bytes)
                        data.write(0)
                    }
                    'Z' -> {
                        val bytes = (arg?.toString() ?: "").toByteArray(Charsets.UTF_16LE)
                        data.write(beInt(bytes.size + 2))
                        data.write(bytes)
                        data.write(0); data.write(0)
                    }
                }
            }
            val payload = data.toByteArray()
            java.io.ByteArrayOutputStream().also {
                it.write(beInt(payload.size))
                it.write(payload)
            }.toByteArray()
        }
        register("blog") { args ->
            val beaconId = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return@register null
            val output   = args.getOrNull(1)?.toString()
            val updater  = consumeActiveOutput(beaconId)
            if (updater != null) {
                updater(output)
            } else {
                InteractionController.forBeacon(beaconId)?.postScriptOutput(output)
            }
            null
        }
    }

    fun register(name: String, handler: (List<Any?>) -> Any?) {
        val bare = name.removePrefix("&")
        registeredFunctions["&$bare"] = handler
        registeredFunctions[bare] = handler
    }

    fun getScriptCommand(name: String): ScriptCommand? = scriptCommands[name]
    fun commandNames(): Set<String> = scriptCommands.keys

    private val beaconCharsets = mutableMapOf<Int, Int>()

    fun registerBeaconCharset(beaconId: Int, codepage: Int) {
        beaconCharsets[beaconId] = codepage
    }

    private fun charsetForBeacon(beaconId: Int): java.nio.charset.Charset {
        val page = beaconCharsets[beaconId] ?: return Charsets.UTF_8
        return try { java.nio.charset.Charset.forName("Cp$page") } catch (_: Exception) { Charsets.UTF_8 }
    }

    fun invokeScriptCommand(cmd: ScriptCommand, vararg args: Any?): Any? {
        cmd.handler?.let { handler ->
            val beaconId = args.getOrNull(0)?.toString()?.toIntOrNull() ?: 0
            val cmdArgs  = args.getOrNull(1)?.toString() ?: ""
            return handler(beaconId, cmdArgs)
        }
        val closure = cmd.closure ?: return null
        val stack = Stack<Scalar>().apply {
            args.reversed().forEach { push(toScalar(it)) }
        }
        val result = closure.callClosure("&${cmd.name}", closure.owner, stack)
        return if (result != null && !SleepUtils.isEmptyScalar(result)) fromScalar(result) else null
    }

    fun loadDirectory(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles { f -> f.extension == "sl" }
            ?.sumOf { loadScript(it) }
            ?: 0
    }

    fun loadScript(file: File): Int = try {
        val instance = loader.loadScript(file)
        if (instance is ScriptInstance) {
            fileInstances[file.canonicalPath] = instance
            instance.runScript()
        }
        ClientState.save()
        1
    } catch (e: Exception) {
        System.err.println("[ScriptBridge] Failed to load ${file.name}: ${e.message}")
        0
    }

    fun unload(canonicalPath: String) {
        val instance = fileInstances.remove(canonicalPath) ?: return
        scriptCommands.values.removeIf { it.closure?.owner === instance }
        loader.unloadScript(instance)
        ClientState.save()
    }

    fun loadedScripts(): Set<String> = fileInstances.keys.toSet()

    fun invoke(scriptName: String, function: String, vararg args: Any?): Any? {
        val instance = loader.scriptsByKey[scriptName] as? ScriptInstance ?: return null
        return invokeOn(instance, function, args)
    }

    fun invokeAll(function: String, vararg args: Any?): List<Any?> {
        val funcKey = if (function.startsWith("&")) function else "&$function"
        return loader.scripts.mapNotNull { script ->
            val instance = script as? ScriptInstance ?: return@mapNotNull null
            if (instance.scriptEnvironment.getFunction(funcKey) != null)
                invokeOn(instance, function, args)
            else null
        }
    }

    fun toScalar(value: Any?): Scalar = when (value) {
        null       -> SleepUtils.getEmptyScalar()
        is Scalar  -> value
        is Boolean -> SleepUtils.getScalar(value)
        is Int     -> SleepUtils.getScalar(value)
        is Long    -> SleepUtils.getScalar(value)
        is Float   -> SleepUtils.getScalar(value)
        is Double  -> SleepUtils.getScalar(value)
        is String  -> SleepUtils.getScalar(value)
        is List<*> -> SleepUtils.getArrayWrapper(value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            SleepUtils.getHashWrapper(value as Map<Any?, Any?>)
        }
        else       -> SleepUtils.getScalar(value)
    }

    fun fromScalar(scalar: Scalar): Any? {
        if (SleepUtils.isEmptyScalar(scalar)) return null
        val obj = scalar.objectValue()
        if (obj != null && obj !is String) return obj
        return scalar.stringValue()
    }

    private fun invokeOn(instance: ScriptInstance, function: String, args: Array<out Any?>): Any? {
        val funcKey = if (function.startsWith("&")) function else "&$function"
        val stack = Stack<Scalar>().apply {
            // Sleep pops args in reverse order
            args.reversed().forEach { push(toScalar(it)) }
        }
        val result = instance.callFunction(funcKey, stack)
        return if (result != null && !SleepUtils.isEmptyScalar(result)) fromScalar(result) else null
    }

    private fun extractBytes(value: Any?): ByteArray? = when (value) {
        is ByteArray -> value
        is String    -> value.toByteArray(Charsets.ISO_8859_1)
        else         -> null
    }

    private fun beInt(value: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun beShort(value: Int): ByteArray =
        java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    private class KotlinFunctionBridge : Loadable {
        override fun scriptLoaded(script: ScriptInstance) {
            val console = IOObject().apply { openWrite(ConsoleOutputStream) }
            IOObject.setConsole(script.scriptEnvironment, console)
            script.addWarningWatcher { warning ->
                val sink = consoleOutput ?: return@addWarningWatcher
                val line = "${warning.getNameShort()}:${warning.lineNumber}: ${warning.message}\n"
                Platform.runLater { sink(line) }
            }
            val env = script.scriptEnvironment.getEnvironment()
            registeredFunctions.forEach { (key, handler) ->
                env[key] = SleepFunction(key, handler)
            }
        }

        override fun scriptUnloaded(script: ScriptInstance) {
            // nothing to clean up — the environment goes away with the script
        }
    }

    private class SleepFunction(private val name: String, private val handler: (List<Any?>) -> Any?, ) : Function {

        override fun evaluate(funcName: String, script: ScriptInstance, args: Stack<*>, ): Scalar {
            val converted = args.reversed().map { fromScalar(it as Scalar) }
            return try {
                toScalar(handler(converted))
            } catch (e: Exception) {
                script.scriptEnvironment.flagError(e)
                SleepUtils.getEmptyScalar()
            }
        }

        // fromScalar and toScalar mirror the outer object's helpers
        private fun fromScalar(s: Scalar): Any? {
            if (SleepUtils.isEmptyScalar(s)) return null
            val obj = s.objectValue()
            if (obj != null && obj !is String) return obj
            return s.stringValue()
        }

        private fun toScalar(value: Any?): Scalar = ScriptBridge.toScalar(value)
    }
}