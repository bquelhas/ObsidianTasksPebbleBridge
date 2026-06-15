package com.example.obsidiantaskspebblebridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ObsidianNotif", ">>> O ReminderReceiver ACORDOU! <<<")

        val taskText = intent.getStringExtra("TASK_TEXT") ?: "Lembrete Obsidian"
        // Drop it from the pending-reminders list now that it has fired.
        val alarmId = intent.getIntExtra("ALARM_ID", 0)
        if (alarmId != 0) ReminderStore.remove(context, alarmId)
        // Usamos um ID fixo para garantir que o canal é recriado se mudares configurações
        val channelId = "obsidian_reminders_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            // 1. CRIAR CANAL (Obrigatório para Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Lembretes Pebble",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notificações enviadas pelo relógio"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d("ObsidianNotif", "Canal de notificação verificado.")
            }

            // Stable notification id so the action buttons can dismiss it.
            val notifId = if (alarmId != 0) alarmId else (System.currentTimeMillis() and 0x0FFFFFFF).toInt()

            // Action buttons → handled by BackgroundReceiver (explicit broadcast).
            val doneIntent = Intent(context, BackgroundReceiver::class.java).apply {
                action = BackgroundReceiver.ACTION_NOTIF_DONE
                putExtra("TASK_TEXT", taskText)
                putExtra("NOTIF_ID", notifId)
                putExtra("ALARM_ID", alarmId)
            }
            val donePi = PendingIntent.getBroadcast(
                context, notifId * 2 + 1, doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snoozeIntent = Intent(context, BackgroundReceiver::class.java).apply {
                action = BackgroundReceiver.ACTION_NOTIF_SNOOZE
                putExtra("TASK_TEXT", taskText)
                putExtra("NOTIF_ID", notifId)
            }
            val snoozePi = PendingIntent.getBroadcast(
                context, notifId * 2 + 2, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 2. CONSTRUIR A NOTIFICAÇÃO
            // Usamos android.R.drawable.ic_dialog_info porque nunca falha (ícones mipmap às vezes dão quadrado branco)
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Obsidian Tasks")
                .setContentText(taskText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 500, 200, 500))
                .addAction(android.R.drawable.checkbox_on_background, context.getString(R.string.notif_action_done), donePi)
                .addAction(android.R.drawable.ic_popup_reminder, context.getString(R.string.notif_action_snooze), snoozePi)

            // 3. MOSTRAR (Com verificação de permissões do Android 13)
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(notifId, builder.build())
                    Log.d("ObsidianNotif", "SUCESSO: Notificação enviada ao sistema!")
                } else {
                    Log.e("ObsidianNotif", "ERRO: Falta permissão POST_NOTIFICATIONS.")
                }
            } else {
                notificationManager.notify(notifId, builder.build())
                Log.d("ObsidianNotif", "SUCESSO: Notificação enviada (Android <13)!")
            }
        } catch (e: Exception) {
            Log.e("ObsidianNotif", "CRASH FATAL: ${e.message}", e)
        }
    }
}
