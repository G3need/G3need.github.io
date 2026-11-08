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
 * supply label and dose, then re-arms itself for the next dose so the supply
 * keeps repeating without the app ever being opened.
 *
 * Two actions: "Taken" stamps lastTaken and re-arms from now, "Later" just
 * dismisses (the dose stays due and the next alarm will still come).
 */
class AlarmReceiver : BroadcastReceiver() {

  override fun onReceive(ctx: Context, intent: Intent) {
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: "supply"
    val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: id
    val dose = intent.getStringExtra(AlarmScheduler.EXTRA_DOSE) ?: ""

    /* "Taken" tapped on the notification: record it, then re-arm from now. */
    if (intent.getBooleanExtra(AlarmScheduler.EXTRA_TAKEN_ACTION, false)) {
      if (AlarmScheduler.markTaken(ctx, id)) return
    }

    notify(ctx, id, label, dose)

    /* Re-arm for the next dose. Without this the supply fires exactly once. */
    val raw = ctx.getSharedPreferences(AlarmScheduler.PREFS, Context.MODE_PRIVATE)
      .getString(AlarmScheduler.KEY_SUPPLIES, null) ?: return
    val arr = org.json.JSONArray(raw)
    for (i in 0 until arr.length()) {
      val s = arr.getJSONObject(i)
      if (AlarmScheduler.supplyId(s) == id && s.optBoolean("on", true) && s.optInt("every", 0) > 0) {
        AlarmScheduler.reschedule(ctx, s)
        return
      }
    }
  }

  private fun notify(ctx: Context, id: String, label: String, dose: String) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val ch = NotificationChannel("quiver_supplies", ctx.getString(R.string.alarm_channel_name),
        NotificationManager.IMPORTANCE_HIGH).apply { description = ctx.getString(R.string.alarm_channel_desc) }
      nm.createNotificationChannel(ch)
    }

    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /* A dedicated receiver instance per action so the extras cannot be confused. */
    val taken = PendingIntent.getBroadcast(ctx, (id + "_taken").hashCode(),
      Intent(ctx, AlarmReceiver::class.java).apply {
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_TAKEN_ACTION, true)
      }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val n = NotificationCompat.Builder(ctx, "quiver_supplies")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(label)
      .setContentText(if (dose.isBlank()) "time for this one" else dose)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(true)
      .setContentIntent(open)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Taken", taken)
      .build()
    nm.notify(id.hashCode(), n)
  }
}

/**
 * Alarms are held in memory by the OS and are lost on every reboot. This puts
 * them back from the same JSON the web layer wrote, so a reboot costs nothing.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
        intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
        intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      AlarmScheduler.rescheduleAll(ctx)
    }
  }
}
