#include <jni.h>
#include <GLES3/gl3.h>
#include "nanovg.h"
#define NANOVG_GLES3_IMPLEMENTATION
#include "nanovg_gl.h"
#include "ShawarmaUI_Android.h"
#include "ShawarmaIcons.h"

#include <string>
#include <vector>
#include <mutex>

struct PresetItem {
    std::string nome;
    std::string extensao;
    std::string args;
};

struct AppConverterState {
    bool isPtBr = true;
    int abaAtual = 0;           // 0 = Converter, 1 = Presets, 2 = Log
    int modoLote = 0;           // 0 = Arquivo Unico, 1 = Pasta (Lote)
    int motorIdx = 0;           // 0 = FFmpeg 8.1, 1 = MKVMerge 57.0
    int presetIdx = 0;

    std::string caminhoSelecionado;
    std::vector<PresetItem> presets;

    std::string editNome;
    std::string editExt;
    std::string editArgs;

    std::mutex mtx;
    bool convertendo = false;
    float progresso = 0.0f;
    std::string statusTexto;
    std::string logSaida;
};

static NVGcontext* g_vg = nullptr;
static ShawarmaUI::Context g_ui;
static AppConverterState g_app;

static JavaVM* g_jvm = nullptr;
static jobject g_activityObj = nullptr;
static jmethodID g_midRequestRender = nullptr;
static jmethodID g_midShowKeyboard = nullptr;
static jmethodID g_midPickPath = nullptr;
static jmethodID g_midStartConvert = nullptr;
static jmethodID g_midCancelConvert = nullptr;
static jmethodID g_midSavePresetsJson = nullptr;
static jmethodID g_midImportPresets = nullptr;
static jmethodID g_midExportPresets = nullptr;

static int g_width = 0;
static int g_height = 0;

static const char* Tr(const char* pt, const char* en) {
    return g_app.isPtBr ? pt : en;
}

static std::string IconLabel(const char* iconUtf8, const char* text) {
    return std::string(iconUtf8) + "  " + text;
}

static std::string EscapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') continue;
        else if (c == '\t') out += "\\t";
        else out.push_back(c);
    }
    return out;
}

static std::string SerializePresetsToJson(const std::vector<PresetItem>& list) {
    std::string json = "[\n";
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& p = list.at(i);
        json += "  {\"nome\":\"" + EscapeJson(p.nome) + "\","
                "\"extensao\":\"" + EscapeJson(p.extensao) + "\","
                "\"args\":\"" + EscapeJson(p.args) + "\"}";
        if (i + 1 < list.size()) json += ",";
        json += "\n";
    }
    json += "]\n";
    return json;
}

static bool ExtractJsonStringField(const std::string& obj, const std::string& key, std::string& outVal) {
    std::string pat = "\"" + key + "\"";
    size_t kPos = obj.find(pat);
    if (kPos == std::string::npos) return false;
    size_t colon = obj.find(':', kPos + pat.size());
    if (colon == std::string::npos) return false;
    size_t qStart = obj.find('"', colon + 1);
    if (qStart == std::string::npos) return false;

    std::string res;
    bool esc = false;
    for (size_t i = qStart + 1; i < obj.size(); ++i) {
        char c = obj.at(i);
        if (esc) {
            if (c == 'n') res.push_back('\n');
            else if (c == 't') res.push_back('\t');
            else res.push_back(c);
            esc = false;
        } else if (c == '\\') {
            esc = true;
        } else if (c == '"') {
            outVal = res;
            return true;
        } else {
            res.push_back(c);
        }
    }
    return false;
}

static std::vector<PresetItem> ParsePresetsFromJson(const std::string& json) {
    std::vector<PresetItem> parsed;
    size_t pos = 0;
    while (pos < json.size()) {
        size_t objStart = json.find('{', pos);
        if (objStart == std::string::npos) break;
        size_t objEnd = json.find('}', objStart);
        if (objEnd == std::string::npos) break;

        std::string slice = json.substr(objStart, objEnd - objStart + 1);
        PresetItem item;
        if (ExtractJsonStringField(slice, "nome", item.nome) &&
            ExtractJsonStringField(slice, "extensao", item.extensao) &&
            ExtractJsonStringField(slice, "args", item.args) &&
            !item.nome.empty() && !item.extensao.empty()) {
            parsed.push_back(item);
        }
        pos = objEnd + 1;
    }
    return parsed;
}

