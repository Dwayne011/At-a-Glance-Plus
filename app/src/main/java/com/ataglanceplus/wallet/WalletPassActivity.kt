package com.ataglanceplus.wallet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full-screen pass pop-up: the pass's barcode or QR code, large, black on white, with the
 * screen brightness turned up as Wallet does so a scanner can read it. Reached only after the
 * unlock gate. Tap anywhere or press back to close.
 */
class WalletPassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        val id = intent.getStringExtra(EXTRA_PASS_ID)
        val pass = id?.let { WalletSource.pass(this, it) }
        if (pass == null) {
            finish()
            return
        }
        setContent { PassPopup(pass) { finish() } }
    }

    @Composable
    private fun PassPopup(pass: WalletPass, onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(pass.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                pass.subtitle?.let { Text(it, color = Color(0xFF9AA0A6), fontSize = 14.sp, textAlign = TextAlign.Center) }

                val barcode = pass.barcode
                if (barcode == null) {
                    Text("This pass has no scannable code.", color = Color(0xFF9AA0A6), fontSize = 14.sp)
                } else {
                    CodeCard(barcode)
                }
                if (pass.details.isNotEmpty()) Details(pass.details)

                Text("Tap anywhere to close", color = Color(0xFF5A606C), fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun CodeCard(barcode: WalletPass.Barcode) {
        val size = (LocalConfiguration.current.screenWidthDp * 2).coerceAtLeast(600)
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(barcode.value) { bitmap = WalletBarcode.render(barcode, size) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val bm = bitmap
                if (bm != null) {
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = "Pass code",
                        contentScale = ContentScale.FillWidth,
                        modifier = if (WalletBarcode.isSquare(barcode.format)) {
                            Modifier.fillMaxWidth().aspectRatio(1f)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                }
                // Only text meant to be read: a 2-D code's raw payload (a boarding pass's BCBP string)
                // is shown only when it couldn't be drawn.
                val text = barcode.displayText
                    ?: barcode.value.takeIf { bm == null || !WalletBarcode.isTwoDimensional(barcode.format) }
                if (text != null) Text(
                    text,
                    color = Color(0xFF111111),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    /** The pass's own details (flight, gate, seat, confirmation…), as Wallet shows them. */
    @Composable
    private fun Details(details: List<Pair<String, String>>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF151922))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            details.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color(0xFF9AA0A6), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PASS_ID = "pass_id"

        fun intent(context: Context, passId: String): Intent =
            Intent(context, WalletPassActivity::class.java)
                .putExtra(EXTRA_PASS_ID, passId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
