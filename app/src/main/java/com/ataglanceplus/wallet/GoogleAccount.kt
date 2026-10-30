package com.ataglanceplus.wallet

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The Google sign-in behind Wallet sync, as SmartspacerPlugins' Google Wallet plugin does it (see
 * NOTICE). Google offers no way for an app to read Wallet passes, so this signs in the way Google
 * Play services does, which Google's terms do not allow:
 *
 *  1. The user signs in on Google's own page (accounts.google.com/EmbeddedSetup) in a WebView;
 *     Google leaves an "oauth_token" cookie.
 *  2. That is traded, as Play services, for the account's long-lived master token.
 *  3. The master token is traded, as Play services, for a short-lived Wallet ("tapandpay") token.
 *
 * Only the master token is kept, encrypted on the phone (see [SecureStore]); signing out deletes
 * it. It can also be revoked from the Google account's security page.
 */
object GoogleAccount {

    private const val TAG = "GoogleAccount"
    const val SIGN_IN_URL = "https://accounts.google.com/EmbeddedSetup"
    private const val GMS = "com.google.android.gms"
    private const val GMS_SIG = "38918a453d07199354f8b19af05ec6562ced5788"
    private const val GMS_VERSION_DEFAULT = "19629032"
    private const val WALLET_SCOPE = "oauth2:https://www.googleapis.com/auth/tapandpay"

    private const val KEY_MASTER = "google_master_token"
    private const val KEY_EMAIL = "google_email"
    private const val KEY_WALLET = "google_wallet_token"
    private const val KEY_WALLET_EXPIRY = "google_wallet_token_expiry"

    fun isSignedIn(context: Context): Boolean = SecureStore.getString(context, KEY_MASTER) != null

    fun email(context: Context): String? = SecureStore.getString(context, KEY_EMAIL)

    /** Step 2: the sign-in page's cookie for the master token. Returns null on success, else why not. */
    fun signIn(context: Context, oauthToken: String): String? {
        val response = post(
            "https://android.clients.google.com/auth",
            mapOf(
                "Token" to oauthToken,
                "lang" to lang(),
                "google_play_services_version" to gmsVersion(context),
                "sdk_version" to Build.VERSION.SDK_INT.toString(),
                "device_country" to Locale.getDefault().country.lowercase(),
                "service" to "ac2dm",
                "get_accountid" to "1",
                "ACCESS_TOKEN" to "1",
                "callerPkg" to GMS,
                "callerSig" to GMS_SIG,
                "add_account" to "1",
                "droidguard_results" to "null",
            ),
        ) ?: return "Google did not answer. Check the connection and try again."
        val token = response["Token"] ?: return "Google did not accept the sign-in" + (response["Error"]?.let { " ($it)" } ?: "") + "."
        SecureStore.putString(context, KEY_MASTER, token)
        SecureStore.putString(context, KEY_EMAIL, response["Email"])
        clearWalletToken(context)
        return null
    }

    /** Step 3: a Wallet token, reused until shortly before it expires. Null when signed out or refused. */
    fun walletToken(context: Context): String? {
        val cached = SecureStore.getString(context, KEY_WALLET)
        val expiry = SecureStore.getString(context, KEY_WALLET_EXPIRY)?.toLongOrNull() ?: 0L
        if (cached != null && System.currentTimeMillis() / 1000 < expiry - 300) return cached
        val master = SecureStore.getString(context, KEY_MASTER) ?: return null
        val (code, response) = postWithCode(
            "https://android.googleapis.com/auth",
            mapOf(
                "lang" to lang(),
                "google_play_services_version" to gmsVersion(context),
                "sdk_version" to Build.VERSION.SDK_INT.toString(),
                "device_country" to Locale.getDefault().country.lowercase(),
                "check_email" to "1",
                "oauth2_foreground" to "1",
                "token_request_options" to "CAA4AVAB",
                "app" to GMS,
                "service" to WALLET_SCOPE,
                "client_sig" to GMS_SIG,
                "system_partition" to "1",
                "callerPkg" to GMS,
                "Token" to master,
                "callerSig" to GMS_SIG,
            ),
            header = "app" to GMS,
        )
        // 403: the master token was revoked (signed out from the Google account's security page).
        if (code == 403) {
            signOut(context)
            return null
        }
        val auth = response?.get("Auth") ?: return null
        SecureStore.putString(context, KEY_WALLET, auth)
        SecureStore.putString(context, KEY_WALLET_EXPIRY, response["Expiry"] ?: (System.currentTimeMillis() / 1000 + 3000).toString())
        return auth
    }

    fun clearWalletToken(context: Context) {
        SecureStore.delete(context, KEY_WALLET)
        SecureStore.delete(context, KEY_WALLET_EXPIRY)
    }

    /** Deletes the tokens and everything synced from Wallet. */
    fun signOut(context: Context) {
        SecureStore.delete(context, KEY_MASTER)
        SecureStore.delete(context, KEY_EMAIL)
        clearWalletToken(context)
        WalletSource.clear(context)
    }

    private fun lang(): String = Locale.getDefault().let { "${it.language}-${it.country}" }

    private fun gmsVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(GMS, 0).longVersionCode.toString() }.getOrDefault(GMS_VERSION_DEFAULT)

    private fun post(url: String, form: Map<String, String>): Map<String, String>? = postWithCode(url, form).second

    private fun postWithCode(url: String, form: Map<String, String>, header: Pair<String, String>? = null): Pair<Int, Map<String, String>?> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            header?.let { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            connection.outputStream.use { out ->
                out.write(form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray())
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            // "Key=value" lines.
            code to body.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        } catch (e: Exception) {
            Log.w(TAG, "auth request failed", e)
            0 to null
        } finally {
            connection.disconnect()
        }
    }
}
