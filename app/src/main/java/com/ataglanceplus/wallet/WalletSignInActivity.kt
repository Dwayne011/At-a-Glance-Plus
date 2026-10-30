package com.ataglanceplus.wallet

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.concurrent.thread

/**
 * Signs the user in to Google so [WalletSource] can read their Wallet passes. The flow is the one
 * SmartspacerPlugins' Google Wallet plugin uses (see NOTICE): a warning first, then Google's own
 * EmbeddedSetup page in a WebView; when Google leaves the "oauth_token" cookie, it is traded for
 * the account master token. Nothing is typed into or read by this app except that cookie.
 */
class WalletSignInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) { SignIn() } }
    }

    private enum class Stage { WARNING, WEB, WORKING }

    @Composable
    private fun SignIn() {
        var stage by remember { mutableStateOf(Stage.WARNING) }
        var error by remember { mutableStateOf<String?>(null) }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0E14)) {
            when (stage) {
                Stage.WARNING -> Warning(error, onContinue = { error = null; stage = Stage.WEB }, onCancel = { finish() })
                Stage.WEB -> SignInWeb(
                    onToken = { token ->
                        stage = Stage.WORKING
                        thread {
                            val failure = GoogleAccount.signIn(this@WalletSignInActivity, token)
                            runOnUiThread {
                                if (failure == null) {
                                    thread { WalletSource.sync(this@WalletSignInActivity) }
                                    setResult(RESULT_OK)
                                    finish()
                                } else {
                                    error = failure
                                    clearCookies()
                                    stage = Stage.WARNING
                                }
                            }
                        }
                    },
                    onCancel = { finish() },
                )
                Stage.WORKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF6E5DA8))
                }
            }
        }
    }

    @Composable
    private fun Warning(error: String?, onContinue: () -> Unit, onCancel: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sign in to Google Wallet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (error != null) {
                Box(
                    Modifier.fillMaxWidth().background(Color(0xFF2A1A1A), RoundedCornerShape(12.dp)).padding(14.dp)
                ) { Text(error, color = Color(0xFFFFB4AB), fontSize = 14.sp) }
            }
            Box(
                Modifier.fillMaxWidth().background(Color(0xFF2A1E1E), RoundedCornerShape(16.dp)).padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Please read this first", color = Color(0xFFFFD2C4), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "To show your Wallet passes, this app signs in the way Google Play services does, " +
                            "because Google gives apps no other way to read them. This is powerful and risky:",
                        color = Color(0xFFE2E2E6), fontSize = 14.sp,
                    )
                    listOf(
                        "It stores your account's master sign-in token on this phone (encrypted, and never sent anywhere but Google).",
                        "To Google it looks like Play services, not this app — it does not follow Google's terms.",
                        "Google may flag the sign-in, ask you to re-verify, or stop it working at any time.",
                        "You can undo it any time: sign out here, which deletes the token, or remove it from your Google account's security page.",
                    ).forEach { Text("•  $it", color = Color(0xFFC4C6D0), fontSize = 13.5.sp) }
                }
            }
            Text(
                "Only you use this. Your passes stay on this phone. If you'd rather not, you don't have to — the rest of the app works without it.",
                color = Color(0xFF9AA0A6), fontSize = 13.sp,
            )
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I understand — continue to Google") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun SignInWeb(onToken: (String) -> Unit, onCancel: () -> Unit) {
        AndroidView(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            factory = { context ->
                clearCookies()
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Present as a normal browser so Google serves the embedded setup flow.
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/140.0.0.0 Mobile Safari/537.36"
                    var handled = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (handled) return
                            val token = oauthToken() ?: return
                            handled = true
                            onToken(token)
                        }
                    }
                    loadUrl(GoogleAccount.SIGN_IN_URL)
                }
            },
        )
    }

    /** The "oauth_token" cookie Google leaves once sign-in completes, if present. */
    private fun oauthToken(): String? {
        val cookies = CookieManager.getInstance().getCookie("https://google.com") ?: return null
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("oauth_token=") }
            ?.substringAfter("oauth_token=")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
