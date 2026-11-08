package agency.toku.quiver

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import android.webkit.JavascriptInterface
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebResourceRequest
import android.content.Intent
import androidx.webkit.WebViewAssetLoader

/**
 * One codebase: the whole app is index.html + assets bundled in the APK.
 * The only native code is the alarm bridge the browser cannot have.
 */
class MainActivity : ComponentActivity() {

  private lateinit var web: WebView

  private companion object {
    /* Android reserves this exact domain for WebViewAssetLoader. Serving the
       bundle here gives it a real https origin instead of file://'s opaque one. */
    const val ASSET_ORIGIN = "https://appassets.androidplatform.net/"
    const val ASSET_ORIGIN_HTTP = "http://appassets.androidplatform.net/"
    const val ASSET_INDEX = "https://appassets.androidplatform.net/assets/index.html"
  }

  /**
   * The app used to be loaded as file:///android_asset/index.html with
   * allowFileAccess = false. That combination loads the document but silently
   * refuses every file subresource, so every <img> came back empty while the
   * inline SVG coaching diagrams still drew: the diagrams are part of the HTML
   * and never touch the filesystem. Serving the same bundle over the asset
   * loader's https origin fixes the images, and also gives the page a real
   * same-origin instead of the opaque origin file:// gets, which is what the
   * store's localStorage and any fetch() need.
   */
  private val assetLoader by lazy {
    WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
      .build()
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    web = findViewById(R.id.web)
    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true          // the app's whole store is localStorage
      databaseEnabled = true
      /* these stay off: we never use file:// any more, and the asset loader
         does not need them */
      allowFileAccess = false
      allowContentAccess = false
      mediaPlaybackRequiresUserGesture = false   // jay call plays without a tap
    }
    web.addJavascriptInterface(Bridge(this), "EverdeenAlarms")
    /* prompt() returns null in a WebView with no WebChromeClient, which is
       indistinguishable from "user cancelled". Every prompt-driven flow silently
       no-ops without this. */
    web.webChromeClient = object : android.webkit.WebChromeClient() {
      override fun onJsAlert(view: WebView, url: String, message: String,
                             result: android.webkit.JsResult): Boolean {
        result.confirm(); return true
      }
      override fun onJsConfirm(view: WebView, url: String, message: String,
                               result: android.webkit.JsResult): Boolean {
        result.confirm(); return true
      }
      override fun onJsPrompt(view: WebView, url: String, message: String,
                              defaultValue: String, result: android.webkit.JsPromptResult): Boolean {
        result.confirm(); return true
      }
    }
    web.webViewClient = object : WebViewClient() {
      /* every request for our own bundle, images included, is answered here */
      override fun shouldInterceptRequest(view: WebView,
                                          request: WebResourceRequest): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(request.url)
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url.toString()
        if (u.startsWith(ASSET_ORIGIN)) return false          // our own bundled app
        // keep the app self contained; external links go to the browser
        if (u.startsWith("http://") || u.startsWith("https://")) {
          startActivity(Intent(Intent.ACTION_VIEW, request.url))
          return true
        }
        return false
      }
    }
    web.loadUrl(ASSET_INDEX)
  }

  override fun onBackPressed() {
    if (web.canGoBack()) web.goBack() else super.onBackPressed()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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

    /**
     * "Taken" can be tapped on the notification while the app is closed. That
     * stamp lives natively, so the web layer has to come and fetch it, or the
     * in-app countdown would disagree with the phone.
     */
    @JavascriptInterface
    fun readSupplies(): String = AlarmScheduler.readSupplies(activity.applicationContext)

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
