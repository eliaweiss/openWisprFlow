package app.openwisprflow

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var micStatus: TextView
    private lateinit var allowMicrophone: Button
    private lateinit var diagnostics: TextView
    private lateinit var apiKey: EditText
    private lateinit var apiKeyStatus: TextView
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        micStatus = findViewById(R.id.mic_status)
        allowMicrophone = findViewById(R.id.allow_microphone)
        diagnostics = findViewById(R.id.diagnostics)
        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        allowMicrophone.setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_PERMISSIONS,
            )
        }
        findViewById<Button>(R.id.play_last_take).setOnClickListener { playLastTake() }
        findViewById<Button>(R.id.open_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        apiKey = findViewById(R.id.api_key)
        apiKeyStatus = findViewById(R.id.api_key_status)
        findViewById<Button>(R.id.api_key_save).setOnClickListener {
            val key = apiKey.text.toString().trim()
            if (key.isNotEmpty()) ApiKeyStore.set(this, key)
            apiKey.text.clear()
            refresh()
        }
        findViewById<Button>(R.id.api_key_remove).setOnClickListener {
            ApiKeyStore.clear(this)
            refresh()
        }
        findViewById<Button>(R.id.api_key_test).setOnClickListener { testKey() }

        val dictionary = findViewById<EditText>(R.id.dictionary)
        dictionary.setText(Dictionary.format(Dictionary.load(this)))
        findViewById<Button>(R.id.dictionary_save).setOnClickListener {
            val entries = Dictionary.parse(dictionary.text.toString())
            Dictionary.save(this, entries)
            dictionary.setText(Dictionary.format(entries))
            Toast.makeText(this, getString(R.string.dictionary_saved, entries.size), Toast.LENGTH_SHORT).show()
        }
        findViewById<Switch>(R.id.screen_context).apply {
            isChecked = AppSettings.useScreenContext(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppSettings.setUseScreenContext(this@MainActivity, checked) }
        }

        findViewById<Switch>(R.id.live_transcription).apply {
            isChecked = AppSettings.useLiveTranscription(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppSettings.setUseLiveTranscription(this@MainActivity, checked) }
        }
        findViewById<Switch>(R.id.cleanup).apply {
            isChecked = AppSettings.useCleanup(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppSettings.setUseCleanup(this@MainActivity, checked) }
        }
        val prompt = findViewById<EditText>(R.id.cleanup_prompt)
        prompt.setText(AppSettings.cleanupPrompt(this))
        findViewById<Button>(R.id.cleanup_prompt_save).setOnClickListener {
            AppSettings.setCleanupPrompt(this, prompt.text.toString())
            prompt.setText(AppSettings.cleanupPrompt(this))
            Toast.makeText(this, R.string.cleanup_prompt_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.cleanup_prompt_reset).setOnClickListener {
            AppSettings.setCleanupPrompt(this, "")
            prompt.setText(AppSettings.cleanupPrompt(this))
        }
        findViewById<Button>(R.id.cleanup_eval).setOnClickListener { runCleanupEval(prompt.text.toString()) }
    }

    /** Runs the evaluation set against the prompt as currently typed, saved or not. */
    private fun runCleanupEval(prompt: String) {
        val results = findViewById<TextView>(R.id.cleanup_eval_results)
        val key = ApiKeyStore.get(this)
        if (key == null) {
            results.setText(R.string.api_key_none)
            return
        }
        val total = CleanupEval.CASES.size
        val lines = mutableListOf<String>()
        results.text = getString(R.string.cleanup_eval_running, 1, total)
        thread(name = "openWisprFlow-cleanup-eval") {
            var passed = 0
            CleanupEval.run(key, prompt) { result ->
                if (result.passed) passed++
                val mark = if (result.passed) "PASS" else "FAIL"
                val detail = result.output?.let { "\"$it\"" } ?: "error: ${result.error}"
                val passedSoFar = passed
                main.post {
                    if (isDestroyed) return@post
                    lines += "$mark  ${result.case.name}\n      $detail"
                    val header = if (lines.size == total) {
                        getString(R.string.cleanup_eval_done, passedSoFar, total)
                    } else {
                        getString(R.string.cleanup_eval_running, lines.size + 1, total)
                    }
                    results.text = (listOf(header) + lines).joinToString("\n")
                }
            }
        }
    }

    /** Transcribes one second of silence: proves the key works and the model is available to it. */
    private fun testKey() {
        val key = ApiKeyStore.get(this)
        if (key == null) {
            apiKeyStatus.setText(R.string.api_key_none)
            return
        }
        apiKeyStatus.setText(R.string.api_key_testing)
        val silence = File(cacheDir, "silence.wav")
        thread(name = "openWisprFlow-key-test") {
            val result = runCatching {
                WavWriter(silence, AudioCapture.SAMPLE_RATE).use { it.write(ShortArray(AudioCapture.SAMPLE_RATE), AudioCapture.SAMPLE_RATE) }
                TranscriptionCall(key, silence, TranscriptionOptions(TranscriptionOptions.SMART)).execute()
            }
            silence.delete()
            main.post {
                if (isDestroyed) return@post
                apiKeyStatus.text = result.fold(
                    onSuccess = { getString(R.string.api_key_test_ok) },
                    onFailure = { getString(R.string.api_key_test_failed, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onPause() {
        player?.release()
        player = null
        super.onPause()
    }

    private fun refresh() {
        status.setText(if (isServiceEnabled()) R.string.status_enabled else R.string.status_disabled)
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.visibility = if (micGranted) View.VISIBLE else View.GONE
        allowMicrophone.visibility = if (micGranted) View.GONE else View.VISIBLE
        diagnostics.text = MicDiagnostics.summary(this)
        apiKeyStatus.setText(if (ApiKeyStore.has(this)) R.string.api_key_saved else R.string.api_key_none)
    }

    private fun playLastTake() {
        val file = MicDiagnostics.lastTakeFile(this)?.let(::File)?.takeIf { it.exists() }
        if (file == null) {
            Toast.makeText(this, R.string.no_take, Toast.LENGTH_SHORT).show()
            return
        }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); player = null }
            prepare()
            start()
        }
    }

    private fun isServiceEnabled(): Boolean {
        val ours = ComponentName(this, DictationAccessibilityService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == ours }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
