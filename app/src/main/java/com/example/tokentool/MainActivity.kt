package com.example.tokentool

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tokentool.ui.theme.TokenToolTheme
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private const val DOMAIN_PHYSICAL_ACTIVITY = "physical_activity"
private const val DOMAIN_SLEEP = "sleep"
private const val DOMAIN_MOOD = "mood"

private const val COLOR_GREEN = "green"
private const val COLOR_BLUE = "blue"
private const val COLOR_YELLOW = "yellow"
private const val COLOR_RED = "red"

private val DOMAIN_OPTIONS = listOf(DOMAIN_PHYSICAL_ACTIVITY, DOMAIN_SLEEP, DOMAIN_MOOD)
private val COLOR_OPTIONS = listOf(COLOR_GREEN, COLOR_BLUE, COLOR_YELLOW, COLOR_RED)

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf(ScreenState())
    private val writerConfigRef = AtomicReference(
        WriterConfig(
            form = TokenForm(),
            autoIncrement = true,
            incrementStep = 1
        )
    )

    private var nfcAdapter: NfcAdapter? = null
    private var lastTagSignature: String = ""
    private var lastTagHandledAtMs: Long = 0L

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        handleTagDiscovered(tag)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        refreshNfcStatus()
        syncWriterConfig()

        setContent {
            TokenToolTheme {
                TokenWriterScreen(
                    state = screenState,
                    onTokenIdChange = { updateForm(screenState.form.copy(tokenId = it)) },
                    onDomainTypeChange = { updateForm(screenState.form.copy(domainType = it)) },
                    onColorChange = { updateForm(screenState.form.copy(color = it)) },
                    onUserNoteChange = { updateForm(screenState.form.copy(userNote = it)) },
                    onAutoIncrementChange = { checked ->
                        screenState = screenState.copy(autoIncrement = checked)
                        syncWriterConfig()
                    },
                    onIncrementStepChange = { text ->
                        screenState = screenState.copy(incrementStepInput = text)
                        syncWriterConfig()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNfcStatus()
        enableReaderModeIfPossible()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun updateForm(nextForm: TokenForm) {
        screenState = screenState.copy(form = nextForm)
        syncWriterConfig()
    }

    private fun syncWriterConfig() {
        val step = screenState.incrementStepInput.toIntOrNull()?.let { max(it, 1) } ?: 1
        writerConfigRef.set(
            WriterConfig(
                form = screenState.form,
                autoIncrement = screenState.autoIncrement,
                incrementStep = step
            )
        )
    }

    private fun refreshNfcStatus() {
        val adapter = nfcAdapter
        val supported = adapter != null
        val enabled = adapter?.isEnabled == true
        val statusMessage = when {
            !supported -> "This device does not support NFC."
            !enabled -> "NFC is turned off. Enable NFC in system settings, then return."
            else -> "Ready. Tap a token to write JSON. The app stays armed for the next token."
        }
        val statusType = when {
            !supported || !enabled -> StatusType.Error
            else -> screenState.statusType.takeUnless { it == StatusType.Success } ?: StatusType.Info
        }
        screenState = screenState.copy(
            isNfcSupported = supported,
            isNfcEnabled = enabled,
            statusMessage = if (screenState.writeCount == 0 || !enabled || !supported) {
                statusMessage
            } else {
                screenState.statusMessage
            },
            statusType = statusType
        )
    }

    private fun enableReaderModeIfPossible() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150)
        }
        adapter.enableReaderMode(this, readerCallback, flags, options)
    }

    private fun handleTagDiscovered(tag: Tag) {
        val config = writerConfigRef.get()

        val tokenId = config.form.tokenId.trim()
        if (tokenId.isEmpty()) {
            runOnUiThread {
                screenState = screenState.copy(
                    statusMessage = "Token ID cannot be empty.",
                    statusType = StatusType.Error
                )
            }
            return
        }

        val tagIdHex = tag.id?.toHexString().orEmpty().ifBlank { "unknown" }
        if (shouldDebounceTag(tagIdHex)) return

        val jsonPayload = config.form.toJsonString(tokenId = tokenId)

        runOnUiThread {
            screenState = screenState.copy(
                statusMessage = "Tag detected ($tagIdHex). Writing...",
                statusType = StatusType.Info
            )
        }

        try {
            writeJsonToTag(tag, jsonPayload)
            runOnUiThread {
                onWriteSuccess(tagIdHex, config.incrementStep, config.autoIncrement)
            }
        } catch (e: Exception) {
            runOnUiThread {
                screenState = screenState.copy(
                    statusMessage = "Write failed: ${e.message ?: e.javaClass.simpleName}",
                    statusType = StatusType.Error
                )
            }
        }
    }

    private fun onWriteSuccess(tagIdHex: String, incrementStep: Int, autoIncrement: Boolean) {
        var nextState = screenState.copy(
            writeCount = screenState.writeCount + 1,
            lastTagIdHex = tagIdHex,
            statusMessage = "Write successful (#${screenState.writeCount + 1}) on tag $tagIdHex. Remove token and tap the next one.",
            statusType = StatusType.Success
        )

        if (autoIncrement) {
            val currentId = nextState.form.tokenId.trim()
            val numericId = currentId.toLongOrNull()
            if (numericId != null) {
                nextState = nextState.copy(
                    form = nextState.form.copy(tokenId = (numericId + incrementStep).toString())
                )
            } else {
                nextState = nextState.copy(
                    statusMessage = "${nextState.statusMessage} Auto-increment skipped (tokenId is not numeric)."
                )
            }
        }

        screenState = nextState
        syncWriterConfig()
    }

    @Synchronized
    private fun shouldDebounceTag(tagIdHex: String): Boolean {
        val now = System.currentTimeMillis()
        val sameTag = tagIdHex == lastTagSignature
        val tooSoon = now - lastTagHandledAtMs < 1200L
        if (sameTag && tooSoon) return true

        lastTagSignature = tagIdHex
        lastTagHandledAtMs = now
        return false
    }

    @Throws(IOException::class, FormatException::class)
    private fun writeJsonToTag(tag: Tag, jsonPayload: String) {
        val message = NdefMessage(arrayOf(createTextRecord(jsonPayload)))
        val bytes = message.toByteArray()

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) {
                    throw IOException("Tag is read-only.")
                }
                if (ndef.maxSize < bytes.size) {
                    throw IOException("Tag too small (${ndef.maxSize} bytes, needs ${bytes.size}).")
                }
                ndef.writeNdefMessage(message)
                return
            } finally {
                try {
                    ndef.close()
                } catch (_: IOException) {
                    // Ignore close errors after a write attempt.
                }
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: throw IOException("Tag does not support NDEF writing.")

        try {
            formatable.connect()
            formatable.format(message)
        } finally {
            try {
                formatable.close()
            } catch (_: IOException) {
                // Ignore close errors after a format/write attempt.
            }
        }
    }

    private fun createTextRecord(text: String): NdefRecord {
        val languageCodeBytes = "en".toByteArray(StandardCharsets.US_ASCII)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + languageCodeBytes.size + textBytes.size)
        payload[0] = languageCodeBytes.size.toByte()
        System.arraycopy(languageCodeBytes, 0, payload, 1, languageCodeBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + languageCodeBytes.size, textBytes.size)
        return NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            NdefRecord.RTD_TEXT,
            ByteArray(0),
            payload
        )
    }
}