static void SincronizarCamposPresetSelecionado() {
    if (g_app.presets.empty()) {
        g_app.presetIdx = 0;
        g_app.editNome.clear();
        g_app.editExt.clear();
        g_app.editArgs.clear();
        return;
    }
    g_app.presetIdx = std::clamp(g_app.presetIdx, 0, static_cast<int>(g_app.presets.size()) - 1);
    const auto& sel = g_app.presets.at(g_app.presetIdx);
    g_app.editNome = sel.nome;
    g_app.editExt  = sel.extensao;
    g_app.editArgs = sel.args;
}

static void RestaurarPresetsPadrao() {
    g_app.presets = {
        { "Audio MP3 (192k)", ".mp3", "-vn -c:a libmp3lame -b:a 192k -ar 44100" },
        { "Audio HE-AAC v2 (39k)", ".aac", "-vn -c:a libfdk_aac -profile:a aac_he_v2 -ac 2 -b:a 39k -ar 44100" },
        { "Audio Opus (96k)", ".opus", "-vn -c:a libopus -b:a 96k -vbr on" },
        { "Video MP4 H.264 (CRF 23)", ".mp4", "-c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p -c:a libfdk_aac -b:a 128k" },
        { "Video WebM VP9 (CRF 32)", ".webm", "-c:v libvpx-vp9 -crf 32 -b:v 0 -row-mt 1 -c:a libopus -b:a 96k" }
    };
    g_app.presetIdx = 0;
    SincronizarCamposPresetSelecionado();
}

static void CallVoidJNI(jmethodID mid) {
    if (!g_jvm || !g_activityObj || !mid) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(g_activityObj, mid);
    if (attached) g_jvm->DetachCurrentThread();
}

static void CallBoolJNI(jmethodID mid, bool val) {
    if (!g_jvm || !g_activityObj || !mid) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(g_activityObj, mid, static_cast<jboolean>(val));
    if (attached) g_jvm->DetachCurrentThread();
}

static void CallStringJNI(jmethodID mid, const std::string& str) {
    if (!g_jvm || !g_activityObj || !mid) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring jStr = env->NewStringUTF(str.c_str());
    env->CallVoidMethod(g_activityObj, mid, jStr);
    env->DeleteLocalRef(jStr);
    if (attached) g_jvm->DetachCurrentThread();
}

static void SalvarPresetsAutomatico() {
    CallStringJNI(g_midSavePresetsJson, SerializePresetsToJson(g_app.presets));
}

static void DispararConversaoJNI() {
    if (!g_jvm || !g_activityObj || !g_midStartConvert) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    std::string ext = ".mkv";
    std::string args = "";
    if (g_app.motorIdx == 0 && !g_app.presets.empty()) {
        const auto& p = g_app.presets.at(g_app.presetIdx);
        ext = p.extensao;
        args = p.args;
    }

    jstring jPath = env->NewStringUTF(g_app.caminhoSelecionado.c_str());
    jstring jExt  = env->NewStringUTF(ext.c_str());
    jstring jArgs = env->NewStringUTF(args.c_str());

    env->CallVoidMethod(g_activityObj, g_midStartConvert,
                        static_cast<jint>(g_app.modoLote),
                        static_cast<jint>(g_app.motorIdx),
                        jPath, jExt, jArgs);

    env->DeleteLocalRef(jPath);
    env->DeleteLocalRef(jExt);
    env->DeleteLocalRef(jArgs);
    if (attached) g_jvm->DetachCurrentThread();
}

