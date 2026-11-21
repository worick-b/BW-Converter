import json
import locale
import os
import re
import shlex
import shutil
import subprocess
import threading
from pathlib import Path

import flet as ft
import flet_permission_handler as fph

TEXTOS = {
    "en": {
        "subtitle": "FFmpeg 8.1 + MKVToolNix",
        "work_folder": "Working Folder:",
        "folder_btn": "Folder",
        "mode_batch": "Batch Mode: Entire working folder",
        "mode_single": "Single File: {name}",
        "mode_multi": "Selected Files: {n} file(s)",
        "pick_file": "Select File(s)",
        "preset_lbl": "Active Preset",
        "manage_tip": "Manage Presets",
        "start_btn": "START CONVERSION",
        "remux_btn": "REMUX MKV",
        "running_btn": "CONVERTING...",
        "remuxing_btn": "REMUXING...",
        "cancel_btn": "CANCEL",
        "canceling": "Canceling operation...",
        "canceled": "[!] Operation canceled by user.",
        "ready": "Ready. Output saved to conv_abw",
        "status_ready": "Ready to convert.",
        "wait": "[!] Wait for the current task to finish.",
        "no_ffmpeg": "[-] Embedded FFmpeg (libffmpeg_bin.so) not found!",
        "no_mkvmerge": "[-] Embedded MKVToolNix (libmkvmerge_bin.so) not found!",
        "preparing": "Preparing engine...",
        "no_media": "[-] No files found to process.",
        "starting": "[+] Starting {n} file(s)",
        "converting": "Processing {i}/{t}: {name} ({pct}%)",
        "done_item": "Done {i}/{t}",
        "finished": "[✓] Finished: {ok}/{t}",
        "folder_set": "[+] Folder set: {name}",
        "dlg_title": "FFmpeg Presets",
        "name_lbl": "Preset Name",
        "ext_lbl": "Extension (e.g. .mp4, .m4a)",
        "args_lbl": "FFmpeg Arguments",
        "save": "Save",
        "restore": "Restore",
        "import": "Import",
        "export": "Export",
        "close": "Close",
        "exported": "[✓] Exported to: {path}",
        "imported": "[✓] Imported {n} preset(s).",
        "restored": "[✓] Default presets restored.",
        "perm_title": "Storage Permission Required",
        "perm_desc": "Allow 'All files access' to function correctly.\n\n(Settings > All files access)",
        "open_settings": "Open Settings",
    },
    "pt": {
        "subtitle": "FFmpeg 8.1 + MKVToolNix",
        "work_folder": "Pasta de Trabalho:",
        "folder_btn": "Pasta",
        "mode_batch": "Modo Lote: Toda a pasta de trabalho",
        "mode_single": "Modo Único: {name}",
        "mode_multi": "Selecionados: {n} arquivo(s)",
        "pick_file": "Selecionar Arquivo(s)",
        "preset_lbl": "Preset Ativo",
        "manage_tip": "Gerenciar Presets",
        "start_btn": "INICIAR CONVERSÃO",
        "remux_btn": "REMUX MKV",
        "running_btn": "CONVERTENDO...",
        "remuxing_btn": "REMUXANDO...",
        "cancel_btn": "CANCELAR",
        "canceling": "Cancelando operação...",
        "canceled": "[!] Operação cancelada pelo usuário.",
        "ready": "Pronto. Salvo em conv_abw",
        "status_ready": "Pronto para converter.",
        "wait": "[!] Aguarde a tarefa atual terminar.",
        "no_ffmpeg": "[-] FFmpeg embutido (libffmpeg_bin.so) não encontrado!",
        "no_mkvmerge": "[-] MKVToolNix embutido (libmkvmerge_bin.so) não encontrado!",
        "preparing": "Preparando motor...",
        "no_media": "[-] Nenhum arquivo encontrado para processar.",
        "starting": "[+] Iniciando {n} arquivo(s)",
        "converting": "Processando {i}/{t}: {name} ({pct}%)",
        "done_item": "Concluído {i}/{t}",
        "finished": "[✓] Finalizado: {ok}/{t}",
        "folder_set": "[+] Pasta definida: {name}",
        "dlg_title": "Presets FFmpeg",
        "name_lbl": "Nome do Preset",
        "ext_lbl": "Extensão (ex: .mp4, .m4a)",
        "args_lbl": "Argumentos FFmpeg",
        "save": "Salvar",
        "restore": "Restaurar",
        "import": "Importar",
        "export": "Exportar",
        "close": "Fechar",
        "exported": "[✓] Exportado para: {path}",
        "imported": "[✓] {n} preset(s) importado(s).",
        "restored": "[✓] Presets padrão restaurados.",
        "perm_title": "Permissão de Arquivos Necessária",
        "perm_desc": "Ative 'Acesso a todos os arquivos' para funcionar corretamente.\n\n(Configurações > Acesso a todos os arquivos)",
        "open_settings": "Abrir Configurações",
    },
}

