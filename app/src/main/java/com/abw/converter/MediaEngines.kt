package com.abw.converter

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object MediaEngines {

    val EXTS_IGNORAR = setOf("json", "txt", "py", "apk", "zip", "rar", "7z", "log", "ini")

    fun obterBinario(context: Context, nomeSo: String): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, nomeSo)
        return if (f.exists()) f else null
    }

    /**
     * Converte Uri do seletor do Android (OpenMultipleDocuments ou OpenDocumentTree)
     * diretamente para o File real em /storage/emulated/0 sem copiar no cache!
     */
    fun resolverUriParaArquivo(context: Context, uri: Uri): File? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path?.let { File(it) }
        }
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val partes = docId.split(":", limit = 2)
                val tipo = partes
                val idOuPath = if (partes.size > 1) partes else ""

                // 1. Armazenamento externo / cartão SD (primary:Download/video.mp4)
                if ("primary".equals(tipo, ignoreCase = true)) {
                    val base = Environment.getExternalStorageDirectory()
                    return if (idOuPath.isEmpty()) base else File(base, idOuPath)
                }
                if ("raw".equals(tipo, ignoreCase = true) && idOuPath.isNotEmpty()) {
                    return File(idOuPath)
                }
                val sd = File("/storage/$tipo")
                if (tipo.isNotEmpty() && sd.exists()) {
                    return if (idOuPath.isEmpty()) sd else File(sd, idOuPath)
                }

                // 2. MediaStore Documents (video:123, audio:456, image:789, document:101)
                val contentUri = when (tipo.lowercase()) {
                    "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "document" -> android.provider.MediaStore.Files.getContentUri("external")
                    else -> null
                }
                if (contentUri != null && idOuPath.isNotEmpty()) {
                    context.contentResolver.query(
                        contentUri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        "_id=?",
                        arrayOf(idOuPath),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val col = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                            if (col >= 0) {
                                val path = cursor.getString(col)
                                if (!path.isNullOrEmpty()) return File(path)
                            }
                        }
                    }
                }
            } else {
                // Árvore de diretório (OpenDocumentTree)
                val treeId = DocumentsContract.getTreeDocumentId(uri)
                val partes = treeId.split(":", limit = 2)
                val tipo = partes
                val relPath = if (partes.size > 1) partes else ""
                if ("primary".equals(tipo, ignoreCase = true)) {
                    val base = Environment.getExternalStorageDirectory()
                    return if (relPath.isEmpty()) base else File(base, relPath)
                }
                val sd = File("/storage/$tipo")
                if (tipo.isNotEmpty() && sd.exists()) {
                    return if (relPath.isEmpty()) sd else File(sd, relPath)
                }
            }
        } catch (_: Exception) {
        }

        // Fallback genérico via coluna _data
        try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (col >= 0) {
                        val path = cursor.getString(col)
                        if (!path.isNullOrEmpty()) return File(path)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun splitArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("""[^\s"']+|"([^"]*)"|'([^']*)'""")
        for (match in regex.findAll(args)) {
            val v = match.groups[1]?.value ?: match.groups[2]?.value ?: match.value
            if (v.isNotEmpty()) result.add(v)
        }
        return result
    }

    fun obterDuracaoSegundos(ffmpegBin: File, entrada: File): Double {
        return try {
            val proc = ProcessBuilder(ffmpegBin.absolutePath, "-i", entrada.absolutePath)
                .redirectErrorStream(true)
                .start()
            val saida = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            val m = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(saida)
            if (m != null) {
                val h = m.groupValues[1].toDouble()
                val mn = m.groupValues[2].toDouble()
                val s = m.groupValues[3].toDouble()
                h * 3600.0 + mn * 60.0 + s
            } else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    fun rodarFfmpeg(
        bin: File,
        entrada: File,
        saidaTmp: File,
        argsExtras: List<String>,
        cancelar: AtomicBoolean,
        procRef: AtomicReference<Process?>,
        onProgresso: (Int) -> Unit
    ): Pair<Int, String> {
        val duracao = obterDuracaoSegundos(bin, entrada)
        val cmd = mutableListOf(
            bin.absolutePath, "-y", "-v", "error", "-nostats",
            "-progress", "pipe:1", "-i", entrada.absolutePath
        ).apply {
            addAll(argsExtras)
            add(saidaTmp.absolutePath)
        }

        val pb = ProcessBuilder(cmd)
        val proc = pb.start()
        procRef.set(proc)

        val stderrBuf = StringBuilder()
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine { stderrBuf.appendLine(it) }
            } catch (_: Exception) {
            }
        }.apply { start() }

        var ultimoPct = -2
        try {
            proc.inputStream.bufferedReader().forEachLine { linha ->
                if (cancelar.get()) {
                    proc.destroy()
                    return@forEachLine
                }
                val l = linha.trim()
                if (l.startsWith("out_time_us=") && duracao > 0.0) {
                    val us = l.substringAfter("=").toLongOrNull() ?: return@forEachLine
                    val pct = (((us / 1_000_000.0) / duracao) * 100.0).toInt().coerceIn(0, 99)
                    if (kotlin.math.abs(pct - ultimoPct) >= 2) {
                        ultimoPct = pct
                        onProgresso(pct)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val code = proc.waitFor()
        errThread.join(1000)
        procRef.set(null)
        return Pair(code, stderrBuf.toString().trim())
    }

    fun rodarMkvmerge(
        bin: File,
        entrada: File,
        saidaTmp: File,
        cancelar: AtomicBoolean,
        procRef: AtomicReference<Process?>,
        onProgresso: (Int) -> Unit
    ): Pair<Int, String> {
        val pb = ProcessBuilder(bin.absolutePath, "-o", saidaTmp.absolutePath, entrada.absolutePath)
            .redirectErrorStream(true)
        pb.environment()["LC_ALL"] = "C.UTF-8"
        pb.environment()["LANG"] = "C.UTF-8"

        val proc = pb.start()
        procRef.set(proc)

        val outBuf = StringBuilder()
        val readerThread = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { outBuf.appendLine(it) }
            } catch (_: Exception) {
            }
        }.apply { start() }

        var pctSim = 0
        while (proc.isAlive) {
            if (cancelar.get()) {
                proc.destroy()
                break
            }
            pctSim = (pctSim + 5).coerceAtMost(95)
            onProgresso(pctSim)
            Thread.sleep(300)
        }

        val code = proc.waitFor()
        readerThread.join(1000)
        procRef.set(null)
        return Pair(code, outBuf.toString().trim())
    }
}
