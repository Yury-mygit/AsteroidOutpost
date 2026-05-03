package com.example.asteroidoutpost

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREF_FILE             = "g3_settings"
        const val KEY_MIC_ENABLED       = "mic_enabled"
        const val KEY_MUSIC_VOLUME      = "music_volume"
        const val KEY_ENEMY_AGGRESSION  = "enemy_aggression"
        const val EXTRA_RESTART         = "restart"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE)

        val tabGeneral        = findViewById<Button>(R.id.tabGeneral)
        val tabSound          = findViewById<Button>(R.id.tabSound)
        val panelGeneral      = findViewById<View>(R.id.panelGeneral)
        val panelSound        = findViewById<View>(R.id.panelSound)
        val switchMic         = findViewById<Switch>(R.id.switchMic)
        val btnRestart        = findViewById<Button>(R.id.btnRestart)
        val seekMusic         = findViewById<SeekBar>(R.id.seekMusic)
        val tvVolume          = findViewById<TextView>(R.id.tvMusicVolume)
        val seekAggression    = findViewById<SeekBar>(R.id.seekAggression)
        val tvAggressionLabel = findViewById<TextView>(R.id.tvAggressionLabel)
        val btnOk             = findViewById<Button>(R.id.btnOk)

        fun aggressionLabel(level: Int) = when (level) {
            0    -> "Низкая"
            1    -> "Средняя"
            else -> "Высокая"
        }

        // Load saved values
        switchMic.isChecked = prefs.getBoolean(KEY_MIC_ENABLED, true)
        val savedVolume = (prefs.getFloat(KEY_MUSIC_VOLUME, 0.25f) * 100).toInt()
        seekMusic.progress = savedVolume
        tvVolume.text = savedVolume.toString()
        val savedAggression = prefs.getInt(KEY_ENEMY_AGGRESSION, 0)
        seekAggression.progress = savedAggression
        tvAggressionLabel.text = aggressionLabel(savedAggression)

        // Tab switching
        fun showTab(general: Boolean) {
            panelGeneral.visibility = if (general) View.VISIBLE else View.GONE
            panelSound.visibility   = if (general) View.GONE else View.VISIBLE
            tabGeneral.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (general) 0xFF3A3A5C.toInt() else 0xFF1E1E3A.toInt()
            )
            tabSound.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (general) 0xFF1E1E3A.toInt() else 0xFF3A3A5C.toInt()
            )
            tabGeneral.setTextColor(if (general) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            tabSound.setTextColor(if (general) 0xFFAAAAAA.toInt() else 0xFFFFFFFF.toInt())
        }

        tabGeneral.setOnClickListener { showTab(true) }
        tabSound.setOnClickListener   { showTab(false) }

        // Volume slider
        seekMusic.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvVolume.text = progress.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Aggressiveness slider
        seekAggression.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvAggressionLabel.text = aggressionLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Restart
        btnRestart.setOnClickListener {
            savePrefs(prefs, switchMic.isChecked, seekMusic.progress / 100f, seekAggression.progress)
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART, true))
            finish()
        }

        // OK
        btnOk.setOnClickListener {
            savePrefs(prefs, switchMic.isChecked, seekMusic.progress / 100f, seekAggression.progress)
            setResult(RESULT_OK, Intent())
            finish()
        }
    }

    private fun savePrefs(
        prefs: android.content.SharedPreferences,
        micEnabled: Boolean,
        musicVolume: Float,
        enemyAggression: Int = 0
    ) {
        prefs.edit()
            .putBoolean(KEY_MIC_ENABLED, micEnabled)
            .putFloat(KEY_MUSIC_VOLUME, musicVolume)
            .putInt(KEY_ENEMY_AGGRESSION, enemyAggression)
            .apply()
    }
}
