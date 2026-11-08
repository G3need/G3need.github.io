package agency.toku.quiver

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.webkit.JavascriptInterface
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebResourceRequest

/**
 * One codebase: the whole app is index.html + assets bundled in the APK.
 * The only native code is the alarm bridge the browser cannot have.
 */
class MainActivity : ComponentActivity() {

  private lateinit var web: WebView

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    web = findViewById(R.id.web)
    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true          // the app's whole store is localStorage
      databaseEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      mediaPlaybackRequiresUserGesture = false   // jay call plays without a tap
    }
    web.addJavascriptInterface(Bridge(this), "QuiverAlarms")
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url.toString()
        if (u.startsWith("file:///android_asset")) return false
        // keep the app self contained; external links go to the browser
        if (u.startsWith("http://") || u.startsWith("https://")) {
          startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
          return true
        }
        return false
      }
    }
    web.loadUrl("file:///android_asset/index.html")
  }

  override fun onBackPressed() {
    if (web.canGoBack()) web.goBack() else super.onBackPressed()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      web.evaluateJavascript("if (window.__onAlarmPermission) window.__onAlarmPermission(true);", null)
    }
  }

  /** What JS calls. Everything returns a JSON string so the web side stays dumb. */
  class Bridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun canScheduleExact(): String = AlarmScheduler.canScheduleExact(activity).toString()

    @JavascriptInterface
    fun requestExactAlarms(): String {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = activity.getSystemService(android.app.AlarmManager::class.java)
        if (!am.canScheduleExactAlarms()) {
          activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
          return "requested"
        }
      }
      return "already"
    }

    @JavascriptInterface
    fun saveSupplies(json: String): String =
      AlarmScheduler.saveAndSchedule(activity.applicationContext, json)

    @JavascriptInterface
    fun cancelSupplies(json: String): String {
      AlarmScheduler.cancelAll(activity.applicationContext, json)
      return "cancelled"
    }

    @JavascriptInterface
    fun requestNotifications(): String {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
        if (!granted) {
          activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
          return "requested"
        }
      }
      return "granted"
    }
  }
}