PRESETS_INICIAIS = [
    {"nome": "Audio MP3 (192k)", "ext": ".mp3", "args": "-vn -c:a libmp3lame -b:a 192k -ar 44100"},
    {"nome": "Audio HE-AAC v2 (39k)", "ext": ".aac", "args": "-vn -c:a libfdk_aac -profile:a aac_he_v2 -ac 2 -b:a 39k -ar 44100"},
    {"nome": "Video MP4 H.264 (CRF 23)", "ext": ".mp4", "args": "-c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p -c:a libfdk_aac -b:a 128k"},
]

EXTS_IGNORAR = {".json", ".txt", ".py", ".apk", ".zip", ".rar", ".7z", ".log", ".ini"}


def obter_pasta_privada() -> Path:
    storage = os.environ.get("FLET_APP_STORAGE_DATA")
    if storage:
        p = Path(storage)
        p.mkdir(parents=True, exist_ok=True)
        return p
    return Path(__file__).resolve().parent


def ler_json(nome_arquivo: str, padrao):
    arq = obter_pasta_privada() / nome_arquivo
    if arq.exists():
        try:
            return json.loads(arq.read_text(encoding="utf-8"))
        except Exception:
            pass
    return padrao


def escrever_json(nome_arquivo: str, dados) -> None:
    try:
        (obter_pasta_privada() / "config.json" if nome_arquivo == "config.json" else obter_pasta_privada() / nome_arquivo).write_text(
            json.dumps(dados, indent=2, ensure_ascii=False), encoding="utf-8"
        )
    except Exception:
        pass


def carregar_presets() -> list[dict]:
    dados = ler_json("presets.json", None)
    if isinstance(dados, list) and dados:
        return dados
    iniciais = [dict(p) for p in PRESETS_INICIAIS]
    escrever_json("presets.json", iniciais)
    return iniciais


def detectar_idioma(page: ft.Page) -> str:
    cfg = ler_json("config.json", {})
    if cfg.get("lang") in ("en", "pt"):
        return cfg["lang"]
    try:
        if getattr(page, "locale", None) and str(page.locale).lower().startswith("pt"):
            return "pt"
    except Exception:
        pass
    try:
        loc = locale.getlocale()
        lang_str = str(next(iter(loc), "") or os.environ.get("LANG", "")).lower()
        return "pt" if lang_str.startswith("pt") else "en"
    except Exception:
        return "en"


def obter_caminho_binario(nome_so: str) -> str | None:
    pastas_busca = set()
    try:
        for linha in Path("/proc/self/maps").read_text(errors="ignore").splitlines():
            for p in linha.split():
                if p.startswith("/") and (".so" in p or ".apk" in p):
                    base_dir = Path(p).parent
                    pastas_busca.update([
                        base_dir,
                        base_dir / "lib" / "arm64",
                        base_dir / "lib" / "arm64-v8a",
                        base_dir / "lib" / "arm",
                        base_dir / "lib" / "armeabi-v7a",
                    ])
    except Exception:
        pass

    for lp in os.environ.get("LD_LIBRARY_PATH", "").split(":"):
        if lp:
            pastas_busca.add(Path(lp))

    for pasta in pastas_busca:
        cand = pasta / nome_so
        if cand.exists():
            return str(cand)
    return None


def eh_caminho_cache(caminho: Path) -> bool:
    s = str(caminho).lower()
    return "cache" in s or "/data/user/" in s or "/data/data/" in s


def resolver_arquivo_original(caminho_selecionado: Path, pasta_trabalho: Path) -> Path:
    if not eh_caminho_cache(caminho_selecionado):
        return caminho_selecionado

    nome = caminho_selecionado.name
    try:
        tamanho = caminho_selecionado.stat().st_size
    except Exception:
        return pasta_trabalho / nome

    sdcard = Path("/storage/emulated/0")
    candidatos_rapidos = [
        pasta_trabalho / nome,
        sdcard / "Download" / nome,
        sdcard / "Movies" / nome,
        sdcard / "Music" / nome,
        sdcard / "Documents" / nome,
        sdcard / "DCIM" / nome,
    ]
    for cand in candidatos_rapidos:
        try:
            if cand.exists() and cand.stat().st_size == tamanho:
                return cand
        except Exception:
            pass

    if sdcard.exists():
        for raiz, dirs, arqs in os.walk(sdcard):
            dirs[:] = [d for d in dirs if not d.startswith(".") and d != "Android" and d != "conv_abw"]
            if nome in arqs:
                cand = Path(raiz) / nome
                try:
                    if cand.stat().st_size == tamanho:
                        return cand
                except Exception:
                    pass

    return pasta_trabalho / nome

def obter_duracao_segundos(bin_ffmpeg: str, caminho_arquivo: str) -> float:
    try:
        res = subprocess.run(
            [bin_ffmpeg, "-i", caminho_arquivo],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            errors="ignore",
        )
        m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", res.stderr or "")
        if m:
            h, mn, s = float(m.group(1)), float(m.group(2)), float(m.group(3))
            return h * 3600 + mn * 60 + s
    except Exception:
        pass
    return 0.0


