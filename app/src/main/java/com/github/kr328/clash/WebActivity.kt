package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = MainScope()

    private val vpnAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            startClashService()
            webView.postDelayed({ webView.loadUrl(SITE_URL) }, 2500)
        } else {
            Toast.makeText(this, "未授权 VPN，代理无法启动", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("quark.cn") || url.contains("pan.quark")) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .setPackage("com.quark.browser")
                        )
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        connectAndLoad()
    }

    private fun connectAndLoad() {
        scope.launch {
            try {
                val active = withProfile { queryActive() }
                if (active == null || !active.imported) {
                    val uuid = withProfile { create(Profile.Type.Url, SUB_NAME, SUB_URL, null) }
                    withProfile { patch(uuid, SUB_NAME, SUB_URL, 0L, null) }
                    withProfile { commit(uuid, null) }
                    withProfile { queryByUUID(uuid) }?.let { withProfile { setActive(it) } }
                }
            } catch (e: Exception) {
                Toast.makeText(this@WebActivity, "订阅导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val req = startClashService()
            if (req != null) {
                vpnAuthLauncher.launch(req)
            } else {
                delay(2500)
                webView.loadUrl(SITE_URL)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SITE_URL = "https://www.xn--wcv59z.com/mv"
        private const val SUB_URL =
            "https://sub.wjkc66.vip:1443/api/subscript/flclash/69f1ca9326979f2c3ec76dcc/99819b23-5576-44ba-8c2e-150a570f36a5"
        private const val SUB_NAME = "我的订阅"
    }
}