private data class TokenForm(
    val tokenId: String = "0",
    val domainType: String = DOMAIN_PHYSICAL_ACTIVITY,
    val color: String = COLOR_GREEN,
    val userNote: String = ""
) {
    fun toJsonString(tokenId: String = this.tokenId): String {
        return JSONObject()
            .put("tokenId", tokenId)
            .put("domainType", domainType)
            .put("color", color)
            .put("userNote", userNote)
            .toString()
    }

    fun toPrettyJson(): String {
        return JSONObject()
            .put("tokenId", tokenId.trim())
            .put("domainType", domainType)
            .put("color", color)
            .put("userNote", userNote)
            .toString(2)
    }
}

private data class WriterConfig(
    val form: TokenForm,
    val autoIncrement: Boolean,
    val incrementStep: Int
)

private data class ScreenState(
    val form: TokenForm = TokenForm(),
    val autoIncrement: Boolean = true,
    val incrementStepInput: String = "1",
    val writeCount: Int = 0,
    val lastTagIdHex: String = "",
    val isNfcSupported: Boolean = true,
    val isNfcEnabled: Boolean = true,
    val statusMessage: String = "Ready. Tap a token to write JSON.",
    val statusType: StatusType = StatusType.Info
)

private enum class StatusType {
    Info,
    Success,
    Error
}

