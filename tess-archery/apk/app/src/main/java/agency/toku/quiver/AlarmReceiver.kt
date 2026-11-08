package agency.toku.quiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Fires at the scheduled time, shows a high-importance notification carrying the
 * supply label and dose, and opens straight back into the app.
 */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    val label = intent.getStringExtra("label") ?: "supply"
    val dose = intent.getStringExtra("dose") ?: ""
    val id = intent.getStringExtra("supply_id") ?: label

    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val ch = NotificationChannel("quiver_supplies", ctx.getString(R.string.alarm_channel_name),
        NotificationManager.IMPORTANCE_HIGH).apply { description = ctx.getString(R.string.alarm_channel_desc) }
      nm.createNotificationChannel(ch)
    }

    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val n = NotificationCompat.Builder(ctx, "quiver_supplies")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(label)
      .setContentText(if (dose.isBlank()) "time for this one" else dose)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(true)
      .setContentIntent(open)
      .build()
    nm.notify(id.hashCode(), n)
  }
}
