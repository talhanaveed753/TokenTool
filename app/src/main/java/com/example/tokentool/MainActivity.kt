package com.example.tokentool

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.tokentool.ui.theme.TokenToolTheme
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private const val DOMAIN_PHYSICAL_ACTIVITY = "physical_activity"
private const val DOMAIN_SLEEP = "sleep"
private const val DOMAIN_MOOD = "mood"
private const val DOMAIN_SPECIAL = "special"

private const val COLOR_GREEN = "green"
private const val COLOR_BLUE = "blue"
private const val COLOR_YELLOW = "yellow"
private const val COLOR_RED = "red"

private const val PREFS_NAME = "token_writer_prefs"
private const val KEY_IDS_INITIALIZED = "token_ids_initialized"
private const val KEY_TOKEN_ID_PREFIX = "token_id_"
private const val WRITE_COOLDOWN_MS = 1000L
private const val WRITE_SUCCESS_TONE_DELAY_MS = 250L
private const val WIDE_SCAN_LAYOUT_BREAKPOINT_DP = 980

private val DOMAIN_OPTIONS = listOf(
    DOMAIN_PHYSICAL_ACTIVITY,
    DOMAIN_SLEEP,
    DOMAIN_MOOD,
    DOMAIN_SPECIAL
)
private val COLOR_OPTIONS = listOf(COLOR_GREEN, COLOR_BLUE, COLOR_YELLOW, COLOR_RED)

private val DOMAIN_SPECS = listOf(
    ChoiceSpec(
        value = DOMAIN_PHYSICAL_ACTIVITY,
        label = "Physical Activity",
        supporting = "Hexagon token • 1 token = 2000 steps"
    ),
    ChoiceSpec(
        value = DOMAIN_SLEEP,
        label = "Sleep",
        supporting = "Circle token • 1 token = 120 minutes"
    ),
    ChoiceSpec(
        value = DOMAIN_MOOD,
        label = "Mood",
        supporting = "Star token • Color tracks the mood"
    ),
    ChoiceSpec(
        value = DOMAIN_SPECIAL,
        label = "Special",
        supporting = "Diamond token • Anything trackable"
    )
)

