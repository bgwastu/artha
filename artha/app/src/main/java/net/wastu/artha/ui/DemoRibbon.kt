package net.wastu.artha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diagonal corner ribbon, like Flutter's debug banner. Draws only; never intercepts touches. */
@Composable
fun DemoRibbon(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().statusBarsPadding().clipToBounds()) {
        // Long enough that both ends run past the screen edges, so the screen crops it cleanly.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 57.dp, y = 6.dp)
                .rotate(45f)
                .width(140.dp)
                .height(15.dp)
                .background(MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "DEMO",
                color = MaterialTheme.colorScheme.onTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(x = (-2).dp, y = (-3.5).dp),
            )
        }
    }
}
