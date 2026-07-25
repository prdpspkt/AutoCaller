package com.example.aicallresponder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var owner: DeviceOwnerManager

    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editLanguage: EditText
    private lateinit var editSttEngine: EditText
    private lateinit var editWhisperModel: EditText
    private lateinit var editGreeting: EditText
    private lateinit var editSystem: EditText
    private lateinit var editGreetingAudio: EditText
    private lateinit var switchEnabled: CompoundButton
    private lateinit var textPermStatus: TextView
    private lateinit var textOwnerStatus: TextView

    private var testTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        owner = DeviceOwnerManager(this)

        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editLanguage = findViewById(R.id.editLanguage)
        editSttEngine = findViewById(R.id.editSttEngine)
        editWhisperModel = findViewById(R.id.editWhisperModel)
        editGreeting = findViewById(R.id.editGreeting)
        editSystem = findViewById(R.id.editSystem)
        editGreetingAudio = findViewById(R.id.editGreetingAudio)
        switchEnabled = findViewById(R.id.switchEnabled)
        textPermStatus = findViewById(R.id.textPermStatus)
        textOwnerStatus = findViewById(R.id.textOwnerStatus)

        loadIntoFields()

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveFields()
            toast("Saved")
        }
        findViewById<Button>(R.id.btnManageModels).setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btnVoiceCheck).setOnClickListener { checkVoice() }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasAllPermissions()) {
                switchEnabled.isChecked = false
                toast("Grant permissions first")
                return@setOnCheckedChangeListener
            }
            if (isChecked && editApiKey.text.isNullOrBlank()) {
                switchEnabled.isChecked = false
                toast("Enter your DeepSeek API key first")
                return@setOnCheckedChangeListener
            }
            saveFields()
            prefs.enabled = isChecked
            // Start/stop the persistent monitor here (foreground => allowed to start the mic FGS).
            if (isChecked) CallHandlerService.start(this) else CallHandlerService.stop(this)
            toast(if (isChecked) "Auto-answer ON (running in background)" else "Auto-answer OFF")
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect changes made in the model manager (it writes prefs directly).
        editWhisperModel.setText(prefs.whisperModelPath)
        editSttEngine.setText(prefs.sttEngine)
        refreshPermissionStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val denied = permissions.filterIndexed { i, _ ->
                grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isEmpty()) {
                toast("All permissions granted")
            } else {
                toast("Missing: ${denied.joinToString { it.substringAfterLast('.') }}")
            }
            refreshPermissionStatus()
        }
    }

    private fun loadIntoFields() {
        editApiKey.setText(prefs.apiKey)
        editModel.setText(prefs.model)
        editLanguage.setText(prefs.language)
        editSttEngine.setText(prefs.sttEngine)
        editWhisperModel.setText(prefs.whisperModelPath)
        editGreeting.setText(prefs.greeting)
        editSystem.setText(prefs.systemPrompt)
        editGreetingAudio.setText(prefs.greetingAudioPath)
        switchEnabled.isChecked = prefs.enabled
    }

    private fun saveFields() {
        prefs.apiKey = editApiKey.text.toString()
        prefs.model = editModel.text.toString().ifBlank { Prefs.DEFAULT_MODEL }
        prefs.language = editLanguage.text.toString().ifBlank { Prefs.DEFAULT_LANGUAGE }
        prefs.sttEngine = editSttEngine.text.toString().ifBlank { Prefs.DEFAULT_STT_ENGINE }
        prefs.whisperModelPath = editWhisperModel.text.toString()
        prefs.greeting = editGreeting.text.toString().ifBlank { Prefs.DEFAULT_GREETING }
        prefs.systemPrompt = editSystem.text.toString().ifBlank { Prefs.DEFAULT_SYSTEM }
        prefs.greetingAudioPath = editGreetingAudio.text.toString()
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun hasAllPermissions(): Boolean = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        // As Device Owner we can grant everything silently — no dialogs.
        if (owner.isDeviceOwner()) {
            val ok = owner.grantRuntimePermissions(DeviceOwnerManager.requiredRuntimePermissions())
            toast(if (ok) "Granted silently (Device Owner)" else "Some grants failed — see logs")
            refreshPermissionStatus()
            return
        }
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            toast("Already granted")
            refreshPermissionStatus()
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    private fun refreshPermissionStatus() {
        textPermStatus.text = if (hasAllPermissions()) {
            "Permissions: granted ✓"
        } else {
            "Permissions: missing — tap “Grant permissions”"
        }
        textOwnerStatus.text = if (owner.isDeviceOwner()) {
            "Device Owner: yes ✓ (silent grants + auto hang-up)"
        } else {
            "Device Owner: no (see README to provision via adb dpm)"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // --- Voice (TTS) diagnostics ---------------------------------------------------------------

    /** Initializes TTS, reports whether the configured language voice is installed, and speaks a
     *  test line out loud (on the media stream so it's audible outside a call). */
    private fun checkVoice() {
        toast("Checking voice engine…")
        testTts?.shutdown()
        testTts = TextToSpeech(this) { status -> runOnUiThread { onTestTtsInit(status) } }
    }

    private fun onTestTtsInit(status: Int) {
        val tts = testTts ?: return
        if (status != TextToSpeech.SUCCESS) {
            showVoiceDialog(
                "No TTS engine initialized (status=$status).\n\nInstall a text-to-speech engine " +
                    "(e.g. Google Text-to-speech) from the Play Store, then set it as default.",
                canInstall = true
            )
            return
        }

        val tag = prefs.language.ifBlank { Prefs.DEFAULT_LANGUAGE }
        val locale = Locale.forLanguageTag(tag)
        val availability = try { tts.isLanguageAvailable(locale) } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        val engine = tts.defaultEngine ?: "unknown"
        val installed = availability >= TextToSpeech.LANG_AVAILABLE

        val availText = when (availability) {
            TextToSpeech.LANG_MISSING_DATA -> "voice data MISSING (needs install)"
            TextToSpeech.LANG_NOT_SUPPORTED -> "NOT supported by this engine"
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "installed ✓"
            else -> "unknown ($availability)"
        }

        // Speak a test line audibly (media stream) so the user can confirm sound works.
        tts.language = if (installed) locale else Locale.US
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val phrase = if (installed) "नमस्ते, यो आवाज परीक्षण हो।"
        else "Test. The $tag voice is not installed."
        tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "voice-test")

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        showVoiceDialog(
            "Engine: $engine\n" +
                "Language $tag: $availText\n" +
                "Media volume: $vol / $maxVol\n\n" +
                (if (installed) "A test phrase should have played just now. If you heard nothing, " +
                    "raise the volume."
                else "The Nepali voice isn't installed. Tap “Install voice”, or open TTS settings " +
                    "to download the Nepali voice and set the engine as default.") +
                "\n\nNote: during a real call the reply is routed to the call so the caller hears " +
                "it over the speakerphone.",
            canInstall = !installed
        )
    }

    private fun showVoiceDialog(message: String, canInstall: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Voice check")
            .setMessage(message)
            .setPositiveButton("TTS settings") { _, _ -> openTtsSettings() }
            .setNegativeButton("Close", null)
        if (canInstall) {
            builder.setNeutralButton("Install voice") { _, _ -> installTtsData() }
        }
        builder.show()
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                toast("Couldn't open settings")
            }
        }
    }

    private fun installTtsData() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: Exception) {
            toast("This engine has no install screen — use TTS settings instead")
            openTtsSettings()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testTts?.shutdown()
        testTts = null
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
