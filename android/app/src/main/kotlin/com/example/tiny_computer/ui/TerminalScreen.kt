package com.example.tiny_computer.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.TermModifiers
import com.example.tiny_computer.core.TermSessions
import com.example.tiny_computer.core.TermViewClient
import com.example.tiny_computer.core.TinyEnv
import com.termux.view.TerminalView
import org.json.JSONArray
import org.json.JSONObject

private data class Key(val label: String, val seq: String)

private val KEYS = listOf(
    Key("Esc", "\u001b"), Key("Tab", "\t"),
    Key("↑", "\u001b[A"), Key("↓", "\u001b[B"), Key("←", "\u001b[D"), Key("→", "\u001b[C"),
    Key("Del", "\u001b[3~"), Key("PgUp", "\u001b[5~"), Key("PgDn", "\u001b[6~"),
    Key("Home", "\u001b[H"), Key("End", "\u001b[F"),
    Key("F1", "\u001bOP"), Key("F2", "\u001bOQ"), Key("F3", "\u001bOR"), Key("F4", "\u001bOS"),
    Key("F5", "\u001b[15~"), Key("F6", "\u001b[17~"), Key("F7", "\u001b[18~"), Key("F8", "\u001b[19~"),
    Key("F9", "\u001b[20~"), Key("F10", "\u001b[21~"), Key("F11", "\u001b[23~"), Key("F12", "\u001b[24~"),
)

/** Terminal (logika sama: shell /system/bin/sh menjalankan proot) + keypad + perintah cepat. */
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val term = remember { TermSessions.forCurrent(ctx) }
    var termView by remember { mutableStateOf<TerminalView?>(null) }
    var ctrl by remember { mutableStateOf(TermModifiers.ctrl) }
    var alt by remember { mutableStateOf(TermModifiers.alt) }
    var showCommands by remember { mutableStateOf(false) }
    var fontPx by remember { mutableStateOf((14f * Cfg.termFontScale.toFloat() * ctx.resources.displayMetrics.density).toInt()) }

    Column(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                TerminalView(c, null).also { v ->
                    val scroller = TerminalScroller(v)
                    v.setTerminalViewClient(TermViewClient(
                        onTap = {
                            if (!Cfg.isTerminalWriteEnabled) return@TermViewClient
                            v.requestFocus()
                            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        },
                        onScaleChange = { s ->
                            val next = (fontPx * s).toInt().coerceIn(16, 120)
                            if (next != fontPx) { fontPx = next; scroller.setTextSize(next); Cfg.termFontScale = next / (14f * c.resources.displayMetrics.density).toDouble() }
                        },
                        onModifiersChanged = { ctrl = TermModifiers.ctrl; alt = TermModifiers.alt },
                    ))
                    scroller.setTextSize(fontPx)
                    v.isFocusable = Cfg.isTerminalWriteEnabled
                    v.isFocusableInTouchMode = Cfg.isTerminalWriteEnabled
                    term.view = v
                    term.scroller = scroller
                    v.attachSession(term.session)
                    termView = v
                }
            },
            // JANGAN panggil onScreenUpdated() di sini: dipanggil tiap rekomposisi dan menyeret
            // tampilan kembali ke bawah sehingga terminal tidak bisa digulir. Pembaruan layar sudah
            // datang dari TermSession.onTextChanged.
            update = { },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (Cfg.isTerminalCommandsEnabled) {
                KeyChip("Ctrl", ctrl) { TermModifiers.ctrl = !TermModifiers.ctrl; ctrl = TermModifiers.ctrl }
                KeyChip("Alt", alt) { TermModifiers.alt = !TermModifiers.alt; alt = TermModifiers.alt }
                KEYS.forEach { k -> KeyChip(k.label) { term.write(k.seq); termView?.requestFocus() } }
            }
            KeyChip(stringResource(R.string.tc_ui_control)) { showCommands = true }
            KeyChip("Ctrl+C") { term.write("\u0003") }
        }
    }
    if (showCommands) CommandsDialog(onDismiss = { showCommands = false }, onRun = { term.writeLine(it); showCommands = false })
}

/** Perintah cepat per kontainer (name + command), dengan tambah/hapus/reset seperti FastCommands di Dart. */
@Composable
private fun CommandsDialog(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    val idx = TinyEnv.currentContainer
    var list by remember { mutableStateOf(Containers.commands(idx, TinyEnv.defaultCommands)) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }

    fun save(arr: JSONArray) { list = arr; Containers.put(idx, "commands", arr) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tc_ui_add_shortcut_command)) },
        text = {
            if (adding) {
                Column {
                    TextRow(stringResource(R.string.tc_ui_command_name), name) { name = it }
                    TextRow(stringResource(R.string.tc_ui_command_content), cmd, multiline = true) { cmd = it }
                }
            } else {
                LazyColumn {
                    items((0 until list.length()).toList()) { i ->
                        val o = list.getJSONObject(i)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { onRun(o.getString("command")) }, modifier = Modifier.weight(1f)) { Text(o.getString("name")) }
                            TextButton(onClick = {
                                val n = JSONArray(); for (j in 0 until list.length()) if (j != i) n.put(list.getJSONObject(j)); save(n)
                            }) { Text(stringResource(R.string.tc_ui_delete_item)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (adding) TextButton(onClick = {
                if (name.isNotBlank() && cmd.isNotBlank()) {
                    val n = JSONArray(list.toString()); n.put(JSONObject().put("name", name).put("command", cmd)); save(n)
                }
                adding = false; name = ""; cmd = ""
            }) { Text(stringResource(R.string.tc_ui_add)) }
            else TextButton(onClick = { adding = true }) { Text(stringResource(R.string.tc_ui_add)) }
        },
        dismissButton = {
            Row {
                if (!adding) TextButton(onClick = { save(TinyEnv.defaultCommands) }) { Text(stringResource(R.string.tc_ui_reset_command)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tc_ui_cancel)) }
            }
        },
    )
}
