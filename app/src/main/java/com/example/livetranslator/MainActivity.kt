package com.example.livetranslator

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity(), SentenceAdapter.Listener, RecordingsAdapter.Listener {

    // ── Prefs ──────────────────────────────────────────────────────────────
    private companion object {
        const val PREFS_NAME         = "prefs"
        const val PREF_AI_ENABLED    = "ai_replies_enabled"
        const val PREF_SOURCE_LANG   = "source_lang"
        const val PREF_TARGET_LANG   = "target_lang"
        const val PREF_API_KEY       = "api_key"
        const val PREF_API_KEY_URI   = "api_key_uri"
        const val PREF_AUTO_SPEAK    = "auto_speak"
        const val PREF_CONTINUOUS    = "continuous_mode"
        const val PREF_AI_REPLY_LANG = "ai_reply_lang"
        const val PREF_TTS_SPEED     = "tts_speed"   // 0–100 → 0.5x–2.0x
        const val PERM_AUDIO         = 10
        const val PERM_NOTIFY        = 11

        // TTS speed: seekbar 0-100 maps to 0.5x - 2.0x
        fun seekToSpeed(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f
        fun speedToSeek(speed: Float): Int    = ((speed - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
    }

    private lateinit var prefs: SharedPreferences

    // ── Tabs ───────────────────────────────────────────────────────────────
    private lateinit var btnTabLive       : Button
    private lateinit var btnTabRecordings : Button
    private lateinit var layoutLive       : View
    private lateinit var layoutRecordings : View

    // ── Live controls ──────────────────────────────────────────────────────
    private lateinit var switchListen      : Switch
    private lateinit var tvListeningBadge  : TextView
    private lateinit var switchContinuous  : Switch
    private lateinit var switchAiReplies   : Switch
    private lateinit var switchAutoSpeak   : Switch
    private lateinit var tvKeyStatus       : TextView
    private lateinit var btnPickApiKeyFile : Button
    private lateinit var btnClearApiKey    : Button
    private lateinit var rgAiLang          : RadioGroup
    private lateinit var rbAiSource        : RadioButton
    private lateinit var rbAiTarget        : RadioButton
    private lateinit var spinnerSource     : Spinner
    private lateinit var spinnerTarget     : Spinner
    private lateinit var btnSwapLangs      : Button

    // TTS speed
    private lateinit var seekTtsSpeed      : SeekBar
    private lateinit var tvTtsSpeedLabel   : TextView

    private lateinit var etManualText      : EditText
    private lateinit var btnTranslate      : Button
    private lateinit var btnExport         : Button
    private lateinit var btnClearHistory   : Button
    private lateinit var recyclerSentences : RecyclerView

    // Continuous card
    private lateinit var cardContinuous      : View
    private lateinit var tvContinuousLive    : TextView
    private lateinit var tvContinuousFinal   : TextView
    private lateinit var btnFinalizeTranslate: Button
    private lateinit var btnClearContinuous  : Button

    // Recordings
    private lateinit var btnRecordToggle    : Button
    private lateinit var tvRecordStatus     : TextView
    private lateinit var recyclerRecordings : RecyclerView

    // ── Adapters ───────────────────────────────────────────────────────────
    private lateinit var sentenceAdapter  : SentenceAdapter
    private lateinit var recordingsAdapter: RecordingsAdapter

    // ── Speech ─────────────────────────────────────────────────────────────
    // Standard single recognizer (used in non-continuous mode)
    private var speechRecognizer : SpeechRecognizer? = null
    private var isListening       = false

    // Dual ping-pong recognizer (continuous mode)
    private var dualRecognizer    : DualSpeechRecognizer? = null
    private val continuousBuffer  = StringBuilder()

    // ── Foreground service ────────────────────────────────────────────────
    private var serviceBound = false
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { serviceBound = true }
        override fun onServiceDisconnected(name: ComponentName?) { serviceBound = false }
    }

    // ── Audio recording / playback ─────────────────────────────────────────
    private lateinit var audioRecorder     : AudioRecorder
    private var mediaPlayer                : MediaPlayer? = null
    private var currentlyPlayingPath       : String? = null

    // ── TTS / translation ──────────────────────────────────────────────────
    private var tts           : TextToSpeech? = null
    private var ttsSpeechRate : Float = 1.0f
    private val aiClient       = AiClient()
    private val idGen          = AtomicLong(1)
    private var translator     : Translator? = null
    private var translatorSrc  : String?     = null
    private var translatorTgt  : String?     = null

    // ── Launchers ──────────────────────────────────────────────────────────
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> r.data?.data?.let { writeExportToUri(it) } }

    private val apiKeyFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val uri = r.data?.data
        if (r.resultCode == RESULT_OK && uri != null) {
            persistReadPermission(uri)
            val key = readTextFromUri(uri).trim()
            if (key.isNotBlank()) {
                prefs.edit().putString(PREF_API_KEY, key).putString(PREF_API_KEY_URI, uri.toString()).apply()
                updateApiKeyStatus(); toast("API key loaded")
            } else toast("Selected file is empty")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioRecorder = AudioRecorder(this)
        bindViews()
        setupSpinners()
        setupRecyclers()
        setupTts()
        restoreUiState()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        showTab(isLiveTab = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Stop listening" tapped in the notification shade
        if (intent.action == TranslatorService.ACTION_STOP) {
            switchListen.isChecked = false
        }
    }

    // ── Binding ────────────────────────────────────────────────────────────
    private fun bindViews() {
        btnTabLive          = findViewById(R.id.btnTabLive)
        btnTabRecordings    = findViewById(R.id.btnTabRecordings)
        layoutLive          = findViewById(R.id.layoutLive)
        layoutRecordings    = findViewById(R.id.layoutRecordings)

        switchListen        = findViewById(R.id.switchListen)
        tvListeningBadge    = findViewById(R.id.tvListeningBadge)
        switchContinuous    = findViewById(R.id.switchContinuous)
        switchAiReplies     = findViewById(R.id.switchAiReplies)
        switchAutoSpeak     = findViewById(R.id.switchAutoSpeak)
        tvKeyStatus         = findViewById(R.id.tvKeyStatus)
        btnPickApiKeyFile   = findViewById(R.id.btnPickApiKeyFile)
        btnClearApiKey      = findViewById(R.id.btnClearApiKey)
        rgAiLang            = findViewById(R.id.rgAiLang)
        rbAiSource          = findViewById(R.id.rbAiSource)
        rbAiTarget          = findViewById(R.id.rbAiTarget)
        spinnerSource       = findViewById(R.id.spinnerSource)
        spinnerTarget       = findViewById(R.id.spinnerTarget)
        btnSwapLangs        = findViewById(R.id.btnSwapLanguages)

        seekTtsSpeed        = findViewById(R.id.seekTtsSpeed)
        tvTtsSpeedLabel     = findViewById(R.id.tvTtsSpeedLabel)

        etManualText        = findViewById(R.id.etManualText)
        btnTranslate        = findViewById(R.id.btnTranslate)
        btnExport           = findViewById(R.id.btnExport)
        btnClearHistory     = findViewById(R.id.btnClearHistory)
        recyclerSentences   = findViewById(R.id.recyclerView)

        cardContinuous        = findViewById(R.id.cardContinuous)
        tvContinuousLive      = findViewById(R.id.tvContinuousLive)
        tvContinuousFinal     = findViewById(R.id.tvContinuousFinal)
        btnFinalizeTranslate  = findViewById(R.id.btnFinalizeTranslate)
        btnClearContinuous    = findViewById(R.id.btnClearContinuous)

        btnRecordToggle     = findViewById(R.id.btnRecordToggle)
        tvRecordStatus      = findViewById(R.id.tvRecordStatus)
        recyclerRecordings  = findViewById(R.id.recyclerRecordings)
    }

    private fun setupSpinners() {
        val labels = LanguageOptions.list.map { it.label }
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSource.adapter = a; spinnerTarget.adapter = a
        spinnerSource.setSelection(LanguageOptions.indexOfTag(prefs.getString(PREF_SOURCE_LANG,"en") ?: "en"))
        spinnerTarget.setSelection(LanguageOptions.indexOfTag(prefs.getString(PREF_TARGET_LANG,"cs") ?: "cs"))
    }

    private fun sourceTag()   = LanguageOptions.list.getOrNull(spinnerSource.selectedItemPosition)?.bcp47  ?: "en"
    private fun targetTag()   = LanguageOptions.list.getOrNull(spinnerTarget.selectedItemPosition)?.bcp47  ?: "cs"
    private fun sourceLabel() = LanguageOptions.list.getOrNull(spinnerSource.selectedItemPosition)?.label  ?: "English"
    private fun targetLabel() = LanguageOptions.list.getOrNull(spinnerTarget.selectedItemPosition)?.label  ?: "Czech"
    private fun aiReplyInSourceLang() = prefs.getString(PREF_AI_REPLY_LANG,"target") == "source"

    private fun setupRecyclers() {
        sentenceAdapter = SentenceAdapter(mutableListOf(), this)
        recyclerSentences.layoutManager = LinearLayoutManager(this)
        recyclerSentences.adapter = sentenceAdapter
        recyclerSentences.isNestedScrollingEnabled = false

        recordingsAdapter = RecordingsAdapter(AudioRecorder.listAll(this).toMutableList(), this)
        recyclerRecordings.layoutManager = LinearLayoutManager(this)
        recyclerRecordings.adapter = recordingsAdapter
        recyclerRecordings.isNestedScrollingEnabled = false
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setSpeechRate(ttsSpeechRate)
            } else {
                toast("TTS init failed")
            }
        }
    }

    private fun restoreUiState() {
        updateApiKeyStatus()
        switchAiReplies.isChecked  = prefs.getBoolean(PREF_AI_ENABLED,  true)
        switchAutoSpeak.isChecked  = prefs.getBoolean(PREF_AUTO_SPEAK,  false)
        switchContinuous.isChecked = prefs.getBoolean(PREF_CONTINUOUS,  false)
        tvListeningBadge.visibility = View.GONE

        if (prefs.getString(PREF_AI_REPLY_LANG,"target") == "source")
            rbAiSource.isChecked = true else rbAiTarget.isChecked = true

        // Restore TTS speed
        val savedSpeed = prefs.getFloat(PREF_TTS_SPEED, 1.0f)
        ttsSpeechRate  = savedSpeed
        seekTtsSpeed.progress  = speedToSeek(savedSpeed)
        updateSpeedLabel(savedSpeed)

        updateContinuousCard()
    }

    // ── Listeners ──────────────────────────────────────────────────────────
    private fun setupListeners() {
        btnTabLive.setOnClickListener       { showTab(true)  }
        btnTabRecordings.setOnClickListener { showTab(false) }

        switchListen.setOnCheckedChangeListener    { _, c -> if (c) startListeningFlow() else stopListeningFlow() }
        switchContinuous.setOnCheckedChangeListener{ _, c ->
            prefs.edit().putBoolean(PREF_CONTINUOUS,c).apply()
            // Restart listening with the correct recognizer
            if (isListening) {
                stopListeningFlow()
                startListeningFlow()
            }
            updateContinuousCard()
        }
        switchAiReplies.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(PREF_AI_ENABLED,c).apply() }
        switchAutoSpeak.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(PREF_AUTO_SPEAK,c).apply() }
        rgAiLang.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(PREF_AI_REPLY_LANG, if (id == R.id.rbAiSource) "source" else "target").apply()
        }

        // TTS speed seekbar
        seekTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putFloat(PREF_TTS_SPEED, ttsSpeechRate).apply()
            }
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                ttsSpeechRate = seekToSpeed(progress)
                tts?.setSpeechRate(ttsSpeechRate)
                updateSpeedLabel(ttsSpeechRate)
            }
        })

        spinnerSource.setOnItemSelectedListener(SimpleItemSelectedListener {
            prefs.edit().putString(PREF_SOURCE_LANG,sourceTag()).apply()
            resetTranslator()
            dualRecognizer?.updateLanguage(sourceTag())
        })
        spinnerTarget.setOnItemSelectedListener(SimpleItemSelectedListener {
            prefs.edit().putString(PREF_TARGET_LANG,targetTag()).apply()
            resetTranslator()
        })
        btnSwapLangs.setOnClickListener {
            val s = spinnerSource.selectedItemPosition; val t = spinnerTarget.selectedItemPosition
            spinnerSource.setSelection(t); spinnerTarget.setSelection(s)
        }

        btnPickApiKeyFile.setOnClickListener { openApiKeyFilePicker() }
        btnClearApiKey.setOnClickListener    {
            prefs.edit().remove(PREF_API_KEY).remove(PREF_API_KEY_URI).apply()
            updateApiKeyStatus(); toast("API key cleared")
        }

        btnTranslate.setOnClickListener {
            val txt = etManualText.text.toString().trim()
            if (txt.isNotEmpty()) { etManualText.setText(""); handleNewSourceText(txt) }
        }
        btnExport.setOnClickListener {
            if (sentenceAdapter.all().isEmpty()) { toast("Nothing to export"); return@setOnClickListener }
            val name = "LiveTranslator_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())}.txt"
            createDocumentLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, name)
            })
        }
        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear history")
                .setMessage("Delete all ${sentenceAdapter.all().size} items?")
                .setPositiveButton("Clear") { _,_ -> sentenceAdapter.clearAll(); toast("Cleared") }
                .setNegativeButton("Cancel",null).show()
        }

        btnFinalizeTranslate.setOnClickListener {
            val text = continuousBuffer.toString().trim()
            if (text.isNotBlank()) {
                handleNewSourceText(text)
                continuousBuffer.clear(); tvContinuousLive.text = ""; tvContinuousFinal.text = ""
            }
        }
        btnClearContinuous.setOnClickListener {
            continuousBuffer.clear(); tvContinuousLive.text = ""; tvContinuousFinal.text = ""
        }

        btnRecordToggle.setOnClickListener {
            if (audioRecorder.isRecording) stopRecording() else startRecording()
        }
    }

    private fun updateSpeedLabel(speed: Float) {
        tvTtsSpeedLabel.text = "%.1fx".format(speed)
    }

    // ── Tab ────────────────────────────────────────────────────────────────
    private fun showTab(isLiveTab: Boolean) {
        layoutLive.visibility       = if (isLiveTab) View.VISIBLE else View.GONE
        layoutRecordings.visibility = if (isLiveTab) View.GONE    else View.VISIBLE
        btnTabLive.alpha       = if (isLiveTab) 1f else 0.5f
        btnTabRecordings.alpha = if (isLiveTab) 0.5f else 1f
        if (!isLiveTab) recordingsAdapter.setAll(AudioRecorder.listAll(this))
    }

    private fun updateContinuousCard() {
        cardContinuous.visibility = if (switchContinuous.isChecked) View.VISIBLE else View.GONE
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SPEECH RECOGNITION
    // ══════════════════════════════════════════════════════════════════════

    private fun startListeningFlow() {
        if (!hasAudioPermission()) { requestAudioPermission(); switchListen.isChecked = false; return }
        isListening = true
        tvListeningBadge.visibility = View.VISIBLE
        // Start foreground service to keep process alive
        startForegroundService(TranslatorService.startIntent(this))
        bindService(TranslatorService.startIntent(this), serviceConn, Context.BIND_AUTO_CREATE)

        if (switchContinuous.isChecked) {
            startDualRecognizer()
        } else {
            ensureSingleRecognizer()
            startSingleRecognizerOnce()
        }
    }

    private fun stopListeningFlow() {
        isListening = false
        tvListeningBadge.visibility = View.GONE

        // Stop dual
        dualRecognizer?.stop()
        dualRecognizer = null

        // Stop single
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        // Stop foreground service
        if (serviceBound) {
            try { unbindService(serviceConn) } catch (_: Exception) {}
            serviceBound = false
        }
        startService(TranslatorService.stopIntent(this))
    }

    // ── Continuous mode: DualSpeechRecognizer ──────────────────────────────
    private fun startDualRecognizer() {
        dualRecognizer?.stop()
        dualRecognizer = DualSpeechRecognizer(
            context     = this,
            onPartial   = { partial ->
                // Show current partial appended to confirmed buffer
                tvContinuousLive.text = buildPreview(partial)
            },
            onFinal     = { text ->
                if (continuousBuffer.isNotEmpty()) continuousBuffer.append(" ")
                continuousBuffer.append(text)
                tvContinuousLive.text = continuousBuffer.toString()
                autoTranslateContinuous()
            },
            onErrorCb   = { /* DualSpeechRecognizer handles restart internally */ }
        )
        dualRecognizer?.start(sourceTag())
    }

    private fun buildPreview(partial: String): String {
        val base = continuousBuffer.toString()
        return if (base.isBlank()) partial else "$base $partial"
    }

    // ── Chunked mode: single SpeechRecognizer ─────────────────────────────
    private fun ensureSingleRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onError(error: Int) {
                if (isListening) recyclerSentences.postDelayed({ startSingleRecognizerOnce() }, 400)
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) { etManualText.setText(text); etManualText.setSelection(text.length) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) { etManualText.setText(""); handleNewSourceText(text) }
                if (isListening) recyclerSentences.postDelayed({ startSingleRecognizerOnce() }, 250)
            }
        })
    }

    private fun startSingleRecognizerOnce() {
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,           1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,  1200L)
        })
    }

    // ── Continuous auto-translate ──────────────────────────────────────────
    private var lastContinuousJobTs = 0L
    private fun autoTranslateContinuous() {
        val ts = System.currentTimeMillis(); lastContinuousJobTs = ts
        val text = continuousBuffer.toString().trim(); if (text.isBlank()) return
        ensureTranslator(sourceTag(), targetTag()) { trResult ->
            if (lastContinuousJobTs != ts) return@ensureTranslator
            trResult.onSuccess { tr ->
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
                    tr.translate(text).addOnSuccessListener { translated ->
                        runOnUiThread {
                            if (lastContinuousJobTs == ts) tvContinuousFinal.text = translated
                        }
                    }
                }
            }
        }
    }

    // ── Chunked pipeline ───────────────────────────────────────────────────
    private fun handleNewSourceText(sourceText: String) {
        val sentence = Sentence(id=idGen.getAndIncrement(), sourceText=sourceText,
            translatedText="", replyText="", status="Translating…", timestamp=System.currentTimeMillis())
        sentenceAdapter.prepend(sentence)
        recyclerSentences.scrollToPosition(0)
        translateThenMaybeReply(sentence)
    }

    private fun translateThenMaybeReply(sentence: Sentence) {
        ensureTranslator(sourceTag(), targetTag()) { trResult ->
            trResult.onFailure { e ->
                runOnUiThread { sentence.status = "Translate failed: ${e.message}"; sentenceAdapter.update(sentence) }
            }
            trResult.onSuccess { tr ->
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.translate(sentence.sourceText)
                            .addOnSuccessListener { translated ->
                                runOnUiThread {
                                    sentence.translatedText = translated; sentence.status = "Ready"
                                    sentenceAdapter.update(sentence)
                                    if (switchAutoSpeak.isChecked && translated.isNotBlank())
                                        speak(translated, LanguageOptions.list
                                            .getOrNull(spinnerTarget.selectedItemPosition)?.locale() ?: Locale.getDefault())
                                }
                                if (switchAiReplies.isChecked) requestAiReply(sentence)
                            }
                            .addOnFailureListener { e ->
                                runOnUiThread { sentence.status = "Translate failed: ${e.message}"; sentenceAdapter.update(sentence) }
                            }
                    }
                    .addOnFailureListener { e ->
                        runOnUiThread { sentence.status = "Model download: ${e.message}"; sentenceAdapter.update(sentence) }
                    }
            }
        }
    }

    // ── AI reply ───────────────────────────────────────────────────────────
    private fun requestAiReply(sentence: Sentence) {
        val apiKey = (prefs.getString(PREF_API_KEY,"") ?: "").trim()
        if (apiKey.isBlank()) { runOnUiThread { sentence.status = "AI key missing"; sentenceAdapter.update(sentence) }; return }
        val promptText = sentence.translatedText.ifBlank { sentence.sourceText }
        if (!isLikelyQuestion(promptText, targetTag())) return
        val replyLang = if (aiReplyInSourceLang()) sourceLabel() else targetLabel()
        runOnUiThread { sentence.status = "AI replying…"; sentenceAdapter.update(sentence) }
        aiClient.generateReply(apiKey, promptText, replyLang) { result ->
            runOnUiThread {
                result.onSuccess { sentence.replyText = it; sentence.status = "Ready" }
                result.onFailure { sentence.replyText = ""; sentence.status = "AI failed: ${it.message}" }
                sentenceAdapter.update(sentence)
            }
        }
    }

    // ── Recording ──────────────────────────────────────────────────────────
    private fun startRecording() {
        if (!hasAudioPermission()) { requestAudioPermission(); return }
        val file = audioRecorder.start()
        if (file != null) {
            btnRecordToggle.text = "⏹ Stop recording"
            tvRecordStatus.visibility = View.VISIBLE; tvRecordStatus.text = "● REC  ${file.name}"
            toast("Recording started")
        } else toast("Failed to start recording")
    }

    private fun stopRecording() {
        val file = audioRecorder.stop()
        btnRecordToggle.text = "⏺ Start recording"; tvRecordStatus.visibility = View.GONE
        if (file != null) { toast("Saved: ${file.name}"); recordingsAdapter.setAll(AudioRecorder.listAll(this)) }
        else toast("Recording too short or failed")
    }

    // ── RecordingsAdapter.Listener ─────────────────────────────────────────
    override fun onPlay(item: RecordingItem) {
        if (mediaPlayer != null) {
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
            currentlyPlayingPath?.let { p ->
                recordingsAdapter.all().find { it.file.absolutePath == p }?.let { prev ->
                    prev.isPlaying = false; recordingsAdapter.update(prev)
                }
            }
            currentlyPlayingPath = null
        }
        val player = MediaPlayer()
        try {
            player.setDataSource(item.file.absolutePath); player.prepare(); player.start()
            mediaPlayer = player; currentlyPlayingPath = item.file.absolutePath
            item.isPlaying = true; recordingsAdapter.update(item)
            player.setOnCompletionListener {
                item.isPlaying = false; recordingsAdapter.update(item); mediaPlayer = null; currentlyPlayingPath = null
            }
        } catch (e: Exception) { player.release(); toast("Playback failed: ${e.message}") }
    }

    override fun onStop(item: RecordingItem) {
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null; currentlyPlayingPath = null
        item.isPlaying = false; recordingsAdapter.update(item)
    }

    override fun onDelete(item: RecordingItem) {
        AlertDialog.Builder(this).setTitle("Delete recording").setMessage("Delete \"${item.name}\"?")
            .setPositiveButton("Delete") { _,_ -> if (item.isPlaying) onStop(item); item.file.delete(); recordingsAdapter.remove(item); toast("Deleted") }
            .setNegativeButton("Cancel",null).show()
    }

    override fun onTranscribe(item: RecordingItem) {
        if (!hasAudioPermission()) { requestAudioPermission(); return }
        item.transcription = "Transcribing…"; item.translation = ""; recordingsAdapter.update(item)
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {};       override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {};              override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onError(error: Int) {
                runOnUiThread { item.transcription = "Transcription failed (error $error)."; recordingsAdapter.update(item) }
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                recognizer.destroy()
                if (text.isEmpty()) { runOnUiThread { item.transcription = "No speech recognised."; recordingsAdapter.update(item) }; return }
                runOnUiThread { item.transcription = text; item.translation = "Translating…"; recordingsAdapter.update(item) }
                ensureTranslator(sourceTag(), targetTag()) { trResult ->
                    trResult.onSuccess { tr ->
                        tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
                            tr.translate(text).addOnSuccessListener { translated ->
                                runOnUiThread { item.translation = translated; recordingsAdapter.update(item) }
                                handleNewSourceText(text)
                            }
                        }
                    }
                    trResult.onFailure { e -> runOnUiThread { item.translation = "Translate failed: ${e.message}"; recordingsAdapter.update(item) } }
                }
            }
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceTag()); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra("android.speech.extra.AUDIO_SOURCE", Uri.fromFile(item.file))
            putExtra("android.speech.extra.AUDIO_SOURCE_LENGTH_MILLIS", 0L)
        })
    }

    // ── SentenceAdapter.Listener ───────────────────────────────────────────
    override fun onCopySource(sentence: Sentence)      { copyToClipboard(sentence.sourceText) }
    override fun onCopyTranslation(sentence: Sentence) { copyToClipboard(sentence.translatedText) }
    override fun onCopyReply(sentence: Sentence)       { copyToClipboard(sentence.replyText) }
    override fun onSpeakSource(sentence: Sentence) {
        speak(sentence.sourceText, LanguageOptions.list.getOrNull(spinnerSource.selectedItemPosition)?.locale() ?: Locale.getDefault())
    }
    override fun onSpeakTranslation(sentence: Sentence) {
        speak(sentence.translatedText.ifBlank { sentence.sourceText }, LanguageOptions.list.getOrNull(spinnerTarget.selectedItemPosition)?.locale() ?: Locale.getDefault())
    }
    override fun onEditSource(sentence: Sentence) {
        val input = EditText(this).apply { setText(sentence.sourceText); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2 }
        AlertDialog.Builder(this).setTitle("Edit text").setView(input)
            .setPositiveButton("Save") { _,_ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { sentence.sourceText=t; sentence.translatedText=""; sentence.replyText=""; sentence.status="Translating…"; sentenceAdapter.update(sentence); translateThenMaybeReply(sentence) }
            }.setNegativeButton("Cancel",null).show()
    }
    override fun onReply(sentence: Sentence)  { requestAiReply(sentence) }
    override fun onDelete(sentence: Sentence) { sentenceAdapter.remove(sentence) }

    // ── Translator ─────────────────────────────────────────────────────────
    private fun ensureTranslator(src: String, tgt: String, cb: (Result<Translator>) -> Unit) {
        if (translator != null && translatorSrc == src && translatorTgt == tgt) { cb(Result.success(translator!!)); return }
        resetTranslator()
        val srcMl = TranslateLanguage.fromLanguageTag(src); val tgtMl = TranslateLanguage.fromLanguageTag(tgt)
        if (srcMl == null || tgtMl == null) { cb(Result.failure(IllegalArgumentException("Unsupported: $src → $tgt"))); return }
        translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(srcMl).setTargetLanguage(tgtMl).build())
        translatorSrc = src; translatorTgt = tgt; cb(Result.success(translator!!))
    }
    private fun resetTranslator() { try { translator?.close() } catch (_: Exception) {}; translator=null; translatorSrc=null; translatorTgt=null }

    // ── Permissions ────────────────────────────────────────────────────────
    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAudioPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_AUDIO)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERM_NOTIFY
                )
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERM_AUDIO  -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) toast("Mic permission granted") else toast("Audio permission denied")
            PERM_NOTIFY -> { /* notification permission – silently handle, service still works without it */ }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        if (audioRecorder.isRecording) audioRecorder.stop()
        mediaPlayer?.release(); mediaPlayer = null
        stopListeningFlow()
        resetTranslator()
        tts?.stop(); tts?.shutdown(); tts = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
        toast("Copied")
    }
    private fun speak(text: String, locale: Locale) {
        if (text.isBlank()) return
        tts?.let { it.language = locale; it.setSpeechRate(ttsSpeechRate); it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}") }
    }
    private fun openApiKeyFilePicker() {
        apiKeyFilePickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain","*/*"))
        })
    }
    private fun persistReadPermission(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
    private fun readTextFromUri(uri: Uri): String = try { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "" } catch (_: Exception) { "" }
    private fun updateApiKeyStatus() {
        val k = (prefs.getString(PREF_API_KEY,"") ?: "").trim()
        tvKeyStatus.text = if (k.isBlank()) "API key: not set" else "API key: …${k.takeLast(4)}"
    }
    private fun writeExportToUri(uri: Uri) {
        val lines = sentenceAdapter.all(); val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        for (s in lines.reversed()) {
            if (s.timestamp > 0) sb.append("TIME: ${fmt.format(Date(s.timestamp))}\n")
            sb.append("SOURCE:\n${s.sourceText}\n\nTRANSLATION:\n${s.translatedText}\n\n")
            if (s.replyText.isNotBlank()) sb.append("AI REPLY:\n${s.replyText}\n\n")
            sb.append("---\n\n")
        }
        try { contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }; toast("Exported ${lines.size} items") }
        catch (_: Exception) { toast("Export failed") }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun isLikelyQuestion(text: String, langTag: String?): Boolean {
    val t = text.trim(); if (t.isEmpty()) return false; if (t.endsWith("?")) return true
    val lower = t.lowercase(); val tag = (langTag ?: "").lowercase()
    val qEn = listOf("what","why","how","who","where","when","which","can","could","would","should","do","does","did","is","are","am","will","may")
    val qCs = listOf("co","proč","proc","jak","kdo","kde","kdy","který","která","které","je","jsou","bude","mohl")
    val qPl = listOf("co","dlaczego","czemu","jak","kto","gdzie","kiedy","który","czy","jest","są")
    val qDe = listOf("was","warum","wie","wer","wo","wann","welche","kann","soll","ist","sind")
    val qEs = listOf("qué","que","por qué","cómo","como","quién","dónde","cuándo","cuál","es","son")
    val qFr = listOf("quoi","pourquoi","comment","qui","où","quand","peux","peut","est","sont")
    val qIt = listOf("che","perché","come","chi","dove","quando","quale","posso","è")
    val qPt = listOf("que","por quê","como","quem","onde","quando","qual","posso","é")
    val qRu = listOf("что","почему","как","кто","где","когда","есть")
    val qUk = listOf("що","чому","як","хто","де","коли","є")
    fun startsWithAny(list: List<String>) = list.any { lower.startsWith("$it ") || lower == it }
    return when {
        tag.startsWith("cs") -> startsWithAny(qCs); tag.startsWith("pl") -> startsWithAny(qPl)
        tag.startsWith("de") -> startsWithAny(qDe); tag.startsWith("es") -> startsWithAny(qEs)
        tag.startsWith("fr") -> startsWithAny(qFr); tag.startsWith("it") -> startsWithAny(qIt)
        tag.startsWith("pt") -> startsWithAny(qPt); tag.startsWith("ru") -> startsWithAny(qRu)
        tag.startsWith("uk") -> startsWithAny(qUk); else -> startsWithAny(qEn)
    }
}
