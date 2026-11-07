package ca.sanjwad.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.sanjwad.tvplayer.BuildConfig
import ca.sanjwad.tvplayer.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks for a newer build once per launch and offers a one-click update. */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("ask") } // ask | downloading | allow | error
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(3000) // let the app settle first
        update = withContext(Dispatchers.IO) { Updater.check() }
    }
    val u = update ?: return
    if (dismissed) return

    LaunchedEffect(u, state) { delay(150); runCatching { fr.requestFocus() } }

    fun start() {
        if (!Updater.ensureInstallAllowed(ctx)) { state = "allow"; return }
        state = "downloading"; progress = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Updater.download(ctx) { p -> scope.launch { progress = p } } }
            }
            r.onSuccess { Updater.install(ctx, it); state = "ask" }
                .onFailure { error = it.message ?: "Download failed"; state = "error" }
        }
    }

    // A real dialog window, so the cursor stays on these buttons. Back = Later (except mid-download).
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (state != "downloading") dismissed = true },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(500.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(28.dp)) {
            Text("Update available", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Sanj TV build ${u.versionCode} is ready. You have build ${BuildConfig.VERSION_CODE}.", fontSize = 15.sp, color = TextDim)
            Spacer(Modifier.height(18.dp))
            if (state == "downloading") {
                Text("Downloading…  ${(progress * 100).toInt()}%", fontSize = 15.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Accent))
                }
            } else {
                if (state == "allow") {
                    Text("Android needs your OK first: turn on Sanj TV in the screen that just opened, press Back, then choose Update now again.",
                        fontSize = 14.sp, color = Accent)
                    Spacer(Modifier.height(12.dp))
                }
                if (state == "error") {
                    Text(error, fontSize = 14.sp, color = ErrorRed)
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Update now", onClick = { start() }, modifier = Modifier.focusRequester(fr))
                    TvButton("Later", onClick = { dismissed = true })
                }
            }
        }
    }
}