private val COLOR_SPECS = listOf(
    ChoiceSpec(
        value = COLOR_GREEN,
        label = "Green",
        supporting = "Often used for happy mood tokens",
        accentColor = Color(0xFF3F8F4B)
    ),
    ChoiceSpec(
        value = COLOR_BLUE,
        label = "Blue",
        supporting = "Often used for sad mood tokens",
        accentColor = Color(0xFF3478B8)
    ),
    ChoiceSpec(
        value = COLOR_YELLOW,
        label = "Yellow",
        supporting = "Often used for frustrated mood tokens",
        accentColor = Color(0xFFD9A10D)
    ),
    ChoiceSpec(
        value = COLOR_RED,
        label = "Red",
        supporting = "Often used for panicked mood tokens",
        accentColor = Color(0xFFC94C3A)
    )
)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    private var screenState by mutableStateOf(ScreenState())
    private val runtimeConfigRef = AtomicReference(
        RuntimeConfig(
            page = AppPage.WRITE_SETUP,
            form = TokenForm(),
            tokenIdsByDomain = defaultTokenIdsMap(),
            autoIncrement = true,
            incrementStep = 1,
            writeCooldownUntilMs = 0L
        )
    )

    private val toneGenerator by lazy(LazyThreadSafetyMode.NONE) {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    }

    private var nfcAdapter: NfcAdapter? = null
    private var lastTagSignature: String = ""
    private var lastTagHandledAtMs: Long = 0L

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        handleTagDiscovered(tag)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        screenState = screenState.copy(tokenIdsByDomain = initializeAndLoadTokenIds())

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        refreshNfcAvailability()
        syncRuntimeConfig()

        setContent {
            TokenToolTheme {
                AppContent(
                    state = screenState,
                    onTokenIdChange = ::updateSelectedDomainTokenId,
                    onDomainTypeChange = { updateForm(screenState.form.copy(domainType = it)) },
                    onColorChange = { updateForm(screenState.form.copy(color = it)) },
                    onUserNoteChange = { updateForm(screenState.form.copy(userNote = it)) },
                    onAutoIncrementChange = { checked ->
                        screenState = screenState.copy(autoIncrement = checked)
                        syncRuntimeConfig()
                    },
                    onIncrementStepChange = { text ->
                        screenState = screenState.copy(incrementStepInput = text)
                        syncRuntimeConfig()
                    },
                    onOpenWriteScan = { navigateTo(AppPage.WRITE_SCAN) },
                    onOpenReadScan = { navigateTo(AppPage.READ_SCAN) },
                    onBackToSetup = { navigateTo(AppPage.WRITE_SETUP) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNfcAvailability()
        updateReaderMode()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onDestroy() {
        toneGenerator.release()
        super.onDestroy()
    }

    private fun initializeAndLoadTokenIds(): Map<String, String> {
        val editor = prefs.edit()
        if (!prefs.getBoolean(KEY_IDS_INITIALIZED, false)) {
            editor.putBoolean(KEY_IDS_INITIALIZED, true)
        }
        DOMAIN_OPTIONS.forEach { domainType ->
            if (!prefs.contains(tokenIdKey(domainType))) {
                editor.putString(tokenIdKey(domainType), "0")
            }
        }
        editor.apply()

        return DOMAIN_OPTIONS.associateWith { domainType ->
            prefs.getString(tokenIdKey(domainType), "0") ?: "0"
        }
    }

    private fun tokenIdKey(domainType: String): String = "$KEY_TOKEN_ID_PREFIX$domainType"

    private fun persistTokenId(domainType: String, tokenId: String) {
        prefs.edit().putString(tokenIdKey(domainType), tokenId).apply()
    }

    private fun navigateTo(page: AppPage) {
        screenState = when (page) {
            AppPage.WRITE_SETUP -> screenState.copy(page = page)
            AppPage.WRITE_SCAN -> {
                val nextMessage = if (
                    screenState.writeStatusType == StatusType.Info &&
                    screenState.writeCount == 0 &&
                    screenState.lastWrittenTagIdHex.isBlank() &&
                    screenState.writeCooldownUntilMs <= System.currentTimeMillis()
                ) {
                    "Ready to write. Hold a token over the center target."
                } else {
                    screenState.writeStatusMessage
                }
                screenState.copy(
                    page = page,
                    writeStatusMessage = nextMessage
                )
            }

            AppPage.READ_SCAN -> {
                val nextMessage = if (
                    screenState.readStatusType == StatusType.Info &&
                    screenState.readLastTagIdHex.isBlank() &&
                    screenState.readRawPayload.isBlank()
                ) {
                    "Ready to read. Hold a token over the center target."
                } else {
                    screenState.readStatusMessage
                }
                screenState.copy(
                    page = page,
                    readStatusMessage = nextMessage
                )
            }
        }
        syncRuntimeConfig()
        refreshNfcAvailability()
        updateReaderMode()
    }

    private fun updateForm(nextForm: TokenForm) {
        screenState = screenState.copy(form = nextForm)
        syncRuntimeConfig()
    }

    private fun updateSelectedDomainTokenId(tokenId: String) {
        val selectedDomain = screenState.form.domainType
        val nextTokenIds = screenState.tokenIdsByDomain.toMutableMap().apply {
            put(selectedDomain, tokenId)
        }
        screenState = screenState.copy(tokenIdsByDomain = nextTokenIds)
        persistTokenId(selectedDomain, tokenId)
        syncRuntimeConfig()
    }

    private fun syncRuntimeConfig() {
        val step = screenState.incrementStepInput.toIntOrNull()?.let { max(it, 1) } ?: 1
        runtimeConfigRef.set(
            RuntimeConfig(
                page = screenState.page,
                form = screenState.form,
                tokenIdsByDomain = HashMap(screenState.tokenIdsByDomain),
                autoIncrement = screenState.autoIncrement,
                incrementStep = step,
                writeCooldownUntilMs = screenState.writeCooldownUntilMs
            )
        )
    }

    private fun refreshNfcAvailability() {
        val adapter = nfcAdapter
        screenState = screenState.copy(
            isNfcSupported = adapter != null,
            isNfcEnabled = adapter?.isEnabled == true
        )
    }

    private fun updateReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            adapter.disableReaderMode(this)
            return
        }

        val shouldScan = screenState.page == AppPage.WRITE_SCAN || screenState.page == AppPage.READ_SCAN
        if (!shouldScan) {
            adapter.disableReaderMode(this)
            return
        }

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
        when (runtimeConfigRef.get().page) {
            AppPage.WRITE_SCAN -> handleWriteTag(tag, runtimeConfigRef.get())
            AppPage.READ_SCAN -> handleReadTag(tag)
            AppPage.WRITE_SETUP -> Unit
        }
    }

    private fun handleWriteTag(tag: Tag, config: RuntimeConfig) {
        val domainType = config.form.domainType
        val tokenId = config.tokenIdsByDomain[domainType].orEmpty().trim()

        if (tokenId.isEmpty()) {
            playWriteFailureTone()
            runOnUiThread {
                screenState = screenState.copy(
                    writeStatusMessage = "Token ID for ${friendlyDomainName(domainType)} cannot be empty.",
                    writeStatusType = StatusType.Error
                )
            }
            return
        }

        val tagIdHex = tag.id?.toHexString().orEmpty().ifBlank { "unknown" }
        if (shouldDebounceTag(tagIdHex)) return

        val now = System.currentTimeMillis()
        if (now < config.writeCooldownUntilMs) {
            playWriteLockoutTone()
            runOnUiThread {
                screenState = screenState.copy(
                    writeStatusMessage = "Write lockout active. Wait ${formatRemainingSeconds(config.writeCooldownUntilMs - now)} before scanning the next token.",
                    writeStatusType = StatusType.Warning
                )
            }
            return
        }

        val jsonPayload = config.form.toJsonString(tokenId = tokenId)
        runOnUiThread {
            screenState = screenState.copy(
                writeStatusMessage = "Tag detected ($tagIdHex). Writing...",
                writeStatusType = StatusType.Info
            )
        }

        try {
            writeJsonToTag(tag, jsonPayload)
            runOnUiThread {
                onWriteSuccess(
                    tagIdHex = tagIdHex,
                    domainType = domainType,
                    incrementStep = config.incrementStep,
                    autoIncrement = config.autoIncrement
                )
            }
        } catch (e: Exception) {
            playWriteFailureTone()
            runOnUiThread {
                screenState = screenState.copy(
                    writeStatusMessage = "Write failed: ${e.message ?: e.javaClass.simpleName}",
                    writeStatusType = StatusType.Error
                )
            }
        }
    }

    private fun handleReadTag(tag: Tag) {
        val tagIdHex = tag.id?.toHexString().orEmpty().ifBlank { "unknown" }
        if (shouldDebounceTag(tagIdHex)) return

        runOnUiThread {
            screenState = screenState.copy(
                readStatusMessage = "Tag detected ($tagIdHex). Reading...",
                readStatusType = StatusType.Info
            )
        }

        try {
            val rawPayload = readTextFromTag(tag)
            val parsedPayload = parseTokenPayload(rawPayload)
            runOnUiThread {
                screenState = screenState.copy(
                    readStatusMessage = "Read successful from tag $tagIdHex.",
                    readStatusType = StatusType.Success,
                    readLastTagIdHex = tagIdHex,
                    readRawPayload = rawPayload,
                    readParsedPayload = parsedPayload
                )
            }
        } catch (e: Exception) {
            runOnUiThread {
                screenState = screenState.copy(
                    readStatusMessage = "Read failed: ${e.message ?: e.javaClass.simpleName}",
                    readStatusType = StatusType.Error
                )
            }
        }
    }

    private fun onWriteSuccess(
        tagIdHex: String,
        domainType: String,
        incrementStep: Int,
        autoIncrement: Boolean
    ) {
        val cooldownUntilMs = System.currentTimeMillis() + WRITE_COOLDOWN_MS
        var nextState = screenState.copy(
            writeCount = screenState.writeCount + 1,
            lastWrittenTagIdHex = tagIdHex,
            writeStatusMessage = "Write successful (#${screenState.writeCount + 1}) on tag $tagIdHex. Remove the token and wait one second before scanning the next one.",
            writeStatusType = StatusType.Success,
            writeCooldownUntilMs = cooldownUntilMs
        )

        if (autoIncrement) {
            val currentId = nextState.tokenIdsByDomain[domainType].orEmpty().trim()
            val numericId = currentId.toLongOrNull()
            if (numericId != null) {
                val nextTokenId = (numericId + incrementStep).toString()
                nextState = nextState.copy(
                    tokenIdsByDomain = nextState.tokenIdsByDomain.toMutableMap().apply {
                        put(domainType, nextTokenId)
                    }
                )
                persistTokenId(domainType, nextTokenId)
            } else {
                nextState = nextState.copy(
                    writeStatusMessage = "${nextState.writeStatusMessage} Auto-increment skipped because the token ID is not numeric."
                )
            }
        }

        playWriteSuccessTone()
        screenState = nextState
        syncRuntimeConfig()
    }

    @Throws(IOException::class)
    private fun readTextFromTag(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: throw IOException("Tag does not contain NDEF data.")
        try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
                ?: throw IOException("Tag is empty.")
            val record = message.records.firstOrNull()
                ?: throw IOException("Tag has no records.")
            return decodeRecordPayload(record)
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
                // Ignore close errors after a read attempt.
            }
        }
    }

    private fun decodeRecordPayload(record: NdefRecord): String {
        val payload = record.payload
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            if (payload.isEmpty()) return ""
            val statusByte = payload[0].toInt()
            val languageCodeLength = statusByte and 0x3F
            val textStart = 1 + languageCodeLength
            if (textStart > payload.size) {
                throw IOException("Invalid text record.")
            }
            return String(payload, textStart, payload.size - textStart, StandardCharsets.UTF_8)
        }
        return String(payload, StandardCharsets.UTF_8)
    }

    private fun parseTokenPayload(rawPayload: String): ParsedTokenPayload? {
        return try {
            val json = JSONObject(rawPayload)
            ParsedTokenPayload(
                tokenId = json.optString("tokenId"),
                domainType = json.optString("domainType"),
                color = json.optString("color"),
                userNote = json.optString("userNote"),
                prettyJson = json.toString(2)
            )
        } catch (_: Exception) {
            null
        }
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

    private fun playWriteSuccessTone() {
        window.decorView.postDelayed(
            {
                if (!isFinishing && !isDestroyed) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                }
            },
            WRITE_SUCCESS_TONE_DELAY_MS
        )
    }

    private fun playWriteFailureTone() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 280)
    }

    private fun playWriteLockoutTone() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 140)
    }
}

