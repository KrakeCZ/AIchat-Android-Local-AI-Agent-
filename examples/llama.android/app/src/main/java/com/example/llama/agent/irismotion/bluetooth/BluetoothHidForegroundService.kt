package com.example.llama.agent.irismotion.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class BluetoothHidForegroundService : Service() {

    companion object {
        private const val TAG = "BtHidForegroundSvc"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "bt_hid_channel"
        
        const val ACTION_START = "com.example.llama.agent.ACTION_START_BT_HID"
        const val ACTION_STOP = "com.example.llama.agent.ACTION_STOP_BT_HID"
    }

    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Vytvářím BluetoothHidForegroundService")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == ACTION_STOP) {
            Log.i(TAG, "Dostal jsem příkaz k ukončení Služby")
            stopHidService()
            return START_NOT_STICKY
        }

        if (!isServiceRunning) {
            isServiceRunning = true
            Log.i(TAG, "Startuji jako Foreground Service typu connectedDevice")
            
            val notification = createNotification()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 34+ vyžaduje ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                // pro Bluetooth připojení (pokud je cíl API 34+ a běží na Androidu 14+)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0 // Starší Androidy to nevyžadují
                }
                
                try {
                    startForeground(NOTIFICATION_ID, notification, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Chyba při startForeground s typem connectedDevice", e)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Zde inicializujeme vysílač v popředí
            if (HidBridge.transmitter == null) {
                HidBridge.transmitter = BluetoothHidTransmitter(this)
            }
        }
        
        return START_STICKY
    }

    private fun stopHidService() {
        Log.i(TAG, "Ukončuji Foreground Service (notifikaci)")
        // Transmitter záměrně NEUvolňujeme, drží ho AiAgentAccessibilityService
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        isServiceRunning = false
    }

    override fun onDestroy() {
        stopHidService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Nejsme bound service
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Bluetooth PC Myš"
            val descriptionText = "Udržuje spojení IrisMotion s vaším PC"
            val importance = NotificationManager.IMPORTANCE_LOW // Aby to nepípalo
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IrisMotion Remote Mode")
            .setContentText("Telefon nyní funguje jako PC Myš.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) 
            .setOngoing(true)

        return builder.build()
    }
}
