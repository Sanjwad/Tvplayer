package ca.sanjwad.tvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0E1116)
val Panel = Color(0xFF171B22)
val Accent = Color(0xFFFF6B5B)
val TextDim = Color(0xFF9AA4B2)
val ErrorRed = Color(0xFFFF7A7A)

val LogoYellow = Color(0xFFFFD166)
val FocusFill = Color(0xFF2A3656)
val SelectedFill = Color(0xFF1F2942)
private val Poppins = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(ca.sanjwad.tvplayer.R.font.poppins_bold, FontWeight.Bold),
    androidx.compose.ui.text.font.Font(ca.sanjwad.tvplayer.R.font.poppins_light, FontWeight.Light),
)

/** The Sanj TV logo: the little TV mark plus "SANJ TV". */
@Composable
fun Logo(size: androidx.compose.ui.unit.TextUnit = 24.sp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(ca.sanjwad.tvplayer.R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size((size.value * 1.45f).dp),
        )
        Spacer(Modifier.width((size.value * 0.45f).dp))
        Text("SANJ", fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = size, color = Color.White, letterSpacing = 1.5.sp)
        Spacer(Modifier.width((size.value * 0.25f).dp))
        Text("TV", fontFamily = Poppins, fontWeight = FontWeight.Light, fontSize = size, color = LogoYellow, letterSpacing = 1.5.sp)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

/** A remote-friendly button/card: clear highlight when focused, tint when selected. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Stay navy and let the coral outline do the highlighting, so coral never mixes into pink.
    val bg = when {
        focused -> FocusFill
        selected -> SelectedFill
        else -> Panel
    }
    Box(
        modifier
            .onFocusChanged {
                if (it.isFocused && !focused) onFocus?.invoke()
                focused = it.isFocused
            }
            .clip(shape)
            .background(bg)
            .border(2.5.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(onClick = onClick),
    ) { content(focused) }
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onClick, modifier = modifier) {
        Text(
            text,
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
fun CenterMessage(title: String, detail: String? = null, loading: Boolean = false) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(16.dp))
        }
        Text(title, fontSize = 20.sp, color = Color.White)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, fontSize = 16.sp, color = TextDim)
        }
    }
}