private enum class AppPage {
    WRITE_SETUP,
    WRITE_SCAN,
    READ_SCAN
}

private data class ChoiceSpec(
    val value: String,
    val label: String,
    val supporting: String,
    val accentColor: Color? = null
)

private data class TokenForm(
    val domainType: String = DOMAIN_PHYSICAL_ACTIVITY,
    val color: String = COLOR_GREEN,
    val userNote: String = ""
) {
    fun toJsonString(tokenId: String): String {
        return JSONObject()
            .put("tokenId", tokenId)
            .put("domainType", domainType)
            .put("color", color)
            .put("userNote", userNote)
            .toString()
    }

    fun toPrettyJson(tokenId: String): String {
        return JSONObject()
            .put("tokenId", tokenId.trim())
            .put("domainType", domainType)
            .put("color", color)
            .put("userNote", userNote)
            .toString(2)
    }
}

private data class ParsedTokenPayload(
    val tokenId: String,
    val domainType: String,
    val color: String,
    val userNote: String,
    val prettyJson: String
)

private data class RuntimeConfig(
    val page: AppPage,
    val form: TokenForm,
    val tokenIdsByDomain: Map<String, String>,
    val autoIncrement: Boolean,
    val incrementStep: Int,
    val writeCooldownUntilMs: Long
)