def rodar_ffmpeg(
    bin_exec: str,
    entrada: str,
    saida_tmp: Path,
    args_extras: list[str],
    estado: dict,
    on_progresso,
) -> tuple[int, str]:
    duracao = obter_duracao_segundos(bin_exec, entrada)
    cmd = [
        bin_exec, "-y", "-v", "error", "-nostats",
        "-progress", "pipe:1", "-i", entrada,
    ] + args_extras + [str(saida_tmp)]

    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="ignore"
    )
    estado["proc_atual"] = proc
    ultimo_pct = -2

    if proc.stdout:
        for linha in proc.stdout:
            if estado["cancelar"]:
                break
            linha = linha.strip()
            if linha.startswith("out_time_us=") and duracao > 0:
                try:
                    us = int(linha.split("=", 1)[1])
                    pct = max(0, min(99, int(((us / 1_000_000.0) / duracao) * 100)))
                    if abs(pct - ultimo_pct) >= 2:
                        ultimo_pct = pct
                        on_progresso(pct)
                except Exception:
                    pass

    stderr_txt = (proc.stderr.read() if proc.stderr else "").strip()
    proc.wait()
    return proc.returncode, stderr_txt


def rodar_mkvmerge(
    bin_exec: str,
    entrada: str,
    saida_tmp: Path,
    estado: dict,
    on_progresso,
) -> tuple[int, str]:
    env = os.environ.copy()
    env["LC_ALL"] = "C.UTF-8"
    env["LANG"] = "C.UTF-8"

    proc = subprocess.Popen(
        [bin_exec, "-o", str(saida_tmp), entrada],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
    )
    estado["proc_atual"] = proc
    pct_sim = 0

    while proc.poll() is None:
        if estado["cancelar"]:
            break
        pct_sim = min(95, pct_sim + 5)
        on_progresso(pct_sim)
        threading.Event().wait(0.35)

    saida_bytes, _ = proc.communicate()
    saida_txt = (saida_bytes or b"").decode("utf-8", errors="ignore").strip()
    return proc.returncode, saida_txt

