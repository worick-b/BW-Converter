package com.abw.converter

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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        init { System.loadLibrary("abw_ui") }
        private const val REQ_PICK_FILE = 1001
        private const val REQ_PICK_FOLDER = 1002
    }

    private external fun nativeInit(density: Float, isPtBr: Boolean)
    private external fun nativeResize(w: Int, h: Int, topInset: Int, bottomInset: Int)
    private external fun nativeRender(): Boolean
    private external fun nativeTouch(action: Int, x: Int, y: Int)
    private external fun nativeTextCommit(text: String)
    private external fun nativeKeySpecial(code: Int)

    private external fun nativeSetSelectedPath(path: String)
    private external fun nativeUpdateProgress(prog: Float, status: String, logAppend: String)
    private external fun nativeOnConvertFinished(success: Boolean, totalConverted: Int)

    private lateinit var glView: ShawarmaGLView
    private var topInset = 0
    private var bottomInset = 0

    private val cancelFlag = AtomicBoolean(false)
    @Volatile private var currentProcess: Process? = null

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
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQ_PICK_FOLDER)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_PICK_FILE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        val resolved = resolveRealPath(uri) ?: return
        glView.queueEvent { nativeSetSelectedPath(resolved) }
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

            for ((index, inputFile) in files.withIndex()) {
                if (cancelFlag.get()) break

                val outDir = File(inputFile.parentFile, "conv_abw").apply { mkdirs() }
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

                val baseProg = index.toFloat() / files.size.toFloat()
                val statusMsg = "[${index + 1}/${files.size}] ${inputFile.name}"
                glView.queueEvent { nativeUpdateProgress(baseProg, statusMsg, "\n> ${cmd.joinToString(" ")}\n") }

                try {
                    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                    val proc = pb.start()
                    currentProcess = proc

                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (cancelFlag.get()) {
                                proc.destroyForcibly()
                                break
                            }
                            val logLine = "$line\n"
                            glView.queueEvent { nativeUpdateProgress(baseProg, statusMsg, logLine) }
                        }
                    }

                    val code = proc.waitFor()
                    currentProcess = null
                    if (code == 0 && !cancelFlag.get()) converted++
                } catch (e: Exception) {
                    val err = "Erro: ${e.message}\n"
                    glView.queueEvent { nativeUpdateProgress(baseProg, statusMsg, err) }
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

    fun savePresetsFromNative() {
        // Gancho opcional para persistir JSON em filesDir
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
        init {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 8)
            setRenderer(this)
            renderMode = RENDERMODE_WHEN_DIRTY
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val isPt = Locale.getDefault().language.startsWith("pt", ignoreCase = true)
            nativeInit(resources.displayMetrics.density, isPt)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            nativeResize(width, height, topInset, bottomInset)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (nativeRender()) requestRender()
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