private data class ScreenState(
    val page: AppPage = AppPage.WRITE_SETUP,
    val form: TokenForm = TokenForm(),
    val tokenIdsByDomain: Map<String, String> = defaultTokenIdsMap(),
    val autoIncrement: Boolean = true,
    val incrementStepInput: String = "1",
    val writeCount: Int = 0,
    val lastWrittenTagIdHex: String = "",
    val writeStatusMessage: String = "Ready to write. Hold a token over the center target.",
    val writeStatusType: StatusType = StatusType.Info,
    val writeCooldownUntilMs: Long = 0L,
    val readStatusMessage: String = "Ready to read. Hold a token over the center target.",
    val readStatusType: StatusType = StatusType.Info,
    val readLastTagIdHex: String = "",
    val readRawPayload: String = "",
    val readParsedPayload: ParsedTokenPayload? = null,
    val isNfcSupported: Boolean = true,
    val isNfcEnabled: Boolean = true
)

private enum class StatusType {
    Info,
    Warning,
    Success,
    Error
}

@Composable
private fun AppContent(
    state: ScreenState,
    onTokenIdChange: (String) -> Unit,
    onDomainTypeChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onUserNoteChange: (String) -> Unit,
    onAutoIncrementChange: (Boolean) -> Unit,
    onIncrementStepChange: (String) -> Unit,
    onOpenWriteScan: () -> Unit,
    onOpenReadScan: () -> Unit,
    onBackToSetup: () -> Unit
) {
    BackHandler(enabled = state.page != AppPage.WRITE_SETUP) {
        onBackToSetup()
    }

    when (state.page) {
        AppPage.WRITE_SETUP -> WriteSetupScreen(
            state = state,
            onTokenIdChange = onTokenIdChange,
            onDomainTypeChange = onDomainTypeChange,
            onColorChange = onColorChange,
            onUserNoteChange = onUserNoteChange,
            onAutoIncrementChange = onAutoIncrementChange,
            onIncrementStepChange = onIncrementStepChange,
            onOpenWriteScan = onOpenWriteScan,
            onOpenReadScan = onOpenReadScan
        )

        AppPage.WRITE_SCAN -> WriteScanScreen(
            state = state,
            onBackToSetup = onBackToSetup
        )

        AppPage.READ_SCAN -> ReadScanScreen(
            state = state,
            onBackToSetup = onBackToSetup
        )
    }
}

