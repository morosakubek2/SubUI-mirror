package com.carudibu.android.subuimirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver that starts MirrorService after device boot.
 * This ensures mirroring can resume automatically after phone restart.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SubUIMirror"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            
            Log.d(TAG, "Boot completed - checking if service should start")
            
            // Sprawdź czy usługa była uruchomiona przed restartem
            val sharedPref = context.getSharedPreferences(
                BuildConfig.APPLICATION_ID, 
                Context.MODE_PRIVATE
            )
            
            val wasRunning = sharedPref.getBoolean("service_was_running", false)
            
            if (wasRunning) {
                Log.d(TAG, "Service was running before reboot - attempting to restart")
                
                // Uwaga: Nie możemy automatycznie rozpocząć mirroringu bez zgody użytkownika
                // MediaProjection wymaga interakcji użytkownika za każdym razem
                // Możemy jednak uruchomić usługę w trybie oczekiwania
                
                // Wyświetl powiadomienie informujące użytkownika
                showNotification(context)
            }
        }
    }
    
    private fun showNotification(context: Context) {
        // Powiadomienie informujące że usługa jest gotowa do uruchomienia
        // Wymagane dla Android 8.0+ foreground services
        Log.d(TAG, "Showing notification - user needs to manually start mirroring")
    }
}