def main(page: ft.Page):
    page.title = "ABW Converter"
    page.theme_mode = ft.ThemeMode.DARK
    page.bgcolor = "#121316"
    page.padding = 14
    page.window.width = 430
    page.window.height = 780

    pasta_inicial = Path("/storage/emulated/0/Download")
    if not pasta_inicial.exists():
        pasta_inicial = Path("/storage/emulated/0") if Path("/storage/emulated/0").exists() else Path.home()

    estado = {
        "lang": detectar_idioma(page),
        "pasta": pasta_inicial.resolve(),
        "arquivos_selecionados": [],
        "presets": carregar_presets(),
        "editando_idx": None,
        "ocupado": False,
        "modo_atual": "ffmpeg",
        "cancelar": False,
        "proc_atual": None,
    }

    def tr(chave: str, **kwargs) -> str:
        txt = TEXTOS[estado["lang"]].get(chave, chave)
        return txt.format(**kwargs) if kwargs else txt

    txt_subtitulo = ft.Text(tr("subtitle"), size=12, color="#78909C")
    txt_lbl_pasta = ft.Text(tr("work_folder"), size=11, color="#90A4AE")
    txt_pasta = ft.Text(
        str(estado["pasta"]),
        size=12,
        color="#A5D6A7",
        weight=ft.FontWeight.BOLD,
        max_lines=2,
        overflow=ft.TextOverflow.ELLIPSIS,
    )
    txt_alvo = ft.Text(
        tr("mode_batch"),
        size=12,
        color="#81C784",
        weight=ft.FontWeight.W_600,
        expand=True,
        max_lines=1,
        overflow=ft.TextOverflow.ELLIPSIS,
    )
    txt_status = ft.Text(tr("status_ready"), size=12, color="#B0BEC5")
    barra_progresso = ft.ProgressBar(value=0, color="#AB47BC", bgcolor="#262930", height=6, border_radius=4)
    lista_logs = ft.ListView(expand=True, spacing=3, auto_scroll=True)
    dd_presets = ft.Dropdown(label=tr("preset_lbl"), expand=True, text_size=13, border_color="#2C303A")

    txt_start_btn = ft.Text(tr("start_btn"), size=13, weight=ft.FontWeight.BOLD, color="#FFFFFF")
    txt_remux_btn = ft.Text(tr("remux_btn"), size=13, weight=ft.FontWeight.BOLD, color="#FFFFFF")
    txt_cancel_btn = ft.Text(tr("cancel_btn"), size=13, weight=ft.FontWeight.BOLD, color="#FFFFFF")

    def recarregar_dropdown(selecionar_idx: int = 0):
        estado["presets"] = carregar_presets()
        dd_presets.options = [
            ft.dropdown.Option(str(i), f"{p['nome']} ({p['ext']})")
            for i, p in enumerate(estado["presets"])
        ]
        dd_presets.value = str(max(0, min(selecionar_idx, len(estado["presets"]) - 1)))

    recarregar_dropdown(0)

    def log_msg(texto: str, tipo: str = "info"):
        cores = {
            "titulo": "#CE93D8",
            "sucesso": "#81C784",
            "erro": "#E57373",
            "aviso": "#FFD54F",
            "info": "#E0E0E0",
        }
        lista_logs.controls.append(
            ft.Text(texto, size=12, font_family="monospace", color=cores.get(tipo, "#E0E0E0"))
        )
        page.update()

    def set_progresso(val: float, texto: str):
        barra_progresso.value = max(0.0, min(1.0, val))
        txt_status.value = texto
        page.update()

    def atualizar_estado_ocupado(ocupado: bool, modo: str = "ffmpeg"):
        estado["ocupado"] = ocupado
        estado["modo_atual"] = modo
        page.keep_screen_on = ocupado
        for ctrl in (btn_converter, btn_remux, btn_pasta, btn_pick, btn_manage, dd_presets):
            ctrl.disabled = ocupado
        btn_cancelar.visible = ocupado
        btn_cancelar.disabled = False

        txt_start_btn.value = tr("running_btn") if (ocupado and modo == "ffmpeg") else tr("start_btn")
        txt_remux_btn.value = tr("remuxing_btn") if (ocupado and modo == "mkvmerge") else tr("remux_btn")
        page.update()

    def cancelar_click(e):
        if estado["ocupado"] and not estado["cancelar"]:
            estado["cancelar"] = True
            btn_cancelar.disabled = True
            txt_status.value = tr("canceling")
            proc = estado.get("proc_atual")
            if proc:
                try:
                    proc.terminate()
                except Exception:
                    pass
            page.update()

    def tem_acesso_total_arquivos() -> bool:
        sdcard = Path("/storage/emulated/0")
        if not sdcard.exists():
            return True
        try:
            arq_teste = sdcard / ".abw_perm_check"
            arq_teste.write_text("ok", encoding="utf-8")
            arq_teste.unlink()
            return True
        except Exception:
            return False

    def solicitar_permissao_sistema(e):
        page.close(dlg_perm)
        try:
            status = perm_handler.request_permission(fph.PermissionType.MANAGE_EXTERNAL_STORAGE)
            if status != fph.PermissionStatus.GRANTED:
                perm_handler.open_app_settings()
        except Exception:
            try:
                perm_handler.open_app_settings()
            except Exception:
                pass

    txt_perm_title = ft.Text(tr("perm_title"))
    txt_perm_desc = ft.Text(tr("perm_desc"), size=13)
    btn_perm_close = ft.TextButton(text=tr("close"), on_click=lambda e: page.close(dlg_perm))
    btn_perm_settings = ft.ElevatedButton(text=tr("open_settings"), on_click=solicitar_permissao_sistema)
    dlg_perm = ft.AlertDialog(
        modal=True,
        title=txt_perm_title,
        content=txt_perm_desc,
        actions=[btn_perm_close, btn_perm_settings],
    )

    def atualizar_label_alvo():
        sels = estado["arquivos_selecionados"]
        if len(sels) == 1:
            txt_alvo.value = tr("mode_single", name=sels[0].name)
            txt_alvo.color = "#FFD54F"
        elif len(sels) > 1:
            txt_alvo.value = tr("mode_multi", n=len(sels))
            txt_alvo.color = "#FFD54F"
        else:
            txt_alvo.value = tr("mode_batch")
            txt_alvo.color = "#81C784"

    def on_pasta_result(e: ft.FilePickerResultEvent):
        if e.path:
            estado["pasta"] = Path(e.path).resolve()
            txt_pasta.value = str(estado["pasta"])
            estado["arquivos_selecionados"] = []
            atualizar_label_alvo()
            log_msg(tr("folder_set", name=estado["pasta"].name), "titulo")

    def on_arquivo_result(e: ft.FilePickerResultEvent):
        if e.files:
            caminhos = [Path(f.path).resolve() for f in e.files if f.path]
            if caminhos:
                estado["arquivos_selecionados"] = caminhos
                atualizar_label_alvo()
                page.update()

    def on_importar_result(e: ft.FilePickerResultEvent):
        if not e.files:
            return
        try:
            arq_json = next(iter(e.files))
            novos = json.loads(Path(arq_json.path).read_text(encoding="utf-8"))
            if isinstance(novos, list):
                nomes_atuais = {p["nome"].strip().lower() for p in estado["presets"]}
                adicionados = 0
                for it in novos:
                    if isinstance(it, dict) and {"nome", "ext", "args"} <= it.keys():
                        nome_limpo = str(it["nome"]).strip()
                        if nome_limpo and nome_limpo.lower() not in nomes_atuais:
                            estado["presets"].append({
                                "nome": nome_limpo,
                                "ext": str(it["ext"]).strip(),
                                "args": str(it["args"]).strip(),
                            })
                            nomes_atuais.add(nome_limpo.lower())
                            adicionados += 1
                if adicionados > 0:
                    escrever_json("presets.json", estado["presets"])
                    atualizar_lista_presets_ui()
                    log_msg(tr("imported", n=adicionados), "sucesso")
        except Exception as ex:
            log_msg(f"[-] {ex}", "erro")

    def on_exportar_result(e: ft.FilePickerResultEvent):
        if not e.path:
            return
        try:
            destino = Path(e.path).resolve() / "abw_presets_exportados.json"
            destino.write_text(json.dumps(estado["presets"], indent=2, ensure_ascii=False), encoding="utf-8")
            fechar_presets_dlg()
            log_msg(tr("exported", path=destino), "sucesso")
        except Exception as ex:
            log_msg(f"[-] {ex}", "erro")

    seletor_pasta = ft.FilePicker(on_result=on_pasta_result)
    seletor_arquivo = ft.FilePicker(on_result=on_arquivo_result)
    seletor_importar = ft.FilePicker(on_result=on_importar_result)
    seletor_exportar = ft.FilePicker(on_result=on_exportar_result)
    perm_handler = fph.PermissionHandler()
    page.overlay.extend([seletor_pasta, seletor_arquivo, seletor_importar, seletor_exportar, perm_handler])

    def mudar_pasta_click(e):
        if not estado["ocupado"]:
            seletor_pasta.get_directory_path(
                initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None
            )

    def escolher_arquivo_click(e):
        if not estado["ocupado"]:
            seletor_arquivo.pick_files(
                allow_multiple=True,
                initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None,
            )

    def limpar_arquivo_click(e):
        if not estado["ocupado"]:
            estado["arquivos_selecionados"] = []
            atualizar_label_alvo()
            page.update()

    # --- Orquestrador de Lote ---
    def executar_lote(modo: str):
        try:
            if estado["ocupado"]:
                log_msg(tr("wait"), "aviso")
                return

            if modo == "mkvmerge":
                bin_exec = obter_caminho_binario("libmkvmerge_bin.so")
                if not bin_exec:
                    log_msg(tr("no_mkvmerge"), "erro")
                    return
                ext_saida = ".mkv"
                args_extras = []
                nome_modo = "MKVToolNix (Remux MKV)"
            else:
                bin_exec = obter_caminho_binario("libffmpeg_bin.so")
                if not bin_exec:
                    log_msg(tr("no_ffmpeg"), "erro")
                    return
                idx_sel = int(dd_presets.value) if dd_presets.value is not None else 0
                preset = estado["presets"][idx_sel] if 0 <= idx_sel < len(estado["presets"]) else estado["presets"][0]
                ext_saida = preset.get("ext", ".mp4").strip()
                if not ext_saida.startswith("."):
                    ext_saida = "." + ext_saida
                args_extras = shlex.split(preset.get("args", ""))
                nome_modo = f"Preset: {preset['nome']}"

            estado["cancelar"] = False
            estado["proc_atual"] = None
            atualizar_estado_ocupado(True, modo)
            lista_logs.controls.clear()
            set_progresso(0, tr("preparing"))

            def worker():
                caches_limpar = []
                pasta_tmp = obter_pasta_privada() / "abw_tmp"
                try:
                    if estado["arquivos_selecionados"]:
                        arquivos = list(estado["arquivos_selecionados"])
                    else:
                        arquivos = sorted(
                            [
                                a for a in estado["pasta"].iterdir()
                                if a.is_file() and a.suffix.lower() not in EXTS_IGNORAR
                            ],
                            key=lambda x: x.name.lower(),
                        )

                    total = len(arquivos)
                    if total == 0:
                        if str(estado["pasta"]).startswith("/storage/emulated/0") and not tem_acesso_total_arquivos():
                            log_msg(f"[-] {tr('perm_title')}", "erro")
                            page.open(dlg_perm)
                        else:
                            log_msg(tr("no_media"), "erro")
                        return

                    pasta_tmp.mkdir(parents=True, exist_ok=True)
                    log_msg(tr("starting", n=total), "titulo")
                    log_msg(f"    {nome_modo}", "info")

                    ok_count = 0
                    for i, arq in enumerate(arquivos, 1):
                        if estado["cancelar"]:
                            break

                        if eh_caminho_cache(arq):
                            caches_limpar.append(arq)

                        arq_original = resolver_arquivo_original(arq, estado["pasta"])
                        pasta_saida = arq_original.parent / "conv_abw"
                        pasta_saida.mkdir(parents=True, exist_ok=True)

                        fonte_real = arq_original if arq_original.exists() else arq
                        if not fonte_real.exists():
                            log_msg(f"  [!] #{i}/{total} Arquivo inacessível", "erro")
                            continue

                        nome_curto = arq_original.name if len(arq_original.name) <= 22 else arq_original.name[:19] + "..."
                        set_progresso((i - 1) / total, tr("converting", i=i, t=total, name=nome_curto, pct=0))

                        destino_final = pasta_saida / f"{arq_original.stem}{ext_saida}"
                        destino_ascii = pasta_tmp / f"out_{i}{ext_saida}"
                        if destino_ascii.exists():
                            destino_ascii.unlink()

                        def atualizar_item(pct_item: int):
                            prog_global = ((i - 1) + (pct_item / 100.0)) / total
                            set_progresso(prog_global, tr("converting", i=i, t=total, name=nome_curto, pct=pct_item))

                        if modo == "mkvmerge":
                            ret_code, erro_saida = rodar_mkvmerge(
                                bin_exec, str(fonte_real), destino_ascii, estado, atualizar_item
                            )
                            sucesso = ret_code in (0, 1)
                        else:
                            ret_code, erro_saida = rodar_ffmpeg(
                                bin_exec, str(fonte_real), destino_ascii, args_extras, estado, atualizar_item
                            )
                            sucesso = ret_code == 0

                        estado["proc_atual"] = None

                        if estado["cancelar"]:
                            if destino_ascii.exists():
                                destino_ascii.unlink()
                            break

                        if sucesso and destino_ascii.exists() and destino_ascii.stat().st_size > 0:
                            if destino_final.exists():
                                destino_final.unlink()
                            shutil.move(str(destino_ascii), str(destino_final))
                            ok_count += 1
                        else:
                            if destino_ascii.exists():
                                destino_ascii.unlink()
                            log_msg(f"  [!] #{i}/{total} (code={ret_code})", "erro")
                            if erro_saida:
                                ultima = erro_saida.splitlines()[-1].split("\r")[-1].strip()
                                log_msg(f"      {ultima[:120]}", "aviso")

                        set_progresso(i / total, tr("done_item", i=i, t=total))

                    if estado["cancelar"]:
                        log_msg(tr("canceled"), "aviso")
                    else:
                        log_msg(tr("finished", ok=ok_count, t=total), "sucesso")
                except Exception as ex:
                    log_msg(f"[-] {ex}", "erro")
                finally:
                    for arq_c in caches_limpar:
                        try:
                            if arq_c.exists():
                                arq_c.unlink()
                        except Exception:
                            pass
                    for tmp_arq in pasta_tmp.glob("*"):
                        try:
                            tmp_arq.unlink()
                        except Exception:
                            pass
                    estado["proc_atual"] = None
                    atualizar_estado_ocupado(False, modo)
                    set_progresso(0.0 if estado["cancelar"] else 1.0, tr("status_ready"))

            threading.Thread(target=worker, daemon=True).start()
        except Exception as ex_click:
            log_msg(f"[-] {ex_click}", "erro")

    # --- Gerenciador de Presets ---
    inp_nome = ft.TextField(label=tr("name_lbl"), dense=True, text_size=13)
    inp_ext = ft.TextField(label=tr("ext_lbl"), dense=True, text_size=13)
    inp_args = ft.TextField(label=tr("args_lbl"), multiline=True, min_lines=2, max_lines=3, dense=True, text_size=12)
    col_lista_presets = ft.Column(spacing=6, scroll=ft.ScrollMode.AUTO, height=160)

    def fechar_presets_dlg(e=None):
        page.close(dialog_presets)
        recarregar_dropdown(int(dd_presets.value or 0))
        page.update()

    def editar_preset_click(idx: int):
        if 0 <= idx < len(estado["presets"]):
            p = estado["presets"][idx]
            estado["editando_idx"] = idx
            inp_nome.value, inp_ext.value, inp_args.value = p["nome"], p["ext"], p["args"]
            page.update()

    def apagar_preset_click(idx: int):
        if len(estado["presets"]) <= 1 or not (0 <= idx < len(estado["presets"])):
            return
        estado["presets"].pop(idx)
        escrever_json("presets.json", estado["presets"])
        atualizar_lista_presets_ui()

    def atualizar_lista_presets_ui():
        col_lista_presets.controls.clear()
        for i, p in enumerate(estado["presets"]):
            col_lista_presets.controls.append(
                ft.Container(
                    bgcolor="#1E222A",
                    padding=8,
                    border_radius=6,
                    content=ft.Row(
                        [
                            ft.Column(
                                [
                                    ft.Text(f"{p['nome']} ({p['ext']})", size=12, weight=ft.FontWeight.BOLD),
                                    ft.Text(p["args"], size=10, color="#90A4AE", max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                                ],
                                spacing=2,
                                expand=True,
                            ),
                            ft.IconButton(
                                icon=ft.Icons.EDIT,
                                icon_size=18,
                                icon_color="#64B5F6",
                                on_click=lambda _, idx=i: editar_preset_click(idx),
                            ),
                            ft.IconButton(
                                icon=ft.Icons.DELETE,
                                icon_size=18,
                                icon_color="#E57373",
                                on_click=lambda _, idx=i: apagar_preset_click(idx),
                            ),
                        ],
                        spacing=2,
                    ),
                )
            )
        page.update()

    def salvar_preset_click(e):
        nome = (inp_nome.value or "").strip()
        ext = (inp_ext.value or "").strip()
        args = (inp_args.value or "").strip()
        if not nome or not ext or not args:
            return
        if not ext.startswith("."):
            ext = "." + ext
        item = {"nome": nome, "ext": ext, "args": args}
        if estado["editando_idx"] is not None:
            estado["presets"][estado["editando_idx"]] = item
            estado["editando_idx"] = None
        else:
            estado["presets"].append(item)
        escrever_json("presets.json", estado["presets"])
        inp_nome.value = inp_ext.value = inp_args.value = ""
        atualizar_lista_presets_ui()

    def restaurar_presets_click(e):
        nomes_existentes = {p["nome"] for p in estado["presets"]}
        for p in PRESETS_INICIAIS:
            if p["nome"] not in nomes_existentes:
                estado["presets"].append(dict(p))
        escrever_json("presets.json", estado["presets"])
        atualizar_lista_presets_ui()
        log_msg(tr("restored"), "sucesso")

    btn_salvar_preset = ft.ElevatedButton(text=tr("save"), icon=ft.Icons.SAVE, on_click=salvar_preset_click)
    btn_restaurar = ft.TextButton(text=tr("restore"), icon=ft.Icons.RESTORE, on_click=restaurar_presets_click)
    btn_importar = ft.TextButton(
        text=tr("import"),
        icon=ft.Icons.UPLOAD_FILE,
        on_click=lambda e: seletor_importar.pick_files(
            allow_multiple=False,
            allowed_extensions=["json"],
            initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None,
        ),
    )
    btn_exportar = ft.TextButton(
        text=tr("export"),
        icon=ft.Icons.DOWNLOAD,
        on_click=lambda e: seletor_exportar.get_directory_path(
            initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None
        ),
    )
    btn_fechar_dlg = ft.TextButton(text=tr("close"), on_click=fechar_presets_dlg)
    txt_dlg_title = ft.Text(tr("dlg_title"), size=16, weight=ft.FontWeight.BOLD)

    dialog_presets = ft.AlertDialog(
        modal=True,
        title=txt_dlg_title,
        content=ft.Column(
            [
                col_lista_presets,
                ft.Divider(height=10, color="#2C303A"),
                inp_nome,
                inp_ext,
                inp_args,
                ft.Row([btn_salvar_preset, btn_restaurar, btn_importar, btn_exportar], wrap=True, spacing=4),
            ],
            tight=True,
            width=320,
            spacing=8,
        ),
        actions=[btn_fechar_dlg],
    )

    def abrir_presets_click(e):
        if not estado["ocupado"]:
            estado["presets"] = carregar_presets()
            estado["editando_idx"] = None
            inp_nome.value = inp_ext.value = inp_args.value = ""
            atualizar_lista_presets_ui()
            page.open(dialog_presets)

    # --- Botões e Troca de Idioma ---
    btn_pasta = ft.ElevatedButton(
        text=tr("folder_btn"),
        icon=ft.Icons.FOLDER_OPEN,
        style=ft.ButtonStyle(bgcolor="#2B3240", color="#FFFFFF", shape=ft.RoundedRectangleBorder(radius=8)),
        on_click=mudar_pasta_click,
    )
    btn_pick = ft.ElevatedButton(
        text=tr("pick_file"),
        icon=ft.Icons.AUDIO_FILE,
        style=ft.ButtonStyle(bgcolor="#232834", color="#E0E0E0", shape=ft.RoundedRectangleBorder(radius=8)),
        expand=True,
        on_click=escolher_arquivo_click,
    )
    btn_manage = ft.IconButton(
        icon=ft.Icons.TUNE,
        icon_color="#CE93D8",
        tooltip=tr("manage_tip"),
        on_click=abrir_presets_click,
    )

    def alternar_idioma_click(e):
        estado["lang"] = "en" if estado["lang"] == "pt" else "pt"
        escrever_json("config.json", {"lang": estado["lang"]})

        btn_lang.text = estado["lang"].upper()
        txt_subtitulo.value = tr("subtitle")
        txt_lbl_pasta.value = tr("work_folder")
        btn_pasta.text = tr("folder_btn")
        btn_pick.text = tr("pick_file")
        dd_presets.label = tr("preset_lbl")
        btn_manage.tooltip = tr("manage_tip")
        txt_start_btn.value = tr("running_btn") if (estado["ocupado"] and estado["modo_atual"] == "ffmpeg") else tr("start_btn")
        txt_remux_btn.value = tr("remuxing_btn") if (estado["ocupado"] and estado["modo_atual"] == "mkvmerge") else tr("remux_btn")
        txt_cancel_btn.value = tr("cancel_btn")
        txt_status.value = tr("status_ready")

        txt_dlg_title.value = tr("dlg_title")
        inp_nome.label = tr("name_lbl")
        inp_ext.label = tr("ext_lbl")
        inp_args.label = tr("args_lbl")
        btn_salvar_preset.text = tr("save")
        btn_restaurar.text = tr("restore")
        btn_importar.text = tr("import")
        btn_exportar.text = tr("export")
        btn_fechar_dlg.text = tr("close")

        txt_perm_title.value = tr("perm_title")
        txt_perm_desc.value = tr("perm_desc")
        btn_perm_close.text = tr("close")
        btn_perm_settings.text = tr("open_settings")

        atualizar_label_alvo()
        page.update()

    btn_lang = ft.TextButton(
        text=estado["lang"].upper(),
        style=ft.ButtonStyle(color="#CE93D8"),
        on_click=alternar_idioma_click,
    )

    # --- Layout Principal ---
    card_pasta = ft.Container(
        bgcolor="#191B20",
        border=ft.border.all(1, "#2C303A"),
        border_radius=10,
        padding=10,
        content=ft.Row(
            [
                ft.Icon(ft.Icons.FOLDER_SPECIAL, color="#FFD54F", size=24),
                ft.Column([txt_lbl_pasta, txt_pasta], spacing=2, expand=True),
                btn_pasta,
            ],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
        ),
    )

    card_alvo = ft.Container(
        bgcolor="#191B20",
        border=ft.border.all(1, "#2C303A"),
        border_radius=10,
        padding=10,
        content=ft.Column(
            [
                ft.Row(
                    [txt_alvo, ft.IconButton(icon=ft.Icons.CLEAR, icon_size=18, on_click=limpar_arquivo_click)],
                    alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
                ),
                ft.Row([btn_pick]),
            ],
            spacing=6,
        ),
    )

    card_preset = ft.Container(
        bgcolor="#191B20",
        border=ft.border.all(1, "#2C303A"),
        border_radius=10,
        padding=10,
        content=ft.Row([dd_presets, btn_manage], spacing=6),
    )

    btn_converter = ft.ElevatedButton(
        content=ft.Row(
            [ft.Icon(ft.Icons.PLAY_ARROW_ROUNDED, size=20, color="#FFFFFF"), txt_start_btn],
            alignment=ft.MainAxisAlignment.CENTER,
            spacing=6,
        ),
        style=ft.ButtonStyle(
            bgcolor="#7B1FA2",
            shape=ft.RoundedRectangleBorder(radius=10),
            padding=ft.padding.symmetric(vertical=14),
        ),
        on_click=lambda e: executar_lote("ffmpeg"),
    )

    btn_remux = ft.ElevatedButton(
        content=ft.Row(
            [ft.Icon(ft.Icons.MOVIE_FILTER_ROUNDED, size=20, color="#FFFFFF"), txt_remux_btn],
            alignment=ft.MainAxisAlignment.CENTER,
            spacing=6,
        ),
        style=ft.ButtonStyle(
            bgcolor="#00695C",
            shape=ft.RoundedRectangleBorder(radius=10),
            padding=ft.padding.symmetric(vertical=14),
        ),
        on_click=lambda e: executar_lote("mkvmerge"),
    )

    btn_cancelar = ft.ElevatedButton(
        visible=False,
        content=ft.Row(
            [ft.Icon(ft.Icons.STOP_ROUNDED, size=20, color="#FFFFFF"), txt_cancel_btn],
            alignment=ft.MainAxisAlignment.CENTER,
            spacing=6,
        ),
        style=ft.ButtonStyle(
            bgcolor="#C62828",
            shape=ft.RoundedRectangleBorder(radius=10),
            padding=ft.padding.symmetric(vertical=14),
        ),
        on_click=cancelar_click,
    )

    card_logs = ft.Container(
        bgcolor="#0D0E11",
        border=ft.border.all(1, "#262930"),
        border_radius=10,
        padding=10,
        expand=True,
        content=lista_logs,
    )

    page.add(
        ft.Row(
            [
                ft.Text("ABW Converter", size=18, weight=ft.FontWeight.BOLD, color="#CE93D8"),
                ft.Row([txt_subtitulo, btn_lang], spacing=4),
            ],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
        ),
        card_pasta,
        card_alvo,
        card_preset,
        ft.Column(
            [
                ft.Row([ft.Container(content=btn_converter, expand=True)]),
                ft.Row([ft.Container(content=btn_remux, expand=True)]),
                ft.Row([ft.Container(content=btn_cancelar, expand=True)]),
            ],
            spacing=6,
        ),
        ft.Column([txt_status, barra_progresso], spacing=4),
        card_logs,
    )

    if not tem_acesso_total_arquivos():
        page.open(dlg_perm)
    log_msg(f"[✓] {tr('ready')}", "sucesso")


if __name__ == "__main__":
    ft.app(target=main)