@Composable
private fun WriteSetupScreen(
    state: ScreenState,
    onTokenIdChange: (String) -> Unit,
    onDomainTypeChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onUserNoteChange: (String) -> Unit,
    onAutoIncrementChange: (Boolean) -> Unit,
    onIncrementStepChange: (String) -> Unit,
    onOpenWriteScan: () -> Unit,
    onOpenReadScan: () -> Unit
) {
    val selectedDomainTokenId = state.tokenIdsByDomain[state.form.domainType] ?: "0"
    val scrollState = rememberScrollState()

    ScreenScaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PageTitle(
                title = "Token Setup",
                subtitle = "Choose the token data here. Scan mode is separate so nothing changes by accident while you hold a token near the device."
            )

            NfcAvailabilityCard(
                isNfcSupported = state.isNfcSupported,
                isNfcEnabled = state.isNfcEnabled
            )

            ActionCard(
                onPrimaryAction = onOpenWriteScan,
                onSecondaryAction = onOpenReadScan
            )

            SectionHeader(
                step = "1",
                title = "Select the domain",
                subtitle = "Each domain keeps a separate saved token ID."
            )
            ChoiceGroup(
                options = DOMAIN_SPECS,
                selected = state.form.domainType,
                onSelect = onDomainTypeChange
            )

            SectionHeader(
                step = "2",
                title = "Set the token ID",
                subtitle = "This value is saved locally for ${friendlyDomainName(state.form.domainType)}."
            )
            SectionCard {
                OutlinedTextField(
                    value = selectedDomainTokenId,
                    onValueChange = onTokenIdChange,
                    label = { Text("Token ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            }

            DomainCounterCard(
                tokenIdsByDomain = state.tokenIdsByDomain,
                selectedDomain = state.form.domainType
            )

            SectionHeader(
                step = "3",
                title = "Pick the color",
                subtitle = "Use the token color that matches the physical piece."
            )
            ChoiceGroup(
                options = COLOR_SPECS,
                selected = state.form.color,
                onSelect = onColorChange
            )

            SectionHeader(
                step = "4",
                title = "Optional note and write behavior",
                subtitle = "Keep the note blank unless you want to store extra context on the tag."
            )
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.form.userNote,
                        onValueChange = onUserNoteChange,
                        label = { Text("User Note") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Auto-increment the token ID after each successful write",
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
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
                    }
                }
            }

            PreviewCard(
                title = "Data that will be written",
                body = state.form.toPrettyJson(selectedDomainTokenId)
            )
        }
    }
}

