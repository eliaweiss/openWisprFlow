package app.openwisprflow

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var micStatus: TextView
    private lateinit var allowMicrophone: Button
    private lateinit var diagnostics: TextView
    private var player: MediaPlayer? = null

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
