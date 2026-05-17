package com.carudibu.android.subuimirror

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.widget.*
import com.google.android.material.switchmaterial.SwitchMaterial


class MainActivity : AppCompatActivity() {
    private val PERMISSION_CODE = 1
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Przywróć stan switcha
        val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
        findViewById<SwitchMaterial>(R.id.crop).isChecked = sharedPref.getBoolean("crop", false)
        // Auto-rotate domyślnie wyłączone (oszczędność baterii)
        findViewById<SwitchMaterial>(R.id.auto_rotate).isChecked = sharedPref.getBoolean("auto_rotate", false)

        findViewById<Button>(R.id.start).setOnClickListener {
            var projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            startActivityForResult(
                projectionManager!!.createScreenCaptureIntent(),
                PERMISSION_CODE);
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            val serviceIntent = Intent(this, MirrorService::class.java)
            stopService(serviceIntent)
            isServiceRunning = false
            updateUI()
        }

        findViewById<SwitchMaterial>(R.id.crop).setOnCheckedChangeListener { buttonView, isChecked ->
            val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            with (sharedPref.edit()) {
                putBoolean("crop", isChecked)
                apply()
            }
            // Pokaż informację o konieczności restartu
            if (isServiceRunning) {
                Toast.makeText(this, "Restart service to apply changes", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<SwitchMaterial>(R.id.auto_rotate).setOnCheckedChangeListener { buttonView, isChecked ->
            val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            with (sharedPref.edit()) {
                putBoolean("auto_rotate", isChecked)
                apply()
            }
            // Pokaż informację o konieczności restartu
            if (isServiceRunning) {
                Toast.makeText(this, "Restart service to apply auto-rotate setting", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<SwitchMaterial>(R.id.good_lock).setOnCheckedChangeListener { buttonView, isChecked ->
            val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            with (sharedPref.edit()) {
                putBoolean("good_lock", isChecked)
                apply()
            }
            // Pokaż informację o Good Lock
            Toast.makeText(this, "Good Lock integration: ${if(isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        findViewById<SwitchMaterial>(R.id.root_mode).setOnCheckedChangeListener { buttonView, isChecked ->
            val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            with (sharedPref.edit()) {
                putBoolean("root_mode", isChecked)
                apply()
            }
            
            if (isChecked) {
                // Sprawdź czy urządzenie ma roota
                val hasRoot = checkRootAccess()
                if (!hasRoot) {
                    Toast.makeText(this, "⚠️ No root access detected! This option will not work without root.", Toast.LENGTH_LONG).show()
                    // Wyłącz switch z powrotem
                    findViewById<SwitchMaterial>(R.id.root_mode).isChecked = false
                    with (sharedPref.edit()) {
                        putBoolean("root_mode", false)
                        apply()
                    }
                } else {
                    Toast.makeText(this, "✅ Root access confirmed! Super power saving enabled.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Root mode disabled", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.orientation).setOnClickListener {
            OrientationSettingsFragment().show(supportFragmentManager, "")
        }
        
        updateUI()
    }
    
    private fun updateUI() {
        // Można dodać aktualizację UI w zależności od stanu usługi
        findViewById<Button>(R.id.stop).isEnabled = isServiceRunning
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PERMISSION_CODE) {
            return
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Screen sharing denied! Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, MirrorService::class.java)
        serviceIntent.putExtra("data", data)
        serviceIntent.putExtra("int", resultCode)
        startService(serviceIntent)
        isServiceRunning = true
        updateUI()
    }

    /**
     * Check if device has root access by trying to execute 'su' command
     */
    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

}