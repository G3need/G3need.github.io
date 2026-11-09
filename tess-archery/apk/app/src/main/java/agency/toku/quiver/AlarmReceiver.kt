package agency.toku.quiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Fires at the scheduled time, rings until it is answered, then re-arms itself
 * for the next dose so a supply repeats without the app ever being opened.
 *
 * A notification is two seconds of sound that can be swiped away in silence,
 * which is the wrong failure mode for a medicine: the reminder works and the
 * dose is still missed. So on top of the notification this sends a full-screen
 * intent and makes the alert ongoing, which means it keeps sounding until
 * "Taken" or "Snooze" is pressed rather than fading out on its own.
 *
 * Every escalation here degrades to the plain notification, so this can only
 * make the reminder louder, never quieter. That is deliberate: a phone that
 * refuses the full-screen takeover still gets exactly the alert it gets today.
 */
class AlarmReceiver : BroadcastReceiver() {

  override fun onReceive(ctx: Context, intent: Intent) {
    val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: "supply"
    val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: id
    val dose = intent.getStringExtra(AlarmScheduler.EXTRA_DOSE) ?: ""

    /* "Taken" tapped on the notification or the ringing screen: record it,
       then re-arm from now. */
    if (intent.getBooleanExtra(AlarmScheduler.EXTRA_TAKEN_ACTION, false)) {
      if (AlarmScheduler.markTaken(ctx, id)) return
    }

    val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)

    notify(ctx, id, label, dose)

    /* A snooze re-firing must not touch the schedule, or a four-hourly dose
       would be pushed a full interval ahead every time it was postponed. */
    if (snooze) return

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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      /* IMPORTANCE_HIGH plus an alarm sound on the alarm stream: it obeys the
         alarm volume slider and Do Not Disturb lets it through, which a
         notification tone on the media stream does not. */
      val ch = NotificationChannel("quiver_supplies", ctx.getString(R.string.alarm_channel_name),
        NotificationManager.IMPORTANCE_HIGH).apply {
        description = ctx.getString(R.string.alarm_channel_desc)
        val tone = android.media.RingtoneManager.getActualDefaultRingtoneUri(
          ctx, android.media.RingtoneManager.TYPE_ALARM)
          ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        setSound(tone, android.media.AudioAttributes.Builder()
          .setUsage(android.media.AudioAttributes.USAGE_ALARM)
          .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build())
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 700, 500, 700, 500, 900)
        /* setBypassDnd is API 28 and the floor is 26, so this stays conditional.
           Skipped on an older phone the alert still sounds normally. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setBypassDnd(true)
      }
      nm.createNotificationChannel(ch)
    }

    /* The ringing screen. Only shown if the system grants the full-screen
       intent, and asking is safe: a refusal leaves the notification intact. */
    val ring = PendingIntent.getActivity(ctx, id.hashCode(),
      Intent(ctx, AlarmRingActivity::class.java).apply {
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_LABEL, label)
        putExtra(AlarmScheduler.EXTRA_DOSE, dose)
      }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /* A dedicated receiver instance per action so the extras cannot be confused. */
    val taken = PendingIntent.getBroadcast(ctx, (id + "_taken").hashCode(),
      Intent(ctx, AlarmReceiver::class.java).apply {
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_TAKEN_ACTION, true)
      }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val snoozeBtn = PendingIntent.getBroadcast(ctx, (id + "_snooze").hashCode(),
      Intent(ctx, AlarmReceiver::class.java).apply {
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_LABEL, label)
        putExtra(AlarmScheduler.EXTRA_DOSE, dose)
        putExtra(AlarmScheduler.EXTRA_SNOOZE, true)
      }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val n = NotificationCompat.Builder(ctx, "quiver_supplies")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(label)
      .setContentText(if (dose.isBlank()) "dose due" else dose)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      /* Ongoing so it cannot be swiped away, which is how a real alarm behaves
         and is how it keeps sounding until answered. */
      .setOngoing(true)
      .setAutoCancel(false)
      .setContentIntent(ring)
      .setFullScreenIntent(ring, true)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Taken", taken)
      .addAction(android.R.drawable.ic_menu_recent_history, "Snooze 10", snoozeBtn)
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