@Composable
private fun WriteScanScreen(
    state: ScreenState,
    onBackToSetup: () -> Unit
) {
    val selectedDomainTokenId = state.tokenIdsByDomain[state.form.domainType] ?: "0"

    ScreenScaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PageTitle(
                title = "Write Scan",
                subtitle = "Hold the token over the center target. This page is read-only so the write settings cannot be changed accidentally.",
                backAction = onBackToSetup
            )

            ScanStageLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                center = {
                    ScanTarget(
                        title = "Write token here",
                        subtitle = "Keep the tag centered on this target until the write confirmation sound plays.",
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                },
                leftPane = {
                    NfcAvailabilityCard(
                        isNfcSupported = state.isNfcSupported,
                        isNfcEnabled = state.isNfcEnabled
                    )
                    StatusCard(
                        title = "Writer Status",
                        message = state.writeStatusMessage,
                        statusType = state.writeStatusType,
                        extraLines = listOfNotNull(
                            "Writes completed: ${state.writeCount}",
                            state.lastWrittenTagIdHex.takeIf { it.isNotBlank() }?.let { "Last tag ID: $it" }
                        )
                    )
                    WriteCooldownCard(cooldownUntilMs = state.writeCooldownUntilMs)
                },
                rightPane = {
                    LockedWriteDataCard(
                        form = state.form,
                        tokenId = selectedDomainTokenId,
                        autoIncrement = state.autoIncrement,
                        incrementStepInput = state.incrementStepInput
                    )
                    PreviewCard(
                        title = "Queued JSON",
                        body = state.form.toPrettyJson(selectedDomainTokenId)
                    )
                }
            )
        }
    }
}

@Composable
private fun ReadScanScreen(
    state: ScreenState,
    onBackToSetup: () -> Unit
) {
    ScreenScaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PageTitle(
                title = "Read Scan",
                subtitle = "Hold a token over the center target to inspect the data already written to it.",
                backAction = onBackToSetup
            )

            ScanStageLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                center = {
                    ScanTarget(
                        title = "Read token here",
                        subtitle = "Keep the token centered until the tag is read and the decoded data updates.",
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                },
                leftPane = {
                    NfcAvailabilityCard(
                        isNfcSupported = state.isNfcSupported,
                        isNfcEnabled = state.isNfcEnabled
                    )
                    StatusCard(
                        title = "Reader Status",
                        message = state.readStatusMessage,
                        statusType = state.readStatusType,
                        extraLines = listOfNotNull(
                            state.readLastTagIdHex.takeIf { it.isNotBlank() }?.let { "Last tag ID: $it" }
                        )
                    )
                    ReadSummaryCard(state = state)
                },
                rightPane = {
                    ReadPayloadCard(state = state)
                }
            )
        }
    }
}

@Composable
private fun ScreenScaffold(
    content: @Composable (PaddingValues) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background,
                        colors.surfaceVariant.copy(alpha = 0.45f),
                        colors.background
                    )
                )
            )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            content = content
        )
    }
}

@Composable
private fun ScanStageLayout(
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
    leftPane: @Composable ColumnScope.() -> Unit,
    rightPane: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val isWideLayout = maxWidth >= WIDE_SCAN_LAYOUT_BREAKPOINT_DP.dp

        if (isWideLayout) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScanSideColumn(
                    modifier = Modifier.weight(1f),
                    content = leftPane
                )
                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    center()
                }
                ScanSideColumn(
                    modifier = Modifier.weight(1f),
                    content = rightPane
                )
            }
        } else {
            val bottomScrollState = rememberScrollState()

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.95f),
                    contentAlignment = Alignment.Center
                ) {
                    center()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.15f)
                        .verticalScroll(bottomScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    leftPane()
                    rightPane()
                }
            }
        }
    }
}

