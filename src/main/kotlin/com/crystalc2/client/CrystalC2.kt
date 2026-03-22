package com.crystalc2.client

import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.input.KeyCombination
import javafx.stage.Stage

class CrystalC2 : Application() {
    override fun start(stage: Stage) {
        ClientState.load()

        val loginLoader = FXMLLoader(getResource("login.fxml"))
        val loginScene = Scene(loginLoader.load())
        loginScene.stylesheets.add(getResource("styles.css")!!.toExternalForm())
        val loginStage = Stage().apply {
            title = "CrystalC2"
            scene = loginScene
            isResizable = false
            applyIcon(this)
        }
        loginStage.showAndWait()
        if (!loginLoader.getController<LoginController>().succeeded) {
            Platform.exit()
            return
        }

        CommandRegistry.register(
            Command(
                name = "Listeners",
                description = "Create and remove listeners",
                shortcut = KeyCombination.keyCombination("Ctrl+Shift+L"),
                action = { openWindow("listeners.fxml", "Listeners") }
            ),
            Command(
                name = "Scripts",
                description = "Load and unload Sleep scripts",
                shortcut = KeyCombination.keyCombination("Ctrl+Shift+S"),
                action = { openWindow("scripts.fxml", "Scripts") }
            ),
            Command(
                name = "Payload",
                description = "Build a beacon payload",
                shortcut = KeyCombination.keyCombination("Ctrl+Shift+B"),
                action = { openWindow("payload.fxml", "Payload") }
            ),
            Command(
                name = "Interact",
                description = "Interact with a Beacon by tag",
                shortcut = KeyCombination.keyCombination("Ctrl+Shift+#"),
                action = {}
            ),
            Command(
                name = "About",
                description = "About CrystalC2",
                shortcut = KeyCombination.keyCombination("Ctrl+Shift+A"),
                action = { openWindow("about.fxml", "About") }
            )
        )

        val loader = FXMLLoader(getResource("main.fxml"))
        val scene = Scene(loader.load())
        scene.stylesheets.add(getResource("styles.css")!!.toExternalForm())
        CommandRegistry.registerAccelerators(scene)
        val controller = loader.getController<MainController>()
        scene.accelerators[KeyCombination.keyCombination("Ctrl+Shift+P")] = Runnable {
            controller.focusCommandPalette()
        }

        stage.title = "CrystalC2"
        stage.scene = scene
        applyIcon(stage)
        stage.show()

        ClientState.restoreScripts()
        openWindow("listeners.fxml", "Listeners")
        openWindow("scripts.fxml", "Scripts")
    }

    override fun stop() {
        GrpcClient.shutdown()
    }

    companion object {
        fun openWindow(fxml: String, title: String) {
            val stage = Stage()
            val scene = Scene(FXMLLoader(CrystalC2::class.java.getResource(fxml)).load())
            scene.stylesheets.add(CrystalC2::class.java.getResource("styles.css")!!.toExternalForm())
            CommandRegistry.registerAccelerators(scene)
            stage.scene = scene
            stage.title = "CrystalC2 :: $title"
            applyIcon(stage)
            stage.show()
        }

        fun openInteractionWindow(session: beacon.Beacons.BeaconSession) {
            val stage = Stage()
            val loader = FXMLLoader(getResource("interaction.fxml"))
            val scene = Scene(loader.load())
            scene.stylesheets.add(getResource("styles.css")!!.toExternalForm())
            loader.getController<InteractionController>().init(session)
            stage.scene = scene
            stage.title = "CrystalC2 :: ${session.user}@${session.computer}"
            applyIcon(stage)
            stage.show()
        }

        fun getResource(name: String) =
            CrystalC2::class.java.getResource(name)

        private fun applyIcon(stage: Stage) {
            getResource("icon.png")?.let { url ->
                stage.icons.add(Image(url.toExternalForm()))
            }
        }
    }
}