package com.akuras.pdfscanner

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PDFScannerTheme {
                SettingsScreen(
                    initialPattern = loadFileNamePattern(this),
                    onPatternChanged = { saveFileNamePattern(this, it) },
                    onBack = { finish() }
                )
            }
        }
    }
}

private data class Token(val label: String, val value: String)

private val tokens = listOf(
    Token("Year (2026)", "{yyyy}"),
    Token("Year (26)", "{yy}"),
    Token("Month (03)", "{MM}"),
    Token("Month (Mar)", "{MMM}"),
    Token("Month (March)", "{MMMM}"),
    Token("Day (09)", "{dd}"),
    Token("Hour (14)", "{HH}"),
    Token("Minute (05)", "{mm}"),
    Token("Second (30)", "{ss}"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    initialPattern: String,
    onPatternChanged: (String) -> Unit,
    onBack: () -> Unit,
) {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialPattern, TextRange(initialPattern.length)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("File Name Template", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Set the default name for scanned PDFs. Tap tokens below to insert them at the cursor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    onPatternChanged(it.text)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("File name pattern") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            val preview = resolveFileName(textFieldValue.text)
            Text(
                text = "Preview: $preview.pdf",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Insert Token", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tokens.forEach { token ->
                    AssistChip(
                        onClick = {
                            val cursor = textFieldValue.selection.start
                            val newText = textFieldValue.text.substring(0, cursor) +
                                    token.value +
                                    textFieldValue.text.substring(cursor)
                            val newCursor = cursor + token.value.length
                            textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                            onPatternChanged(newText)
                        },
                        label = { Text(token.label) }
                    )
                }
            }
        }
    }
}

const val PREFS_NAME = "pdfscanner_prefs"
const val KEY_FILE_PATTERN = "file_name_pattern"
const val DEFAULT_PATTERN = "scan_{yyyy}{MM}{dd}_{HH}{mm}{ss}"

fun loadFileNamePattern(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_FILE_PATTERN, DEFAULT_PATTERN) ?: DEFAULT_PATTERN
}

fun saveFileNamePattern(context: Context, pattern: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_FILE_PATTERN, pattern)
        .apply()
}

fun resolveFileName(pattern: String): String {
    val now = LocalDateTime.now()
    return pattern
        .replace("{yyyy}", now.format(DateTimeFormatter.ofPattern("yyyy")))
        .replace("{yy}", now.format(DateTimeFormatter.ofPattern("yy")))
        .replace("{MMMM}", now.format(DateTimeFormatter.ofPattern("MMMM")))
        .replace("{MMM}", now.format(DateTimeFormatter.ofPattern("MMM")))
        .replace("{MM}", now.format(DateTimeFormatter.ofPattern("MM")))
        .replace("{dd}", now.format(DateTimeFormatter.ofPattern("dd")))
        .replace("{HH}", now.format(DateTimeFormatter.ofPattern("HH")))
        .replace("{mm}", now.format(DateTimeFormatter.ofPattern("mm")))
        .replace("{ss}", now.format(DateTimeFormatter.ofPattern("ss")))
}
