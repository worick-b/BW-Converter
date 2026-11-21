package com.abw.converter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class PresetItem(
    val nome: String,
    val ext: String,
    val args: String
)

object PresetsManager {

    val PRESETS_PADRAO = listOf(
        PresetItem("Audio MP3 (192k)", ".mp3", "-vn -c:a libmp3lame -b:a 192k -ar 44100"),
        PresetItem("Audio HE-AAC v2 (39k)", ".aac", "-vn -c:a libfdk_aac -profile:a aac_he_v2 -ac 2 -b:a 39k -ar 44100"),
        PresetItem("Video MP4 H.264 (CRF 23)", ".mp4", "-c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p -c:a libfdk_aac -b:a 128k")
    )

    private fun presetsFile(context: Context): File = File(context.filesDir, "presets.json")
    private fun configFile(context: Context): File = File(context.filesDir, "config.json")

    fun carregarPresets(context: Context): MutableList<PresetItem> {
        val file = presetsFile(context)
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                val lista = mutableListOf<PresetItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val nome = obj.optString("nome").trim()
                    val ext = obj.optString("ext").trim()
                    val args = obj.optString("args").trim()
                    if (nome.isNotEmpty() && ext.isNotEmpty() && args.isNotEmpty()) {
                        lista.add(PresetItem(nome, ext, args))
                    }
                }
                if (lista.isNotEmpty()) return lista
            } catch (_: Exception) {
            }
        }
        val iniciais = PRESETS_PADRAO.toMutableList()
        salvarPresets(context, iniciais)
        return iniciais
    }

    fun salvarPresets(context: Context, lista: List<PresetItem>) {
        try {
            val arr = JSONArray()
            lista.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("nome", p.nome)
                        put("ext", p.ext)
                        put("args", p.args)
                    }
                )
            }
            presetsFile(context).writeText(arr.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun importarJson(context: Context, jsonTexto: String): Int {
        val atuais = carregarPresets(context)
        val nomesExistentes = atuais.map { it.nome.lowercase() }.toMutableSet()
        val arr = JSONArray(jsonTexto)
        var adicionados = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val nome = obj.optString("nome").trim()
            var ext = obj.optString("ext").trim()
            val args = obj.optString("args").trim()
            if (nome.isNotEmpty() && ext.isNotEmpty() && args.isNotEmpty()) {
                if (!ext.startsWith(".")) ext = ".$ext"
                if (nome.lowercase() !in nomesExistentes) {
                    atuais.add(PresetItem(nome, ext, args))
                    nomesExistentes.add(nome.lowercase())
                    adicionados++
                }
            }
        }
        if (adicionados > 0) {
            salvarPresets(context, atuais)
        }
        return adicionados
    }

    fun exportarJsonString(lista: List<PresetItem>): String {
        val arr = JSONArray()
        lista.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("nome", p.nome)
                    put("ext", p.ext)
                    put("args", p.args)
                }
            )
        }
        return arr.toString(2)
    }

    fun detectarIdioma(context: Context): String {
        val cfg = configFile(context)
        if (cfg.exists()) {
            try {
                val lang = JSONObject(cfg.readText(Charsets.UTF_8)).optString("lang")
                if (lang == "pt" || lang == "en") return lang
            } catch (_: Exception) {
            }
        }
        return if (Locale.getDefault().language.lowercase().startsWith("pt")) "pt" else "en"
    }

    fun salvarIdioma(context: Context, lang: String) {
        try {
            configFile(context).writeText(JSONObject().put("lang", lang).toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
