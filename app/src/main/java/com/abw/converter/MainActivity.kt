package com.abw.converter

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class LogLine(val text: String, val color: Color)

private val TEXTOS_PT = mapOf(
    "subtitle" to "FFmpeg 8.1 + MKVToolNix",
    "work_folder" to "Pasta de Trabalho:",
    "folder_btn" to "Pasta",
    "mode_batch" to "Modo Lote: Toda a pasta de trabalho",
    "mode_single" to "Modo Único: {name}",
    "mode_multi" to "Selecionados: {n} arquivos",
    "pick_file" to "Modo Único: Selecionar Arquivos",
    "preset_lbl" to "Preset Ativo",
    "manage_tip" to "Gerenciar Presets",
    "start_btn" to "INICIAR CONVERSÃO",
    "remux_btn" to "REMUX MKV",
    "running_btn" to "CONVERTENDO...",
    "remuxing_btn" to "REMUXANDO...",
    "cancel_btn" to "CANCELAR",
    "canceling" to "Cancelando operação...",
    "canceled" to "[!] Operação cancelada pelo usuário.",
    "ready" to "Pronto. Salvo em conv_abw",
    "status_ready" to "Pronto para converter.",
    "wait" to "[!] Aguarde a tarefa atual terminar.",
    "no_ffmpeg" to "[-] FFmpeg embutido (libffmpeg_bin.so) não encontrado!",
    "no_mkvmerge" to "[-] MKVToolNix embutido (libmkvmerge_bin.so) não encontrado!",
    "preparing" to "Preparando motor...",
    "no_media" to "[-] Nenhum arquivo encontrado para processar.",
    "starting" to "[+] Iniciando {n} arquivo(s)",
    "converting" to "Processando {i}/{t}: {name} ({pct}%)",
    "done_item" to "Concluído {i}/{t}",
    "finished" to "[✓] Finalizado: {ok}/{t}",
    "folder_set" to "[+] Pasta definida: {name}",
    "dlg_title" to "Presets FFmpeg",
    "name_lbl" to "Nome do Preset",
    "ext_lbl" to "Extensão (ex: .mp4, .m4a)",
    "args_lbl" to "Argumentos FFmpeg",
    "save" to "Salvar",
    "restore" to "Restaurar",
    "import" to "Importar",
    "export" to "Exportar",
    "close" to "Fechar",
    "exported" to "[✓] Exportado para: {path}",
    "imported" to "[✓] {n} preset(s) importado(s).",
    "restored" to "[✓] Presets padrão restaurados.",
    "perm_title" to "Permissão de Arquivos Necessária",
    "perm_desc" to "Ative 'Acesso a todos os arquivos' para ler e salvar ao lado dos arquivos originais.\n\n(Configurações > Acesso a todos os arquivos)",
    "open_settings" to "Abrir Configurações"
)

