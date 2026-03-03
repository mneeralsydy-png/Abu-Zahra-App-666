package com.abuzahra.tracker

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.abuzahra.tracker.services.DataSyncWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        
        webView.addJavascriptInterface(ChildWebInterface(this), "AndroidNative")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/child_index.html")
    }
    
    fun startWorker() {
        // تشغيل العامل للبيانات غير اللحظية (اختياري)
        val workRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "data_sync_work",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
