package agency.toku.quiver

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.KeyEvent

/**
 * The screen that appears when a dose is due.
 *
 * A notification is two seconds of sound that can be swiped away in silence,
 * which is the wrong failure mode for a medicine: the reminder works and the
 * dose is still missed. This rings until it is answered.
 *
 * Both buttons hand their work to AlarmReceiver by broadcast instead of doing
 * it here, so everything the alarm can do lives in one place and the
 * notification action and this screen cannot disagree about what "Taken" means.
 *
 * Nothing here is load-bearing. If the phone will not show it, the notification
 * still fires, so this activity can only make things louder, never quieter.
 */
class AlarmRingActivity : Activity() {

  /* One instance at a time. A ringing alarm has to be stoppable from outside
     itself, or "Taken" tapped on the notification leaves the sound playing
     behind a screen that is already gone. */
  companion object {
    @Volatile private var live: AlarmRingActivity? = null

    fun stopLive() { live?.let { it.runOnUiThread { it.finish() } } }
  }

  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null

  private lateinit var ringId: String
  private lateinit var ringLabel: String
  private lateinit var ringDose: String

  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    live = this

    /* Show over the lock screen and wake the display. The flag path covers API
       26, the method path is the one Google now sanctions. */
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true); setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    ringId = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: "supply"
    ringLabel = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ringId
    ringDose = intent.getStringExtra(AlarmScheduler.EXTRA_DOSE) ?: ""

    setContentView(buildUi(ringLabel, ringDose))
    startAlarmSound()
    startVibration()
  }

  /* The identical broadcast the notification action sends. */
  private fun press(actionKey: String) {
    sendBroadcast(Intent(this, AlarmReceiver::class.java).apply {
      putExtra(AlarmScheduler.EXTRA_ID, ringId)
      putExtra(AlarmScheduler.EXTRA_LABEL, ringLabel)
      putExtra(AlarmScheduler.EXTRA_DOSE, ringDose)
      putExtra(actionKey, true)
    })
    finish()
  }

  private fun buildUi(label: String, dose: String): View {
    val ink = Color.parseColor("#F6EAF6")
    val muted = Color.parseColor("#B79ACB")
    val bgStart = Color.parseColor("#140A26")
    val bgEnd = Color.parseColor("#2B0B2E")
    val accent = Color.parseColor("#F472B6")

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(44, 44, 44, 60)
      background = android.graphics.drawable.GradientDrawable().apply {
        colors = intArrayOf(bgStart, bgEnd, bgStart)
        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
      }
    }

    root.addView(TextView(this).apply {
      text = "everdeen"
      textSize = 13f
      setTextColor(muted)
      letterSpacing = 0.3f
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 30)
    })

    /* The label is the whole point, so it gets the weight of the screen. */
    root.addView(TextView(this).apply {
      text = label
      textSize = 30f
      setTextColor(ink)
      typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 14)
    })

    root.addView(TextView(this).apply {
      text = if (dose.isBlank()) "time for this one" else dose
      textSize = 17f
      setTextColor(accent)
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 12)
    })

    root.addView(TextView(this).apply {
      text = "ringing until you answer"
      textSize = 12f
      setTextColor(muted)
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 46)
    })

    root.addView(Button(this).apply {
      text = "taken"
      textSize = 18f
      setTextColor(Color.WHITE)
      setBackgroundColor(accent)
      isAllCaps = false
      setOnClickListener { press(AlarmScheduler.EXTRA_TAKEN_ACTION) }
    }.apply { layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 34 } })

    root.addView(Button(this).apply {
      text = "snooze 10 min"
      textSize = 16f
      setTextColor(ink)
      setBackgroundColor(Color.TRANSPARENT)
      isAllCaps = false
      setOnClickListener { press(AlarmScheduler.EXTRA_SNOOZE_ACTION) }
    })

    return root
  }

  private fun startAlarmSound() {
    try {
      val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: Settings.System.DEFAULT_ALARM_ALERT_URI
      player = MediaPlayer().apply {
        setDataSource(this@AlarmRingActivity, uri)
        /* USAGE_ALARM, not a notification tone, so it obeys the alarm volume
           slider and is allowed through Do Not Disturb. */
        setAudioAttributes(AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build())
        isLooping = true
        prepare()
        start()
      }
    } catch (e: Exception) { /* no ringtone on this device; vibration still carries it */ }
  }

  private fun startVibration() {
    try {
      val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
      }
      vibrator = v
      /* repeat index 0 restarts the waveform, so this buzzes until answered. */
      val pattern = longArrayOf(0, 700, 500, 700, 500, 900)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
      } else {
        @Suppress("DEPRECATION") v.vibrate(pattern, 0)
      }
    } catch (e: Exception) {}
  }

  /* Back must not silently end a ringing alarm, which is the same failure as
     swiping a notification away. */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_BACK) return true
    return super.onKeyDown(keyCode, event)
  }

  override fun onDestroy() {
    live = null
    player?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
    vibrator?.cancel()
    super.onDestroy()
  }
}
