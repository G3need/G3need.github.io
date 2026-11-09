package agency.toku.quiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real thing: one exact alarm per supply, scheduled at the next time that
 * supply is due, re-armed by the receiver itself after it fires so it keeps
 * repeating forever. One-shot rather than setRepeating because the next time
 * is anchored on "when she last took it", which changes from inside the app.
 *
 * The due-time maths here is the Kotlin twin of nextDose() in index.html. The
 * two must agree or the in-app countdown and the phone alarm will disagree.
 */
object AlarmScheduler {

  const val PREFS = "quiver_bridge"
  const val KEY_SUPPLIES = "supplies"
  const val EXTRA_ID = "supply_id"
  const val EXTRA_LABEL = "label"
  const val EXTRA_DOSE = "dose"
  const val EXTRA_TAKEN_ACTION = "taken_action"
  /* A person pressed snooze. Separate from EXTRA_SNOOZE, which marks the snooze
     alarm itself firing ten minutes later and must ring again. */
  const val EXTRA_SNOOZE_ACTION = "snooze_action"
  const val EXTRA_SNOOZE = "snooze"

  private const val MIN_STEP_MS = 60L * 60 * 1000        /* every is hours, min 1h */
  private const val MIN_AHEAD_MS = 5L * 1000             /* never fire the moment it is set */

  fun canScheduleExact(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
  }

  /** Called from JS: save the supply list and (re)schedule everything that is on. */
  fun saveAndSchedule(ctx: Context, suppliesJson: String): String {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_SUPPLIES, suppliesJson).apply()
    val arr = JSONArray(suppliesJson)
    var scheduled = 0
    var nextAt = 0L
    for (i in 0 until arr.length()) {
      val t = reschedule(ctx, arr.getJSONObject(i))
      if (t != null) { scheduled++; if (nextAt == 0L || t < nextAt) nextAt = t }
    }
    val exact = canScheduleExact(ctx)
    return JSONObject()
      .put("scheduled", scheduled)
      .put("exact", exact)
      .put("degraded", !exact)
      .put("nextAt", nextAt)
      .toString()
  }

  /** Cancel then re-arm one supply at its next due time. Returns null if it is not repeating. */
  fun reschedule(ctx: Context, s: JSONObject): Long? {
    cancel(ctx, s)
    val every = s.optInt("every", 0)
    if (!s.optBoolean("on", true) || every <= 0) return null

    val step = (every * 3600 * 1000L).coerceAtLeast(MIN_STEP_MS)
    val anchor = anchorMs(s)
    /* No start time and never taken is NOT schedulable. The web layer returns null
       here, and falling back to "now" would arm an alarm nobody configured. */
    if (anchor <= 0L) return null

    var t = anchor
    var triggers = 0
    while (t <= System.currentTimeMillis() && triggers < 5000) { t += step; triggers++ }
    if (t - System.currentTimeMillis() < MIN_AHEAD_MS) t += step

    val id = supplyId(s)
    val pi = alarmIntent(ctx, id, s)
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (canScheduleExact(ctx)) {
      am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    } else {
      am.setWindow(AlarmManager.RTC_WAKEUP, t, 10 * 60 * 1000L, pi)
    }
    return t
  }

  /** Where the countdown starts: the last time it was taken if known, else today at "time". */
  private fun anchorMs(s: JSONObject): Long {
    val last = s.optString("lastTaken", "")
    if (!last.isBlank()) {
      val ms = last.toLongOrNull() ?: isoToMs(last)   /* epoch millis or an ISO stamp */
      if (ms > 0L) return ms
    }
    val hhmm = s.optString("time").split(":")
    if (hhmm.size != 2) return -1L
    val h = hhmm[0].trim().toIntOrNull() ?: return -1L
    val m = hhmm[1].trim().toIntOrNull() ?: return -1L
    if (h !in 0..23 || m !in 0..59) return -1L
    val cal = java.util.Calendar.getInstance().apply {
      set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
      set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
  }

  private fun isoToMs(iso: String): Long =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).let {
      it.timeZone = java.util.TimeZone.getTimeZone("UTC"); it.parse(iso)?.time ?: 0L
    }

  fun supplyId(s: JSONObject) = s.optString("id", s.optString("name", "supply"))

  private fun alarmIntent(ctx: Context, id: String, s: JSONObject): PendingIntent {
    val intent = Intent(ctx, AlarmReceiver::class.java).apply {
      putExtra(EXTRA_ID, id)
      putExtra(EXTRA_LABEL, s.optString("label", s.optString("name", id)))
      putExtra(EXTRA_DOSE, s.optString("dose", ""))
    }
    return PendingIntent.getBroadcast(ctx, id.hashCode(), intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  /**
   * Ring again in minutes, without touching the real schedule.
   *
   * A snooze must not call markTaken: taking the dose advances the next one by
   * the full interval, so snoozing would silently push a four-hourly medicine
   * out by four hours. The snooze carries the same id and EXTRA_SNOOZE so the
   * receiver rings again and leaves the schedule alone.
   */
  fun snooze(ctx: Context, id: String, label: String, dose: String, minutes: Int) {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val code = (id + "_snooze").hashCode()
    /* Cancel first. Pressing snooze twice would otherwise queue two alarms and
       the dose would ring twice in ten minutes. */
    PendingIntent.getBroadcast(ctx, code, Intent(ctx, AlarmReceiver::class.java),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
      am.cancel(it); it.cancel()
    }

    val at = System.currentTimeMillis() + minutes * 60 * 1000L
    val pi = PendingIntent.getBroadcast(ctx, code,
      Intent(ctx, AlarmReceiver::class.java).apply {
        putExtra(EXTRA_ID, id)
        putExtra(EXTRA_LABEL, label)
        putExtra(EXTRA_DOSE, dose)
        putExtra(EXTRA_SNOOZE, true)
      }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60 * 1000L, pi)
  }

  fun cancel(ctx: Context, s: JSONObject) {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val id = supplyId(s)
    val pi = PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, AlarmReceiver::class.java),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if (pi != null) { am.cancel(pi); pi.cancel() }
  }

  fun cancelAll(ctx: Context, suppliesJson: String) {
    val arr = JSONArray(suppliesJson)
    for (i in 0 until arr.length()) cancel(ctx, arr.getJSONObject(i))
  }

  /** Re-arm every stored supply. Used on boot, where the web layer is not running. */
  fun rescheduleAll(ctx: Context) {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SUPPLIES, null) ?: return
    val arr = JSONArray(raw)
    for (i in 0 until arr.length()) reschedule(ctx, arr.getJSONObject(i))
  }

  /** The stored list, so the web layer can adopt a "Taken" recorded from a notification. */
  fun readSupplies(ctx: Context): String {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUPPLIES, null)
    return raw ?: "[]"
  }

  /** "Taken" tapped on the notification: stamp lastTaken and re-arm from now. */
  fun markTaken(ctx: Context, id: String): Boolean {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SUPPLIES, null) ?: return false
    val arr = JSONArray(raw)
    var found = false
    for (i in 0 until arr.length()) {
      val s = arr.getJSONObject(i)
      if (supplyId(s) == id) {
        s.put("lastTaken", nowIso()); found = true
        prefs.edit().putString(KEY_SUPPLIES, arr.toString()).apply()
        reschedule(ctx, s)
      }
    }
    return found
  }

  fun nowIso(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).let {
      it.timeZone = java.util.TimeZone.getTimeZone("UTC"); it.format(java.util.Date())
    }
}
