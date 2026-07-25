package com.example.aicallresponder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

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

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasAllPermissions()) {
                switchEnabled.isChecked = false
                toast("Grant permissions first")
                return@setOnCheckedChangeListener
            }
            if (isChecked && editApiKey.text.isNullOrBlank()) {
                switchEnabled.isChecked = false
                toast("Enter your Claude API key first")
                return@setOnCheckedChangeListener
            }
            saveFields()
            prefs.enabled = isChecked
            toast(if (isChecked) "Auto-answer ON" else "Auto-answer OFF")
        }
    }

    override fun onResume() {
        super.onResume()
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

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
