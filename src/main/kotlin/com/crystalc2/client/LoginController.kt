package com.crystalc2.client

import auth.Auth.LoginRequest
import auth.Auth.LoginResponse
import auth.AuthServiceGrpc
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.scene.layout.VBox

class LoginController {
    @FXML private lateinit var recentPane: VBox
    @FXML private lateinit var recentList: ListView<ServerEntry>
    @FXML private lateinit var hostField: TextField
    @FXML private lateinit var portField: TextField
    @FXML private lateinit var usernameField: TextField
    @FXML private lateinit var passwordField: PasswordField
    @FXML private lateinit var loginButton: Button
    @FXML private lateinit var errorLabel: Label

    var succeeded = false
        private set

    @FXML
    fun initialize() {
        hostField.text = "localhost"
        portField.text = "5184"
        usernameField.setOnAction { onLogin() }
        passwordField.setOnAction { onLogin() }

        val servers = ClientState.servers
        if (servers.isNotEmpty()) {
            recentList.items.setAll(servers)
            recentList.setCellFactory { ServerCell() }
            recentList.setOnMouseClicked { e ->
                val entry = recentList.selectionModel.selectedItem ?: return@setOnMouseClicked
                fillFrom(entry)
                if (e.button == MouseButton.PRIMARY && e.clickCount == 2) onLogin()
            }
            recentPane.isVisible = true
            recentPane.isManaged = true
        }
    }

    private fun showError(msg: String) {
        loginButton.isDisable = false
        loginButton.text = "Login"
        errorLabel.text = msg
    }

    private fun fillFrom(entry: ServerEntry) {
        hostField.text = entry.host
        portField.text = entry.port.toString()
        usernameField.text = entry.username
        passwordField.requestFocus()
    }

    @FXML
    fun onLogin() {
        val host = hostField.text.trim().ifEmpty { "localhost" }
        val port = portField.text.trim().toIntOrNull() ?: run {
            errorLabel.text = "Invalid port number"; return
        }
        val username = usernameField.text.trim()
        val password = passwordField.text

        if (username.isEmpty()) { errorLabel.text = "Username is required"; return }
        if (password.isEmpty()) { errorLabel.text = "Password is required"; return }

        loginButton.isDisable = true
        loginButton.text = "Connecting…"
        errorLabel.text = ""

        val tempChannel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .build()

        AuthServiceGrpc.newStub(tempChannel)
            .login(
                LoginRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(password)
                    .build(),
                object : StreamObserver<LoginResponse> {
                    private var gotResponse = false

                    override fun onNext(response: LoginResponse) {
                        gotResponse = true
                        tempChannel.shutdown()
                        GrpcClient.init(host, port, response.token)
                        ClientState.saveServer(host, port, username)
                        Platform.runLater {
                            succeeded = true
                            loginButton.scene.window.hide()
                        }
                    }
                    override fun onError(t: Throwable) {
                        tempChannel.shutdown()
                        val msg = when (t) {
                            is StatusRuntimeException ->
                                t.status.description?.takeIf { it.isNotBlank() }
                                    ?: t.status.code.name.lowercase().replace('_', ' ')
                            else -> t.message ?: "Login failed"
                        }
                        Platform.runLater { showError(msg) }
                    }
                    override fun onCompleted() {
                        if (!gotResponse) {
                            tempChannel.shutdown()
                            Platform.runLater { showError("Login failed") }
                        }
                    }
                }
            )
    }
}

private class ServerCell : ListCell<ServerEntry>() {
    override fun updateItem(entry: ServerEntry?, empty: Boolean) {
        super.updateItem(entry, empty)
        text = if (empty || entry == null) null else entry.toString()
    }
}