private val TEXTOS_EN = mapOf(
    "subtitle" to "FFmpeg 8.1 + MKVToolNix",
    "work_folder" to "Working Folder:",
    "folder_btn" to "Folder",
    "mode_batch" to "Batch Mode: Entire working folder",
    "mode_single" to "Single File: {name}",
    "mode_multi" to "Selected Files: {n} file(s)",
    "pick_file" to "Select File(s)",
    "preset_lbl" to "Active Preset",
    "manage_tip" to "Manage Presets",
    "start_btn" to "START CONVERSION",
    "remux_btn" to "REMUX MKV",
    "running_btn" to "CONVERTING...",
    "remuxing_btn" to "REMUXING...",
    "cancel_btn" to "CANCEL",
    "canceling" to "Canceling operation...",
    "canceled" to "[!] Operation canceled by user.",
    "ready" to "Ready. Output saved to conv_abw",
    "status_ready" to "Ready to convert.",
    "wait" to "[!] Wait for the current task to finish.",
    "no_ffmpeg" to "[-] Embedded FFmpeg (libffmpeg_bin.so) not found!",
    "no_mkvmerge" to "[-] Embedded MKVToolNix (libmkvmerge_bin.so) not found!",
    "preparing" to "Preparing engine...",
    "no_media" to "[-] No files found to process.",
    "starting" to "[+] Starting {n} file(s)",
    "converting" to "Processing {i}/{t}: {name} ({pct}%)",
    "done_item" to "Done {i}/{t}",
    "finished" to "[✓] Finished: {ok}/{t}",
    "folder_set" to "[+] Folder set: {name}",
    "dlg_title" to "FFmpeg Presets",
    "name_lbl" to "Preset Name",
    "ext_lbl" to "Extension (e.g. .mp4, .m4a)",
    "args_lbl" to "FFmpeg Arguments",
    "save" to "Save",
    "restore" to "Restore",
    "import" to "Import",
    "export" to "Export",
    "close" to "Close",
    "exported" to "[✓] Exported to: {path}",
    "imported" to "[✓] Imported {n} preset(s).",
    "restored" to "[✓] Default presets restored.",
    "perm_title" to "Storage Permission Required",
    "perm_desc" to "Allow 'All files access' to read and save next to original files.\n\n(Settings > All files access)",
    "open_settings" to "Open Settings"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AbwConverterScreen(
                    onKeepScreenOn = { keepOn ->
                        runOnUiThread {
                            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbwConverterScreen(onKeepScreenOn: (Boolean) -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(PresetsManager.detectarIdioma(context)) }
    fun tr(key: String, vararg args: Pair<String, Any>): String {
        var txt = (if (lang == "pt") TEXTOS_PT else TEXTOS_EN)[key] ?: key
        for ((k, v) in args) txt = txt.replace("{$k}", v.toString())
        return txt
    }

    val downloadDir = remember {
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (d != null && d.exists()) d else Environment.getExternalStorageDirectory()
    }

    var pastaTrabalho by remember { mutableStateOf(downloadDir) }
    var arquivosSelecionados by remember { mutableStateOf<List<File>>(emptyList()) }
    var presets by remember { mutableStateOf(PresetsManager.carregarPresets(context)) }
    var presetIdx by remember { mutableIntStateOf(0) }
    var dropdownAberto by remember { mutableStateOf(false) }

    var ocupado by remember { mutableStateOf(false) }
    var modoAtual by remember { mutableStateOf("ffmpeg") }
    var progresso by remember { mutableFloatStateOf(0f) }
    var statusTexto by remember { mutableStateOf(tr("status_ready")) }

    val logs = remember { mutableStateListOf<LogLine>() }
    val logListState = rememberLazyListState()
    val cancelarFlag = remember { AtomicBoolean(false) }
    val procAtualRef = remember { AtomicReference<Process?>(null) }

    var mostrarDlgPerm by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
    }
    var mostrarDlgPresets by remember { mutableStateOf(false) }

    fun addLog(msg: String, color: Color = Color(0xFFE0E0E0)) {
        logs.add(LogLine(msg, color))
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val dir = MediaEngines.resolverUriParaArquivo(context, uri)
            if (dir != null && dir.exists()) {
                pastaTrabalho = dir
                arquivosSelecionados = emptyList()
                addLog(tr("folder_set", "name" to dir.name), Color(0xFFCE93D8))
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val reais = uris.mapNotNull { MediaEngines.resolverUriParaArquivo(context, it) }.filter { it.exists() }
            if (reais.isNotEmpty()) {
                arquivosSelecionados = reais
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val txt = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val n = PresetsManager.importarJson(context, txt)
                if (n > 0) {
                    presets = PresetsManager.carregarPresets(context)
                    addLog(tr("imported", "n" to n), Color(0xFF81C784))
                }
            } catch (ex: Exception) {
                addLog("[-] ${ex.message}", Color(0xFFE57373))
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val dir = MediaEngines.resolverUriParaArquivo(context, uri)
            if (dir != null && dir.exists()) {
                try {
                    val dest = File(dir, "abw_presets_exportados.json")
                    dest.writeText(PresetsManager.exportarJsonString(presets), Charsets.UTF_8)
                    mostrarDlgPresets = false
                    addLog(tr("exported", "path" to dest.absolutePath), Color(0xFF81C784))
                } catch (ex: Exception) {
                    addLog("[-] ${ex.message}", Color(0xFFE57373))
                }
            }
        }
    }

    fun iniciarLote(modo: String) {
        if (ocupado) {
            addLog(tr("wait"), Color(0xFFFFD54F))
            return
        }
        val binSo = if (modo == "mkvmerge") "libmkvmerge_bin.so" else "libffmpeg_bin.so"
        val binFile = MediaEngines.obterBinario(context, binSo)
        if (binFile == null) {
            addLog(tr(if (modo == "mkvmerge") "no_mkvmerge" else "no_ffmpeg"), Color(0xFFE57373))
            return
        }

        val presetAtivo = presets.getOrElse(presetIdx) { presets.first() }
        val extSaida = if (modo == "mkvmerge") ".mkv" else {
            val e = presetAtivo.ext.trim()
            if (e.startsWith(".")) e else ".$e"
        }
        val argsExtras = if (modo == "mkvmerge") emptyList() else MediaEngines.splitArgs(presetAtivo.args)
        val nomeModo = if (modo == "mkvmerge") "MKVToolNix (Remux MKV)" else "Preset: ${presetAtivo.nome}"

        cancelarFlag.set(false)
        procAtualRef.set(null)
        ocupado = true
        modoAtual = modo
        onKeepScreenOn(true)
        logs.clear()
        progresso = 0f
        statusTexto = tr("preparing")

        thread(start = true) {
            val pastaTmp = File(context.cacheDir, "abw_tmp").apply { mkdirs() }
            try {
                val listaAlvo: List<File> = if (arquivosSelecionados.isNotEmpty()) {
                    arquivosSelecionados.toList()
                } else {
                    (pastaTrabalho.listFiles() ?: emptyArray())
                        .filter { it.isFile && it.extension.lowercase() !in MediaEngines.EXTS_IGNORAR }
                        .sortedBy { it.name.lowercase() }
                }

                val total = listaAlvo.size
                if (total == 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        addLog("[-] ${tr("perm_title")}", Color(0xFFE57373))
                        mostrarDlgPerm = true
                    } else {
                        addLog(tr("no_media"), Color(0xFFE57373))
                    }
                    return@thread
                }

                addLog(tr("starting", "n" to total), Color(0xFFCE93D8))
                addLog("    $nomeModo", Color(0xFFE0E0E0))

                var okCount = 0
                for ((idx, arq) in listaAlvo.withIndex()) {
                    if (cancelarFlag.get()) break
                    val i = idx + 1

                    val pastaPai = arq.parentFile ?: pastaTrabalho
                    val pastaSaida = File(pastaPai, "conv_abw").apply { mkdirs() }

                    val nomeCurto = if (arq.name.length <= 24) arq.name else arq.name.take(21) + "..."
                    progresso = idx.toFloat() / total
                    statusTexto = tr("converting", "i" to i, "t" to total, "name" to nomeCurto, "pct" to 0)

                    val destinoFinal = File(pastaSaida, "${arq.nameWithoutExtension}$extSaida")
                    val saidaTmp = File(pastaTmp, "out_$i$extSaida").apply { if (exists()) delete() }

                    val onItemProg: (Int) -> Unit = { pctItem ->
                        progresso = (idx + pctItem / 100f) / total
                        statusTexto = tr("converting", "i" to i, "t" to total, "name" to nomeCurto, "pct" to pctItem)
                    }

                    val (retCode, errOut) = if (modo == "mkvmerge") {
                        MediaEngines.rodarMkvmerge(binFile, arq, saidaTmp, cancelarFlag, procAtualRef, onItemProg)
                    } else {
                        MediaEngines.rodarFfmpeg(binFile, arq, saidaTmp, argsExtras, cancelarFlag, procAtualRef, onItemProg)
                    }

                    if (cancelarFlag.get()) {
                        saidaTmp.delete()
                        break
                    }

                    val sucesso = if (modo == "mkvmerge") (retCode == 0 || retCode == 1) else (retCode == 0)
                    if (sucesso && saidaTmp.exists() && saidaTmp.length() > 0L) {
                        if (destinoFinal.exists()) destinoFinal.delete()
                        if (!saidaTmp.renameTo(destinoFinal)) {
                            saidaTmp.copyTo(destinoFinal, overwrite = true)
                            saidaTmp.delete()
                        }
                        okCount++
                    } else {
                        saidaTmp.delete()
                        addLog("  [!] #$i/$total (code=$retCode)", Color(0xFFE57373))
                        if (errOut.isNotEmpty()) {
                            val ultima = errOut.lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
                            if (ultima.isNotEmpty()) addLog("      ${ultima.take(120)}", Color(0xFFFFD54F))
                        }
                    }

                    progresso = i.toFloat() / total
                    statusTexto = tr("done_item", "i" to i, "t" to total)
                }

                if (cancelarFlag.get()) {
                    addLog(tr("canceled"), Color(0xFFFFD54F))
                } else {
                    addLog(tr("finished", "ok" to okCount, "t" to total), Color(0xFF81C784))
                }
            } catch (ex: Exception) {
                addLog("[-] ${ex.message}", Color(0xFFE57373))
            } finally {
                pastaTmp.listFiles()?.forEach { it.delete() }
                procAtualRef.set(null)
                ocupado = false
                onKeepScreenOn(false)
                progresso = if (cancelarFlag.get()) 0f else 1f
                statusTexto = tr("status_ready")
            }
        }
    }

    val alvoTexto = when {
        arquivosSelecionados.size == 1 -> tr("mode_single", "name" to arquivosSelecionados.first().name)
        arquivosSelecionados.size > 1 -> tr("mode_multi", "n" to arquivosSelecionados.size)
        else -> tr("mode_batch")
    }
    val alvoCor = if (arquivosSelecionados.isNotEmpty()) Color(0xFFFFD54F) else Color(0xFF81C784)

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121316)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ABW Converter", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCE93D8))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("subtitle"), fontSize = 12.sp, color = Color(0xFF78909C))
                    TextButton(
                        onClick = {
                            lang = if (lang == "pt") "en" else "pt"
                            PresetsManager.salvarIdioma(context, lang)
                            statusTexto = tr("status_ready")
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(lang.uppercase(), color = Color(0xFFCE93D8), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF191B20), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF2C303A), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = Color(0xFFFFD54F))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("work_folder"), fontSize = 11.sp, color = Color(0xFF90A4AE))
                    Text(
                        pastaTrabalho.absolutePath,
                        fontSize = 12.sp,
                        color = Color(0xFFA5D6A7),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { if (!ocupado) folderPicker.launch(null) },
                    enabled = !ocupado,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3240)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("folder_btn"), color = Color.White, fontSize = 12.sp)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF191B20), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF2C303A), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        alvoTexto,
                        fontSize = 12.sp,
                        color = alvoCor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { if (!ocupado) arquivosSelecionados = emptyList() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, tint = Color(0xFF90A4AE), modifier = Modifier.size(18.dp))
                    }
                }
                Button(
                    onClick = { if (!ocupado) filePicker.launch(arrayOf("*/*")) },
                    enabled = !ocupado,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232834)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("pick_file"), color = Color(0xFFE0E0E0), fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF191B20), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF2C303A), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val atual = presets.getOrElse(presetIdx) { presets.first() }
                ExposedDropdownMenuBox(
                    expanded = dropdownAberto && !ocupado,
                    onExpandedChange = { if (!ocupado) dropdownAberto = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = "${atual.nome} (${atual.ext})",
                        onValueChange = {},
                        readOnly = true,
                        enabled = !ocupado,
                        label = { Text(tr("preset_lbl"), fontSize = 12.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownAberto) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownAberto && !ocupado,
                        onDismissRequest = { dropdownAberto = false }
                    ) {
                        presets.forEachIndexed { idx, p ->
                            DropdownMenuItem(
                                text = { Text("${p.nome} (${p.ext})", fontSize = 13.sp) },
                                onClick = {
                                    presetIdx = idx
                                    dropdownAberto = false
                                }
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { if (!ocupado) mostrarDlgPresets = true },
                    enabled = !ocupado
                ) {
                    Icon(Icons.Default.Tune, contentDescription = tr("manage_tip"), tint = Color(0xFFCE93D8))
                }
            }

            Button(
                onClick = { iniciarLote("ffmpeg") },
                enabled = !ocupado,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (ocupado && modoAtual == "ffmpeg") tr("running_btn") else tr("start_btn"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = { iniciarLote("mkvmerge") },
                enabled = !ocupado,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.MovieFilter, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (ocupado && modoAtual == "mkvmerge") tr("remuxing_btn") else tr("remux_btn"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            if (ocupado) {
                Button(
                    onClick = {
                        if (!cancelarFlag.get()) {
                            cancelarFlag.set(true)
                            statusTexto = tr("canceling")
                            try {
                                procAtualRef.get()?.destroy()
                            } catch (_: Exception) {
                            }
                        }
                    },
                    enabled = !cancelarFlag.get(),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("cancel_btn"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusTexto, fontSize = 12.sp, color = Color(0xFFB0BEC5))
                LinearProgressIndicator(
                    progress = { progresso.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Color(0xFFAB47BC),
                    trackColor = Color(0xFF262930)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0D0E11), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF262930), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                LazyColumn(state = logListState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(logs) { item ->
                        Text(item.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = item.color)
                    }
                }
            }
        }
    }

    if (mostrarDlgPerm) {
        AlertDialog(
            onDismissRequest = { mostrarDlgPerm = false },
            title = { Text(tr("perm_title")) },
            text = { Text(tr("perm_desc"), fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    mostrarDlgPerm = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                }) {
                    Text(tr("open_settings"))
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDlgPerm = false }) { Text(tr("close")) }
            }
        )
    }

    if (mostrarDlgPresets) {
        var editIdx by remember { mutableStateOf<Int?>(null) }
        var nomeInp by remember { mutableStateOf("") }
        var extInp by remember { mutableStateOf("") }
        var argsInp by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { mostrarDlgPresets = false },
            title = { Text(tr("dlg_title"), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(modifier = Modifier.height(145.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets.size) { i ->
                            val p = presets[i]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E222A), RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${p.nome} (${p.ext})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(p.args, fontSize = 10.sp, color = Color(0xFF90A4AE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    editIdx = i
                                    nomeInp = p.nome
                                    extInp = p.ext
                                    argsInp = p.args
                                }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    if (presets.size > 1) {
                                        val nova = presets.toMutableList().apply { removeAt(i) }
                                        PresetsManager.salvarPresets(context, nova)
                                        presets = nova
                                        if (presetIdx >= presets.size) presetIdx = 0
                                    }
                                }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF2C303A))
                    OutlinedTextField(value = nomeInp, onValueChange = { nomeInp = it }, label = { Text(tr("name_lbl"), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = extInp, onValueChange = { extInp = it }, label = { Text(tr("ext_lbl"), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = argsInp, onValueChange = { argsInp = it }, label = { Text(tr("args_lbl"), fontSize = 12.sp) }, maxLines = 3, modifier = Modifier.fillMaxWidth())

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val n = nomeInp.trim()
                                var e = extInp.trim()
                                val a = argsInp.trim()
                                if (n.isNotEmpty() && e.isNotEmpty() && a.isNotEmpty()) {
                                    if (!e.startsWith(".")) e = ".$e"
                                    val nova = presets.toMutableList()
                                    if (editIdx != null && editIdx!! in nova.indices) {
                                        nova[editIdx!!] = PresetItem(n, e, a)
                                        editIdx = null
                                    } else {
                                        nova.add(PresetItem(n, e, a))
                                    }
                                    PresetsManager.salvarPresets(context, nova)
                                    presets = nova
                                    nomeInp = ""; extInp = ""; argsInp = ""
                                }
                            }
                        ) { Text(tr("save"), fontSize = 12.sp) }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val existentes = presets.map { it.nome }.toSet()
                                val nova = presets.toMutableList()
                                PresetsManager.PRESETS_PADRAO.forEach { if (it.nome !in existentes) nova.add(it) }
                                PresetsManager.salvarPresets(context, nova)
                                presets = nova
                                addLog(tr("restored"), Color(0xFF81C784))
                            }
                        ) { Text(tr("restore"), fontSize = 12.sp) }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { importPicker.launch(arrayOf("application/json", "*/*")) }
                        ) { Text(tr("import"), fontSize = 12.sp) }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { exportPicker.launch(null) }
                        ) { Text(tr("export"), fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarDlgPresets = false }) { Text(tr("close")) }
            }
        )
    }
}
