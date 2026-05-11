package com.akuras.pdfscanner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanButtonIsVisible() {
        composeRule.onNodeWithText("Scan Document").assertIsDisplayed()
    }

    @Test
    fun historyPanelHeaderIsVisible() {
        composeRule.onNodeWithText("Saved PDFs").assertIsDisplayed()
    }

    @Test
    fun emptyStateMessageIsVisible() {
        composeRule.onNodeWithText("No scanned documents yet").assertIsDisplayed()
    }
}
