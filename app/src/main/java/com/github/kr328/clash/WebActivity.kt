package com.github.kr328.clash

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class WebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var navProgress: ProgressBar
    private val scope = MainScope()
    private var loginAttempts = 0
    private var loginDone = false
    private var mainLoaded = false
    private var urlLoaded = false
    private var navClickBound = false

    private val loadTimeout = Runnable { loadSite() }

    private inner class NavBridge {
        @JavascriptInterface
        fun onNavStart() {
            webView.post { navProgress.visibility = View.VISIBLE }
        }
    }

    private val clashObserver = object : Broadcasts.Observer {
        override fun onStarted() {
            webView.removeCallbacks(loadTimeout)
            loadSite()
        }
        override fun onServiceRecreated() {}
        override fun onStopped(cause: String?) {}
        override fun onProfileChanged() {}
        override fun onProfileUpdateCompleted(uuid: UUID?) {}
        override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {}
        override fun onProfileLoaded() {}
    }

    private val vpnAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            startClashService()
        } else {
            Toast.makeText(this, "未授权 VPN，代理无法启动", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = WebView(this)
        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@WebActivity).apply { isIndeterminate = true })
            addView(TextView(this@WebActivity).apply {
                text = "正在连接代理，请稍候…"
                setPadding(0, 24, 0, 0)
            })
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        root.addView(
            loadingView,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        navProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(
            navProgress,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, (4 * resources.displayMetrics.density).toInt(), Gravity.TOP)
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(NavBridge(), "AndroidNav")

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                navProgress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                navProgress.visibility = View.GONE
                val u = url ?: return
                if (u.contains("/user/login")) {
                    injectLogin()
                    return
                }
                if (u.contains("/mv")) {
                    webView.evaluateJavascript(
                        "(function(){var t=document.body?document.body.innerText:'';" +
                        "if(/安全验证|计算中|浏览器计算验证/.test(t)) return 'POW';" +
                        "if(/受限|登录后继续/.test(t)) return 'RESTRICTED';" +
                        "return 'OK';})();"
                    ) { r ->
                        when (r) {
                            "\"RESTRICTED\"" -> view?.loadUrl(LOGIN_URL)
                            "\"OK\"" -> {
                                mainLoaded = true
                                loadingView.visibility = View.GONE
                                CookieManager.getInstance().flush()
                                if (!navClickBound) {
                                    navClickBound = true
                                    webView.evaluateJavascript("document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a'):null;if(a&&a.href&&a.href.indexOf('javascript:')!==0){try{AndroidNav.onNavStart();}catch(x){}}},true);", null)
                                }
                            }
                        }
                    }
                    return
                }
                if (loginDone && !mainLoaded) {
                    view?.loadUrl(SITE_URL)
                }
            }

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

        Remote.broadcasts.addObserver(clashObserver)
        connectAndLoad()
    }

    private fun loadSite() {
        if (urlLoaded) return
        urlLoaded = true
        webView.loadUrl(SITE_URL)
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

            if (Remote.broadcasts.clashRunning) {
                loadSite()
                return@launch
            }

            webView.postDelayed(loadTimeout, 8000)
            val req = startClashService()
            if (req != null) {
                vpnAuthLauncher.launch(req)
            }
        }
    }

    private fun injectLogin() {
        val js = """
            (function(){
              try{
                var u=document.querySelector('input[name=username]');
                var p=document.querySelector('input[name=password]');
                var b=document.querySelector('#button');
                if(!u||!p||!b) return 'NOFORM';
                if(u.value) return 'FILLED';
                var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
                s.call(u,'$ACCOUNT');
                u.dispatchEvent(new Event('input',{bubbles:true}));
                s.call(p,'$PASSWORD');
                p.dispatchEvent(new Event('input',{bubbles:true}));
                b.click();
                return 'OK';
              }catch(e){return 'ERR';}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            when (r) {
                "\"OK\"" -> {
                    loginDone = true
                    loginAttempts = 0
                }
                "\"NOFORM\"" -> {
                    if (loginAttempts < 12) {
                        loginAttempts++
                        webView.postDelayed({ injectLogin() }, 600)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Remote.broadcasts.removeObserver(clashObserver)
        webView.removeCallbacks(loadTimeout)
        CookieManager.getInstance().flush()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SITE_URL = "https://www.xn--wcv59z.com/mv"
        private const val LOGIN_URL = "https://www.xn--wcv59z.com/user/login"
        private const val ACCOUNT = "415943059@qq.com"
        private const val PASSWORD = "123456"
        private const val SUB_URL =
            "https://sub.wjkc66.vip:1443/api/subscript/flclash/69f1ca9326979f2c3ec76dcc/99819b23-5576-44ba-8c2e-150a570f36a5"
        private const val SUB_NAME = "我的订阅"
    }
}
