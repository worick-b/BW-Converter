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
    fun resolverUriParaArquivo(uri: Uri): File? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path?.let { File(it) }
        }
        try {
            val docId = when {
                DocumentsContract.isDocumentUri(null, uri) -> DocumentsContract.getDocumentId(uri)
                else -> DocumentsContract.getTreeDocumentId(uri)
            }
            val partes = docId.split(":", limit = 2)
            val tipo = partes[0]
            val relPath = if (partes.size > 1) partes[1] else ""
            if ("primary".equals(tipo, ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory()
                return if (relPath.isEmpty()) base else File(base, relPath)
            } else if (tipo.isNotEmpty()) {
                val sd = File("/storage/$tipo")
                if (sd.exists()) {
                    return if (relPath.isEmpty()) sd else File(sd, relPath)
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
