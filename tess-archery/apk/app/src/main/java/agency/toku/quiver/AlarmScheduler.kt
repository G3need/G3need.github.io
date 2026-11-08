package agency.toku.quiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real thing: schedule exact alarms that survive Doze, degrade honestly to
 * setWindow() when the exact permission is refused, and keep the whole supply
 * list in one JSON file so the web layer and this scheduler read the same shape.
 */
object AlarmScheduler {

  const val PREFS = "quiver_bridge"
  const val KEY_SUPPLIES = "supplies"
  const val EXTRA_ID = "supply_id"

  fun canScheduleExact(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
  }

  /** Called from JS: save the supply list and (re)schedule everything that is on. */
  fun saveAndSchedule(ctx: Context, suppliesJson: String): String {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SUPPLIES, suppliesJson).apply()
    val arr = JSONArray(suppliesJson)
    var scheduled = 0
    for (i in 0 until arr.length()) {
      val s = arr.getJSONObject(i)
      if (s.optBoolean("on", true) && s.optInt("every", 0) > 0 && s.optString("time").isNotBlank()) {
        schedule(ctx, s); scheduled++
      }
    }
    val exact = canScheduleExact(ctx)
    return JSONObject()
      .put("scheduled", scheduled)
      .put("exact", exact)
      .put("degraded", !exact)
      .toString()
  }

  private fun schedule(ctx: Context, s: JSONObject) {
    val id = s.optString("id", s.optString("name", "supply"))
    val every = s.optInt("every")
    val time = s.optString("time")
    val hhmm = time.split(":")
    if (hhmm.size != 2) return
    val h = hhmm[0].trim().toIntOrNull() ?: return
    val m = hhmm[1].trim().toIntOrNull() ?: return

    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(ctx, AlarmReceiver::class.java).apply {
      putExtra(EXTRA_ID, id)
      putExtra("label", s.optString("label", s.optString("name", id)))
      putExtra("dose", s.optString("dose", ""))
    }
    val pi = PendingIntent.getBroadcast(ctx, id.hashCode(), intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // next occurrence of HH:MM, then every N hours
    val cal = java.util.Calendar.getInstance().apply {
      set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
      set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
      if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    val interval = (every * 3600 * 1000L).coerceAtLeast(60 * 60 * 1000L)
    val exact = canScheduleExact(ctx)
    if (exact) am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, interval, pi)
    else am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, interval.coerceAtLeast(600000L), pi)
  }

  fun cancelAll(ctx: Context, suppliesJson: String) {
    val am = ctx.getSupportAlarmManager()
    val arr = JSONArray(suppliesJson)
    for (i in 0 until arr.length()) {
      val s = arr.getJSONObject(i)
      val id = s.optString("id", s.optString("name", "supply"))
      val pi = PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
      if (pi != null) { am.cancel(pi); pi.cancel() }
    }
  }

  private fun Context.getSupportAlarmManager() = getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