@Composable
private fun ScanSideColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun PageTitle(
    title: String,
    subtitle: String,
    backAction: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (backAction != null) {
            TextButton(
                onClick = backAction,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("< Back to setup")
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SectionHeader(
    step: String,
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Step $step",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ActionCard(
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Choose the next mode",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Start with writing if you are preparing tokens, or switch to read mode to inspect a token that already exists.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Write Scan Page")
            }
            OutlinedButton(
                onClick = onSecondaryAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Read Scan Page")
            }
        }
    }
}

@Composable
private fun NfcAvailabilityCard(
    isNfcSupported: Boolean,
    isNfcEnabled: Boolean
) {
    val message = when {
        !isNfcSupported -> "This device does not support NFC."
        !isNfcEnabled -> "NFC is turned off. Enable it in system settings before scanning."
        else -> "NFC is ready."
    }
    val type = when {
        !isNfcSupported || !isNfcEnabled -> StatusType.Error
        else -> StatusType.Info
    }

    StatusCard(
        title = "NFC",
        message = message,
        statusType = type
    )
}

@Composable
private fun ChoiceGroup(
    options: List<ChoiceSpec>,
    selected: String,
    onSelect: (String) -> Unit
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val borderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                }
                val containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(containerColor)
                        .border(1.dp, borderColor, MaterialTheme.shapes.large)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(option.value) },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (option.accentColor != null) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(option.accentColor)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = option.supporting,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    androidx.compose.material3.RadioButton(
                        selected = isSelected,
                        onClick = null
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainCounterCard(
    tokenIdsByDomain: Map<String, String>,
    selectedDomain: String
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Saved Next Token IDs by Domain",
                style = MaterialTheme.typography.titleMedium
            )
            DOMAIN_OPTIONS.forEach { domainType ->
                val isSelected = domainType == selectedDomain
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = friendlyDomainName(domainType),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = tokenIdsByDomain[domainType] ?: "0",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

@Composable
private fun WriteCooldownCard(cooldownUntilMs: Long) {
    var now by remember(cooldownUntilMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(cooldownUntilMs) {
        while (System.currentTimeMillis() < cooldownUntilMs) {
            now = System.currentTimeMillis()
            delay(120L)
        }
        now = System.currentTimeMillis()
    }

    val remainingMs = (cooldownUntilMs - now).coerceAtLeast(0L)
    val ready = remainingMs == 0L
    val message = if (ready) {
        "Ready for the next token."
    } else {
        "Next write enabled in ${formatRemainingSeconds(remainingMs)}."
    }

    StatusCard(
        title = "Write Lockout",
        message = message,
        statusType = if (ready) StatusType.Success else StatusType.Warning
    )
}

@Composable
private fun LockedWriteDataCard(
    form: TokenForm,
    tokenId: String,
    autoIncrement: Boolean,
    incrementStepInput: String
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Current Write Data",
                style = MaterialTheme.typography.titleMedium
            )
            DetailLine("Domain", friendlyDomainName(form.domainType))
            DetailLine("Token ID", tokenId)
            DetailLine("Color", friendlyColorName(form.color))
            DetailLine("User Note", form.userNote.ifBlank { "(empty)" })
            DetailLine("Auto Increment", if (autoIncrement) "On" else "Off")
            if (autoIncrement) {
                DetailLine("Increment Step", incrementStepInput.ifBlank { "1" })
            }
        }
    }
}