@Composable
private fun TokenWriterScreen(
    state: ScreenState,
    onTokenIdChange: (String) -> Unit,
    onDomainTypeChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onUserNoteChange: (String) -> Unit,
    onAutoIncrementChange: (Boolean) -> Unit,
    onIncrementStepChange: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "NFC Token Writer",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Enter token data once, then keep tapping tokens. The app remains in write mode while this screen is open.",
                style = MaterialTheme.typography.bodyMedium
            )

            StatusCard(state)

            OutlinedTextField(
                value = state.form.tokenId,
                onValueChange = onTokenIdChange,
                label = { Text("Token ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OptionGroup(
                title = "Domain Type",
                options = DOMAIN_OPTIONS,
                selected = state.form.domainType,
                onSelect = onDomainTypeChange
            )

            OptionGroup(
                title = "Color",
                options = COLOR_OPTIONS,
                selected = state.form.color,
                onSelect = onColorChange
            )

            OutlinedTextField(
                value = state.form.userNote,
                onValueChange = onUserNoteChange,
                label = { Text("User Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Auto-increment Token ID after each successful write",
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Switch(
                            checked = state.autoIncrement,
                            onCheckedChange = onAutoIncrementChange
                        )
                    }
                    if (state.autoIncrement) {
                        OutlinedTextField(
                            value = state.incrementStepInput,
                            onValueChange = onIncrementStepChange,
                            label = { Text("Increment Step") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(
                            text = "If the step is invalid, the app uses 1.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                text = "JSON Preview (stored as NFC NDEF text record)",
                style = MaterialTheme.typography.titleMedium
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = state.form.toPrettyJson(),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tip: Use hexagon tokens for physical activity, circle for sleep, star for mood, and diamond for special tracking.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusCard(state: ScreenState) {
    val containerColor = when (state.statusType) {
        StatusType.Info -> MaterialTheme.colorScheme.primaryContainer
        StatusType.Success -> MaterialTheme.colorScheme.tertiaryContainer
        StatusType.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (state.statusType) {
        StatusType.Info -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusType.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusType.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Writes completed: ${state.writeCount}",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.lastTagIdHex.isNotBlank()) {
                Text(
                    text = "Last tag ID: ${state.lastTagIdHex}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            if (!state.isNfcSupported || !state.isNfcEnabled) {
                Text(
                    text = "NFC must be supported and enabled on this device.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun OptionGroup(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = option == selected,
                        onClick = null
                    )
                    Text(
                        text = option,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

@Preview(showBackground = true)
@Composable
private fun TokenWriterScreenPreview() {
    TokenToolTheme {
        TokenWriterScreen(
            state = ScreenState(
                statusMessage = "Ready. Tap a token to write JSON.",
                writeCount = 3,
                lastTagIdHex = "04A2BC9D"
            ),
            onTokenIdChange = {},
            onDomainTypeChange = {},
            onColorChange = {},
            onUserNoteChange = {},
            onAutoIncrementChange = {},
            onIncrementStepChange = {}
        )
    }
}
