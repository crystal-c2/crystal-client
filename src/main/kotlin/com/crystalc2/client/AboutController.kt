package com.crystalc2.client

import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

class AboutController {
    @FXML private lateinit var creditsList: VBox

    private data class Credit(
        val name: String,
        val description: String,
        val license: String,
        val url: String,
    )

    private val credits = listOf(
        Credit(
            name        = "Crystal Palace",
            description = "2025-2026 Adversary Fan Fiction Writers Guild",
            license     = "BSD",
            url         = "https://tradecraftgarden.org/crystalpalace.html",
        ),
        Credit(
            name        = "Sleep",
            description = "2002-2020 Raphael Mudge",
            license     = "BSD",
            url         = "http://sleep.dashnine.org",
        ),
        Credit(
            name        = "armitage",
            description = "2010-2015 Raphael Mudge",
            license     = "BSD",
            url         = "https://github.com/rsmudge/armitage",
        )
    )

    @FXML
    fun initialize() {
        credits.forEach { credit ->
            creditsList.children.add(buildCreditRow(credit))
        }
    }

    private fun buildCreditRow(credit: Credit): VBox {
        val nameLabel = Label(credit.name).apply {
            styleClass.add("about-credit-name")
        }
        val licenseLabel = Label(credit.license).apply {
            styleClass.add("about-credit-license")
        }
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val topRow = HBox(spacer, nameLabel, licenseLabel).apply {
            alignment = Pos.CENTER_LEFT
            spacing = 8.0
        }
        // Swap spacer to push name left, license right
        topRow.children.setAll(nameLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, licenseLabel)

        val descLabel = Label(credit.description).apply {
            styleClass.add("about-credit-desc")
            isWrapText = true
        }
        val urlLabel = Label(credit.url).apply {
            styleClass.add("about-credit-url")
        }
        return VBox(4.0, topRow, descLabel, urlLabel).apply {
            styleClass.add("about-credit-row")
        }
    }
}