@Composable
private fun ReadSummaryCard(state: ScreenState) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Decoded Fields",
                style = MaterialTheme.typography.titleMedium
            )

            val parsedPayload = state.readParsedPayload
            when {
                parsedPayload != null -> {
                    DetailLine("Token ID", parsedPayload.tokenId)
                    DetailLine("Domain", friendlyDomainName(parsedPayload.domainType))
                    DetailLine("Color", friendlyColorName(parsedPayload.color))
                    DetailLine("User Note", parsedPayload.userNote.ifBlank { "(empty)" })
                }

                state.readRawPayload.isNotBlank() -> {
                    Text(
                        text = "The tag was read, but the payload is not in the expected token JSON format.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    Text(
                        text = "No token data scanned yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadPayloadCard(state: ScreenState) {
    val parsedPayload = state.readParsedPayload
    val title = when {
        parsedPayload != null -> "Decoded JSON"
        state.readRawPayload.isNotBlank() -> "Raw Payload"
        else -> "Token Payload"
    }
    val body = when {
        parsedPayload != null -> parsedPayload.prettyJson
        state.readRawPayload.isNotBlank() -> state.readRawPayload
        else -> "No payload scanned yet."
    }

    PreviewCard(
        title = title,
        body = body
    )
}

@Composable
private fun PreviewCard(
    title: String,
    body: String
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = body,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    message: String,
    statusType: StatusType,
    extraLines: List<String> = emptyList()
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when (statusType) {
        StatusType.Info -> colors.primaryContainer
        StatusType.Warning -> colors.secondaryContainer
        StatusType.Success -> colors.tertiaryContainer
        StatusType.Error -> colors.errorContainer
    }
    val contentColor = when (statusType) {
        StatusType.Info -> colors.onPrimaryContainer
        StatusType.Warning -> colors.onSecondaryContainer
        StatusType.Success -> colors.onTertiaryContainer
        StatusType.Error -> colors.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
            extraLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun ScanTarget(
    title: String,
    subtitle: String,
    accentColor: Color
) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val minSide = size.minDimension
                val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
                val outerRadius = minSide * 0.45f
                val middleRadius = minSide * 0.33f
                val guideRadius = minSide * 0.22f

                drawCircle(
                    color = accentColor.copy(alpha = 0.08f),
                    radius = outerRadius,
                    center = center
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.22f),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = minSide * 0.010f)
                )
                drawCircle(
                    color = colors.surface,
                    radius = middleRadius,
                    center = center
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.48f),
                    radius = middleRadius,
                    center = center,
                    style = Stroke(width = minSide * 0.012f)
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.16f),
                    radius = guideRadius,
                    center = center,
                    style = Stroke(
                        width = minSide * 0.018f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(minSide * 0.06f, minSide * 0.05f))
                    )
                )

                val plateWidth = minSide * 0.30f
                val plateHeight = minSide * 0.44f
                val plateTopLeft = Offset(
                    x = center.x - plateWidth / 2f,
                    y = center.y - plateHeight / 2f
                )
                drawRoundRect(
                    color = colors.surfaceVariant.copy(alpha = 0.88f),
                    topLeft = plateTopLeft,
                    size = Size(plateWidth, plateHeight),
                    cornerRadius = CornerRadius(minSide * 0.05f)
                )
                drawRoundRect(
                    color = accentColor,
                    topLeft = plateTopLeft,
                    size = Size(plateWidth, plateHeight),
                    cornerRadius = CornerRadius(minSide * 0.05f),
                    style = Stroke(width = minSide * 0.012f)
                )

            }

            Text(
                text = "NFC",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
        )
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun defaultTokenIdsMap(): Map<String, String> =
    DOMAIN_OPTIONS.associateWith { "0" }

private fun friendlyDomainName(domainType: String): String =
    DOMAIN_SPECS.firstOrNull { it.value == domainType }?.label ?: domainType

private fun friendlyColorName(color: String): String =
    COLOR_SPECS.firstOrNull { it.value == color }?.label ?: color

private fun formatRemainingSeconds(remainingMs: Long): String =
    String.format(Locale.US, "%.1fs", remainingMs / 1000f)

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

@Preview(showBackground = true)
@Composable
private fun WriteSetupScreenPreview() {
    TokenToolTheme {
        AppContent(
            state = ScreenState(
                tokenIdsByDomain = mapOf(
                    DOMAIN_PHYSICAL_ACTIVITY to "12",
                    DOMAIN_SLEEP to "8",
                    DOMAIN_MOOD to "3",
                    DOMAIN_SPECIAL to "1"
                )
            ),
            onTokenIdChange = {},
            onDomainTypeChange = {},
            onColorChange = {},
            onUserNoteChange = {},
            onAutoIncrementChange = {},
            onIncrementStepChange = {},
            onOpenWriteScan = {},
            onOpenReadScan = {},
            onBackToSetup = {}
        )
    }
}
