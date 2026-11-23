package com.bw.converter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        init { System.loadLibrary("bw_ui") }
        private const val REQ_PICK_FILE = 1001
        private const val REQ_PICK_FOLDER = 1002
        private const val REQ_IMPORT_JSON = 1003
        private const val REQ_EXPORT_JSON = 1004
    }

    private external fun nativeInit(density: Float, isPtBr: Boolean)
    private external fun nativeResize(w: Int, h: Int, topInset: Int, bottomInset: Int)
    private external fun nativeRender(): Boolean
    private external fun nativeTouch(action: Int, x: Int, y: Int)
    private external fun nativeTextCommit(text: String)
    private external fun nativeKeySpecial(code: Int)

    private external fun nativeLoadPresetsJson(jsonStr: String, showToast: Boolean)
    private external fun nativeNotifyToast(msg: String, isError: Boolean)
    private external fun nativeSetSelectedPath(path: String)
    private external fun nativeUpdateProgress(prog: Float, status: String, logAppend: String)
    private external fun nativeOnConvertFinished(success: Boolean, totalConverted: Int)

    private lateinit var glView: ShawarmaGLView
    private var topInset = 0
    private var bottomInset = 0

    private val cancelFlag = AtomicBoolean(false)
    @Volatile private var currentProcess: Process? = null
    private var pendingExportJson: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = ShawarmaGLView(this)
        setContentView(glView)

        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            topInset = insets.systemWindowInsetTop
            bottomInset = insets.systemWindowInsetBottom
            if (glView.width > 0 && glView.height > 0) {
                glView.queueEvent { nativeResize(glView.width, glView.height, topInset, bottomInset) }
            }
            v.onApplyWindowInsets(insets)
        }

        ensureAllFilesPermission()
    }

    private fun ensureAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        glView.requestRender()
    }

    override fun onPause() {
        glView.stopBackdropTick()
        glView.onPause()
        super.onPause()
    }

    fun requestRenderFromNative() {
        glView.requestRender()
    }

    fun setKeyboardVisible(show: Boolean) {
        runOnUiThread {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (show) {
                glView.requestFocus()
                imm.showSoftInput(glView, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm.hideSoftInputFromWindow(glView.windowToken, 0)
            }
        }
    }

    fun pickMediaFromNative(isFolder: Boolean) {
        runOnUiThread {
            ensureAllFilesPermission()
            if (isFolder) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_PICK_FILE)
            }
        }
    }

    fun savePresetsJsonFromNative(jsonStr: String) {
        try {
            File(filesDir, "presets.json").writeText(jsonStr, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun importPresetsFromNative() {
        runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_JSON)
        }
    }

    fun exportPresetsFromNative(jsonStr: String) {
        pendingExportJson = jsonStr
        runOnUiThread {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "betterway_presets.json")
            }
            startActivityForResult(intent, REQ_EXPORT_JSON)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        if (requestCode == REQ_PICK_FILE || requestCode == REQ_PICK_FOLDER) {
            val resolved = resolveRealPath(uri) ?: return
            glView.queueEvent { nativeSetSelectedPath(resolved) }
        } else if (requestCode == REQ_IMPORT_JSON) {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
                glView.queueEvent { nativeLoadPresetsJson(content, true) }
            } catch (_: Exception) {
                glView.queueEvent { nativeNotifyToast("Falha ao ler JSON.", true) }
            }
        } else if (requestCode == REQ_EXPORT_JSON) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingExportJson.toByteArray(Charsets.UTF_8))
                }
                glView.queueEvent { nativeNotifyToast("Presets exportados!", false) }
            } catch (_: Exception) {
                glView.queueEvent { nativeNotifyToast("Falha ao exportar JSON.", true) }
            }
        }
    }

    private fun resolveRealPath(uri: Uri): String? {
        val docId = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else if (DocumentsContract.isDocumentUri(this, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            return uri.path
        }

        val parts = docId.split(":")
        val type = parts.getOrNull(0) ?: return null
        val relPath = parts.getOrNull(1) ?: ""
        return if (type.equals("primary", ignoreCase = true)) {
            "${Environment.getExternalStorageDirectory().absolutePath}/$relPath".trimEnd('/')
        } else {
            "/storage/$type/$relPath".trimEnd('/')
        }
    }

    fun startConvertFromNative(modoLote: Int, motorIdx: Int, path: String, ext: String, argsStr: String) {
        cancelFlag.set(false)
        thread(start = true) {
            val files = collectMediaFiles(File(path), modoLote == 1)
            if (files.isEmpty()) {
                glView.queueEvent { nativeOnConvertFinished(false, 0) }
                return@thread
            }

            val nativeDir = applicationInfo.nativeLibraryDir
            val binPath = if (motorIdx == 0) "$nativeDir/libffmpeg_bin.so" else "$nativeDir/libmkvmerge_bin.so"
            var converted = 0
            val totalFiles = files.size.toFloat()

            val rxDuration = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
            val rxTime = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
            val rxMkvProg = Regex("""Progress:\s*(\d+)%""")

            fun parseSeconds(m: MatchResult): Double {
                val h = m.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val min = m.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val sec = m.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                return h * 3600.0 + min * 60.0 + sec
            }

            for ((index, inputFile) in files.withIndex()) {
                if (cancelFlag.get()) break

                val outDir = File(inputFile.parentFile, "conv_bw").apply { mkdirs() }
                val outExt = if (motorIdx == 0) ext else ".mkv"
                val outFile = File(outDir, "${inputFile.nameWithoutExtension}$outExt")

                val cmd = mutableListOf(binPath)
                if (motorIdx == 0) {
                    cmd.addAll(listOf("-y", "-i", inputFile.absolutePath))
                    val parsedArgs = argsStr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    cmd.addAll(parsedArgs)
                    cmd.add(outFile.absolutePath)
                } else {
                    cmd.addAll(listOf("-o", outFile.absolutePath, inputFile.absolutePath))
                }

                val statusMsg = "[${index + 1}/${files.size}] ${inputFile.name}"
                val startProg = index.toFloat() / totalFiles
                glView.queueEvent { nativeUpdateProgress(startProg, statusMsg, "$statusMsg ... ") }

                var durationSec = 0.0
                var lastSentPct = -1

                try {
                    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                    val proc = pb.start()
                    currentProcess = proc

                    val reader = InputStreamReader(proc.inputStream, Charsets.UTF_8)
                    val sb = StringBuilder()
                    var chInt: Int
                    while (reader.read().also { chInt = it } != -1) {
                        if (cancelFlag.get()) {
                            proc.destroyForcibly()
                            break
                        }
                        val c = chInt.toChar()
                        if (c == '\n' || c == '\r') {
                            if (sb.isNotEmpty()) {
                                val line = sb.toString()
                                sb.setLength(0)

                                var fileRatio = -1f
                                if (motorIdx == 0) {
                                    if (durationSec <= 0.0) {
                                        rxDuration.find(line)?.let { durationSec = parseSeconds(it) }
                                    }
                                    if (durationSec > 0.0) {
                                        rxTime.find(line)?.let { m ->
                                            val curSec = parseSeconds(m)
                                            fileRatio = (curSec / durationSec).toFloat().coerceIn(0f, 1f)
                                        }
                                    }
                                } else {
                                    rxMkvProg.find(line)?.let { m ->
                                        val pInt = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                                        fileRatio = (pInt / 100f).coerceIn(0f, 1f)
                                    }
                                }

                                if (fileRatio >= 0f) {
                                    val globalProg = ((index.toFloat() + fileRatio) / totalFiles).coerceIn(0f, 1f)
                                    val pctInt = (globalProg * 100f).toInt()
                                    if (pctInt != lastSentPct) {
                                        lastSentPct = pctInt
                                        glView.queueEvent { nativeUpdateProgress(globalProg, statusMsg, "") }
                                    }
                                }
                            }
                        } else {
                            sb.append(c)
                        }
                    }

                    val code = proc.waitFor()
                    currentProcess = null
                    if (code == 0 && !cancelFlag.get()) {
                        converted++
                        val doneProg = (index + 1).toFloat() / totalFiles
                        glView.queueEvent { nativeUpdateProgress(doneProg, statusMsg, "OK\n") }
                    } else if (!cancelFlag.get()) {
                        glView.queueEvent { nativeUpdateProgress(startProg, statusMsg, "ERRO\n") }
                    }
                } catch (_: Exception) {
                    glView.queueEvent { nativeUpdateProgress(startProg, statusMsg, "ERRO\n") }
                }
            }

            val ok = !cancelFlag.get() && converted > 0
            glView.queueEvent { nativeOnConvertFinished(ok, converted) }
        }
    }

    fun cancelConvertFromNative() {
        cancelFlag.set(true)
        try { currentProcess?.destroyForcibly() } catch (_: Exception) {}
    }

    private fun collectMediaFiles(target: File, isBatch: Boolean): List<File> {
        val validExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "mp3", "aac", "ogg", "opus", "flac", "wav", "m4a", "wma")
        if (!isBatch) {
            return if (target.isFile) listOf(target) else emptyList()
        }
        if (!target.isDirectory) return emptyList()
        return target.listFiles()
            ?.filter { it.isFile && validExts.contains(it.extension.lowercase(Locale.ROOT)) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()
    }

    inner class ShawarmaGLView(ctx: Context) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
        private val backdropTick = Runnable { requestRender() }

        init {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 8)
            setRenderer(this)
            renderMode = RENDERMODE_WHEN_DIRTY
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun stopBackdropTick() {
            removeCallbacks(backdropTick)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val isPt = Locale.getDefault().language.startsWith("pt", ignoreCase = true)
            nativeInit(resources.displayMetrics.density, isPt)

            val savedFile = File(filesDir, "presets.json")
            if (savedFile.exists()) {
                try {
                    val json = savedFile.readText(Charsets.UTF_8)
                    nativeLoadPresetsJson(json, false)
                } catch (_: Exception) {}
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            nativeResize(width, height, topInset, bottomInset)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (nativeRender()) {
                removeCallbacks(backdropTick)
                postDelayed(backdropTick, 100L) // ~10 FPS para animação do fundo
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val act = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1
                MotionEvent.ACTION_MOVE -> 2
                else -> return true
            }
            val x = event.x.toInt()
            val y = event.y.toInt()
            queueEvent { nativeTouch(act, x, y) }
            return true
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val str = text?.toString() ?: return true
                    queueEvent { nativeTextCommit(str) }
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0) queueEvent { nativeKeySpecial(1) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.keyCode == KeyEvent.KEYCODE_DEL) queueEvent { nativeKeySpecial(1) }
                        else if (event.keyCode == KeyEvent.KEYCODE_ENTER) queueEvent { nativeKeySpecial(2) }
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }
}