static void RenderBetterWayUI() {
    bool isConverting = false;
    float progress = 0.0f;
    std::string statusCopy;
    std::string logCopy;
    {
        std::lock_guard<std::mutex> lock(g_app.mtx);
        isConverting = g_app.convertendo;
        progress = g_app.progresso;
        statusCopy = g_app.statusTexto;
        logCopy = g_app.logSaida;
    }

    ShawarmaUI::Color goldTitle(232, 176, 56);
    ShawarmaUI::Color greenOk(115, 224, 140);
    ShawarmaUI::Color redDanger(196, 54, 44);
    ShawarmaUI::Color redDangerHover(222, 68, 56);
    ShawarmaUI::Color neutralBtn(52, 47, 44);
    ShawarmaUI::Color neutralHover(68, 61, 57);

    // Cabeçalho com título BetterWay Converter e botão rápido EN / PT-BR
    g_ui.AlignText().Text("BetterWay Converter", true);
    g_ui.SameLineRight(64);
    if (g_ui.Button(g_app.isPtBr ? "PT-BR" : "EN", 64, 30, neutralBtn, neutralHover)) {
        g_app.isPtBr = !g_app.isPtBr;
        g_ui.Toast(g_app.isPtBr ? "Idioma: Portugues (BR)" : "Language: English", goldTitle);
    }

    g_ui.Spacing(4);
    g_ui.TabBar(g_app.abaAtual, {
        Tr("Converter", "Convert"),
        "Presets",
        Tr("Saida / Log", "Output / Log")
    });
    g_ui.Spacing(4);

    if (g_app.abaAtual == 0) {
        // ABA 1: CONVERTER
        {
            auto dis = g_ui.Disabled(isConverting);
            if (auto p1 = g_ui.Panel(Tr("1. Origem & Motor", "1. Source & Engine"), goldTitle)) {
                if (g_ui.SegmentedControl(g_app.modoLote, {
                    IconLabel(ShawarmaIcons::File, Tr("Arquivo", "File")),
                    IconLabel(ShawarmaIcons::Folder, Tr("Pasta (Lote)", "Batch Folder"))
                })) {
                    g_app.caminhoSelecionado.clear();
                }

                g_ui.Spacing(2);
                g_ui.InputTextReadOnly(
                    g_app.caminhoSelecionado.empty()
                        ? Tr("Nenhum arquivo ou pasta selecionado...", "No file or folder selected...")
                        : g_app.caminhoSelecionado
                );

                std::string pickLbl = g_app.modoLote == 0
                    ? IconLabel(ShawarmaIcons::File, Tr("Selecionar Arquivo de Midia", "Select Media File"))
                    : IconLabel(ShawarmaIcons::Folder, Tr("Selecionar Pasta Completa", "Select Media Folder"));

                if (g_ui.Button(pickLbl, -1, 38)) {
                    CallBoolJNI(g_midPickPath, g_app.modoLote == 1);
                }

                g_ui.SeparatorText(Tr("Motor de Processamento", "Processing Engine"));
                g_ui.SegmentedControl(g_app.motorIdx, { "FFmpeg 8.1", "MKVMerge 57.0" });
            }

            if (auto p2 = g_ui.Panel(Tr("2. Configuracao de Saida", "2. Output Settings"), goldTitle)) {
                if (g_app.motorIdx == 0) {
                    std::vector<std::string> nomes;
                    nomes.reserve(g_app.presets.size());
                    for (const auto& item : g_app.presets) {
                        nomes.push_back(item.nome + " (" + item.extensao + ")");
                    }
                    if (g_ui.Combo("combo_presets", g_app.presetIdx, nomes)) {
                        SincronizarCamposPresetSelecionado();
                    }
                    if (!g_app.presets.empty()) {
                        g_ui.KeyValue("Args", g_app.presets.at(g_app.presetIdx).args);
                    }
                } else {
                    g_ui.Badge(Tr("Remux direto para .MKV (Sem perda de qualidade)", "Direct remux to .MKV (Lossless)"),
                               greenOk);
                }
                g_ui.TextColored(g_ui.style.textDim, Tr("Saida salva na subpasta: conv_bw/", "Output saved to subfolder: conv_bw/"));
            }
        }

        if (!isConverting) {
            auto disBtn = g_ui.Disabled(g_app.caminhoSelecionado.empty());
            if (g_ui.Button(IconLabel(ShawarmaIcons::Play, Tr("INICIAR CONVERSAO", "START CONVERSION")),
                            -1, 44, ShawarmaUI::Color(46, 145, 85), ShawarmaUI::Color(58, 175, 105))) {
                {
                    std::lock_guard<std::mutex> lock(g_app.mtx);
                    g_app.convertendo = true;
                    g_app.progresso = 0.0f;
                    g_app.statusTexto = Tr("Iniciando processo...", "Starting process...");
                    g_app.logSaida.clear();
                }
                DispararConversaoJNI();
            }
        } else {
            if (auto pProg = g_ui.Panel("", goldTitle)) {
                g_ui.Spinner(20);
                g_ui.SameLine().AlignText().Text(statusCopy.empty() ? Tr("Processando...", "Processing...") : statusCopy, true);
                int pct = static_cast<int>(progress * 100.0f + 0.5f);
                g_ui.ProgressBar(progress, -1, 20, goldTitle, std::to_string(pct) + "%");

                if (g_ui.Button(IconLabel(ShawarmaIcons::Square, Tr("Cancelar", "Cancel")),
                                -1, 36, redDanger, redDangerHover)) {
                    CallVoidJNI(g_midCancelConvert);
                }
            }
        }

    } else if (g_app.abaAtual == 1) {
        // ABA 2: PRESETS CUSTOMIZADOS + IMPORTAR / EXPORTAR JSON
        if (auto p = g_ui.Panel(Tr("Gerenciar Presets FFmpeg", "Manage FFmpeg Presets"), goldTitle)) {
            std::vector<std::string> nomes;
            for (const auto& item : g_app.presets) nomes.push_back(item.nome);

            if (g_ui.Combo("edit_combo", g_app.presetIdx, nomes)) {
                SincronizarCamposPresetSelecionado();
            }

            g_ui.InputText("p_nome", g_app.editNome, Tr("Nome do Preset", "Preset Name"));
            g_ui.InputText("p_ext", g_app.editExt, Tr("Extensao (ex: .mp3, .mp4)", "Extension (e.g. .mp3, .mp4)"));
            g_ui.TextColored(g_ui.style.textDim, Tr("Argumentos FFmpeg:", "FFmpeg Arguments:"));
            g_ui.InputTextMultiline("p_args", g_app.editArgs, -1, 105);

            int halfW = static_cast<int>((g_ui.GetAvailWidth() / g_ui.dpiScale - g_ui.style.itemSpacingX) / 2);

            // Linha 1: Salvar / Novo  |  Excluir
            if (g_ui.Button(IconLabel(ShawarmaIcons::Save, Tr("Salvar / Novo", "Save / Add")), halfW, 38)) {
                if (!g_app.editNome.empty() && !g_app.editExt.empty()) {
                    if (g_app.editExt.front() != '.') g_app.editExt.insert(g_app.editExt.begin(), '.');
                    bool atualizado = false;
                    for (size_t i = 0; i < g_app.presets.size(); ++i) {
                        if (g_app.presets.at(i).nome == g_app.editNome) {
                            g_app.presets.at(i) = { g_app.editNome, g_app.editExt, g_app.editArgs };
                            g_app.presetIdx = static_cast<int>(i);
                            atualizado = true;
                            break;
                        }
                    }
                    if (!atualizado) {
                        g_app.presets.push_back({ g_app.editNome, g_app.editExt, g_app.editArgs });
                        g_app.presetIdx = static_cast<int>(g_app.presets.size()) - 1;
                    }
                    SalvarPresetsAutomatico();
                    g_ui.Toast(Tr("Preset salvo!", "Preset saved!"), greenOk);
                }
            }

            g_ui.SameLine();
            {
                auto disDel = g_ui.Disabled(g_app.presets.size() <= 1);
                if (g_ui.Button(IconLabel(ShawarmaIcons::Trash2, Tr("Excluir", "Delete")),
                                halfW, 38, redDanger, redDangerHover)) {
                    if (g_app.presets.size() > 1) {
                        g_app.presets.erase(g_app.presets.begin() + g_app.presetIdx);
                        SincronizarCamposPresetSelecionado();
                        SalvarPresetsAutomatico();
                        g_ui.Toast(Tr("Preset removido.", "Preset deleted."), goldTitle);
                    }
                }
            }

            g_ui.SeparatorText(Tr("Backup JSON (Importar / Exportar)", "JSON Backup (Import / Export)"));

            // Linha 2: Importar JSON  |  Exportar JSON
            if (g_ui.Button(IconLabel(ShawarmaIcons::Download, Tr("Importar JSON", "Import JSON")),
                            halfW, 36, neutralBtn, neutralHover)) {
                CallVoidJNI(g_midImportPresets);
            }
            g_ui.SameLine();
            if (g_ui.Button(IconLabel(ShawarmaIcons::Upload, Tr("Exportar JSON", "Export JSON")),
                            halfW, 36, neutralBtn, neutralHover)) {
                CallStringJNI(g_midExportPresets, SerializePresetsToJson(g_app.presets));
            }

            // Linha 3: Restaurar Padrão
            if (g_ui.Button(Tr("Restaurar Presets Padrao", "Restore Default Presets"),
                            -1, 34, neutralBtn, neutralHover)) {
                RestaurarPresetsPadrao();
                SalvarPresetsAutomatico();
                g_ui.Toast(Tr("Presets restaurados!", "Defaults restored!"), goldTitle);
            }
        }

    } else if (g_app.abaAtual == 2) {
        // ABA 3: LOG DO CONSOLE
        if (auto p = g_ui.Panel(Tr("Console FFmpeg / MKVMerge", "FFmpeg / MKVMerge Console"), goldTitle)) {
            std::string viewLog = logCopy.empty()
                ? Tr("Nenhuma saida registrada ainda.", "No output logged yet.")
                : logCopy;
            g_ui.InputTextMultiline("console_log", viewLog, -1, 240);
            if (g_ui.Button(IconLabel(ShawarmaIcons::Trash2, Tr("Limpar Log", "Clear Log")),
                            -1, 36, neutralBtn, neutralHover)) {
                std::lock_guard<std::mutex> lock(g_app.mtx);
                g_app.logSaida.clear();
            }
        }
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeInit(JNIEnv* env, jobject thiz, jfloat density, jboolean isPtBr) {
    env->GetJavaVM(&g_jvm);
    if (g_activityObj) env->DeleteGlobalRef(g_activityObj);
    g_activityObj = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    g_midRequestRender   = env->GetMethodID(cls, "requestRenderFromNative", "()V");
    g_midShowKeyboard    = env->GetMethodID(cls, "setKeyboardVisible", "(Z)V");
    g_midPickPath        = env->GetMethodID(cls, "pickMediaFromNative", "(Z)V");
    g_midStartConvert    = env->GetMethodID(cls, "startConvertFromNative", "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_midCancelConvert   = env->GetMethodID(cls, "cancelConvertFromNative", "()V");
    g_midSavePresetsJson = env->GetMethodID(cls, "savePresetsJsonFromNative", "(Ljava/lang/String;)V");
    g_midImportPresets   = env->GetMethodID(cls, "importPresetsFromNative", "()V");
    g_midExportPresets   = env->GetMethodID(cls, "exportPresetsFromNative", "(Ljava/lang/String;)V");

    if (g_vg) {
        nvgDeleteGLES3(g_vg);
        g_vg = nullptr;
    }
    g_vg = nvgCreateGLES3(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
    g_ui.InitGLES3(g_vg, density);
    g_ui.LoadIconFontFromMemory(ShawarmaUI::kIconFontTTF.data(), static_cast<int>(ShawarmaUI::kIconFontTTF.size()));
    g_ui.ApplyTheme(ShawarmaUI::Theme::ShawarmaGold);

    g_app.isPtBr = (isPtBr == JNI_TRUE);
    if (g_app.presets.empty()) {
        RestaurarPresetsPadrao();
    }

    g_ui.onRequestRedraw = []() { CallVoidJNI(g_midRequestRender); };
    g_ui.onShowKeyboard  = [](bool show) { CallBoolJNI(g_midShowKeyboard, show); };
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h, jint topInset, jint bottomInset) {
    g_width = w;
    g_height = h;
    g_ui.SetSafeAreaInsets(topInset, bottomInset);
}

JNIEXPORT jboolean JNICALL
Java_com_bw_converter_MainActivity_nativeRender(JNIEnv*, jobject) {
    if (g_width <= 0 || g_height <= 0) return JNI_FALSE;
    g_ui.BeginFrame(g_width, g_height);
    RenderBetterWayUI();
    g_ui.EndFrame();
    return g_ui.HasActiveAnimations() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeTouch(JNIEnv*, jobject, jint action, jint x, jint y) {
    if (action == 0) g_ui.OnTouchDown(x, y);
    else if (action == 1) g_ui.OnTouchUp(x, y);
    else if (action == 2) g_ui.OnTouchMove(x, y);
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeTextCommit(JNIEnv* env, jobject, jstring text) {
    const char* utf = env->GetStringUTFChars(text, nullptr);
    if (utf) {
        g_ui.OnTextCommit(utf);
        env->ReleaseStringUTFChars(text, utf);
    }
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeKeySpecial(JNIEnv*, jobject, jint code) {
    if (code == 1) g_ui.OnKeyBackspace();
    else if (code == 2) g_ui.OnKeyEnter();
}

// --- CALLBACKS DO KOTLIN -> UI C++ ---

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeLoadPresetsJson(JNIEnv* env, jobject, jstring jsonStr, jboolean showToast) {
    if (!jsonStr) return;
    const char* utf = env->GetStringUTFChars(jsonStr, nullptr);
    if (!utf) return;
    std::string raw(utf);
    env->ReleaseStringUTFChars(jsonStr, utf);

    auto loaded = ParsePresetsFromJson(raw);
    if (!loaded.empty()) {
        g_app.presets = std::move(loaded);
        g_app.presetIdx = 0;
        SincronizarCamposPresetSelecionado();
        if (showToast == JNI_TRUE) {
            SalvarPresetsAutomatico();
            g_ui.Toast(Tr("Presets importados com sucesso!", "Presets imported!"), ShawarmaUI::Color(115, 224, 140));
        }
        g_ui.RequestRedraw();
    } else if (showToast == JNI_TRUE) {
        g_ui.Toast(Tr("Arquivo JSON invalido ou vazio.", "Invalid or empty JSON file."), ShawarmaUI::Color(222, 68, 56));
    }
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeNotifyToast(JNIEnv* env, jobject, jstring msg, jboolean isError) {
    if (!msg) return;
    const char* utf = env->GetStringUTFChars(msg, nullptr);
    if (!utf) return;
    std::string text(utf);
    env->ReleaseStringUTFChars(msg, utf);
    g_ui.Toast(text, isError == JNI_TRUE ? ShawarmaUI::Color(222, 68, 56) : ShawarmaUI::Color(115, 224, 140));
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeSetSelectedPath(JNIEnv* env, jobject, jstring path) {
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf) {
        g_app.caminhoSelecionado = utf;
        env->ReleaseStringUTFChars(path, utf);
        g_ui.RequestRedraw();
    }
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeUpdateProgress(JNIEnv* env, jobject, jfloat prog, jstring status, jstring logAppend) {
    std::lock_guard<std::mutex> lock(g_app.mtx);
    g_app.progresso = prog;
    if (status) {
        const char* st = env->GetStringUTFChars(status, nullptr);
        if (st) {
            g_app.statusTexto = st;
            env->ReleaseStringUTFChars(status, st);
        }
    }
    if (logAppend) {
        const char* lg = env->GetStringUTFChars(logAppend, nullptr);
        if (lg) {
            g_app.logSaida += lg;
            env->ReleaseStringUTFChars(logAppend, lg);
        }
    }
    g_ui.RequestRedraw();
}

JNIEXPORT void JNICALL
Java_com_bw_converter_MainActivity_nativeOnConvertFinished(JNIEnv*, jobject, jboolean success, jint totalConverted) {
    {
        std::lock_guard<std::mutex> lock(g_app.mtx);
        g_app.convertendo = false;
        g_app.progresso = success ? 1.0f : 0.0f;
    }
    if (success) {
        std::string msg = g_app.isPtBr
            ? ("Concluido! " + std::to_string(totalConverted) + " arquivo(s) em conv_bw/")
            : ("Done! " + std::to_string(totalConverted) + " file(s) saved in conv_bw/");
        g_ui.Toast(msg, ShawarmaUI::Color(115, 224, 140), 3500);
    } else {
        g_ui.Toast(Tr("Conversao interrompida ou falhou.", "Conversion canceled or failed."),
                   ShawarmaUI::Color(222, 68, 56), 3500);
    }
    g_ui.RequestRedraw();
}

} // extern "C"
