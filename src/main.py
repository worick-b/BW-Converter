import os
import re
import json
import shlex
import shutil
import locale
import threading
import subprocess
from pathlib import Path
import flet as ft
import flet_permission_handler as fph

TEXTOS = {
    "en": {
        "subtitle": "FFmpeg 8.1 Engine",
        "work_folder": "Working Folder:",
        "folder_btn": "Folder",
        "mode_batch": "Batch Mode: Entire working folder",
        "mode_single": "Single File: {name}",
        "pick_file": "Single File: Select the File",
        "preset_lbl": "Active Preset",
        "manage_tip": "Manage Presets",
        "start_btn": "START CONVERSION",
        "running_btn": "CONVERTING...",
        "ready": "Ready. Output saved to conv_abw",
        "status_ready": "Ready to convert.",
        "wait": "[!] Wait for the current task to finish.",
        "no_ffmpeg": "[-] Embedded FFmpeg (libffmpeg_bin.so) not found!",
        "preparing": "Preparing FFmpeg...",
        "no_media": "[-] No files found to convert.",
        "starting": "[+] Starting {n} file(s)",
        "converting": "Converting {i}/{t}: {name} ({pct}%)",
        "done_item": "Done {i}/{t}",
        "failed_item": "  [!] Skipped/Failed {name}",
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
        "subtitle": "Motor FFmpeg 8.1",
        "work_folder": "Pasta de Trabalho:",
        "folder_btn": "Pasta",
        "mode_batch": "Modo Lote: Toda a pasta de trabalho",
        "mode_single": "Modo Único: {name}",
        "pick_file": "Modo Único: Selecionar o Arquivo",
        "preset_lbl": "Preset Ativo",
        "manage_tip": "Gerenciar Presets",
        "start_btn": "INICIAR CONVERSÃO",
        "running_btn": "CONVERTENDO...",
        "ready": "Pronto. Salvo em conv_abw",
        "status_ready": "Pronto para converter.",
        "wait": "[!] Aguarde a conversão atual terminar.",
        "no_ffmpeg": "[-] FFmpeg embutido (libffmpeg_bin.so) não encontrado!",
        "preparing": "Preparando FFmpeg...",
        "no_media": "[-] Nenhum arquivo encontrado para converter.",
        "starting": "[+] Iniciando {n} arquivo(s)",
        "converting": "Convertendo {i}/{t}: {name} ({pct}%)",
        "done_item": "Concluído {i}/{t}",
        "failed_item": "  [!] Ignorado/Falha em {name}",
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


def obter_pasta_privada() -> Path:
    storage = os.environ.get("FLET_APP_STORAGE_DATA")
    if storage:
        p = Path(storage)
        p.mkdir(parents=True, exist_ok=True)
        return p
    return Path(__file__).resolve().parent


def carregar_config() -> dict:
    arq = obter_pasta_privada() / "config.json"
    if arq.exists():
        try:
            return json.loads(arq.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def salvar_config(dados: dict) -> None:
    try:
        (obter_pasta_privada() / "config.json").write_text(
            json.dumps(dados, indent=2, ensure_ascii=False), encoding="utf-8"
        )
    except Exception:
        pass


def carregar_presets() -> list[dict]:
    arq = obter_pasta_privada() / "presets.json"
    if arq.exists():
        try:
            dados = json.loads(arq.read_text(encoding="utf-8"))
            if isinstance(dados, list) and dados:
                return dados
        except Exception:
            pass
    iniciais = [dict(p) for p in PRESETS_INICIAIS]
    salvar_presets(iniciais)
    return iniciais


def salvar_presets(lista: list[dict]) -> None:
    try:
        (obter_pasta_privada() / "presets.json").write_text(
            json.dumps(lista, indent=2, ensure_ascii=False), encoding="utf-8"
        )
    except Exception:
        pass


def obter_caminho_ffmpeg() -> str | None:
    nome_so = "libffmpeg_bin.so"
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

    pasta_src = Path(__file__).resolve().parent
    for nome in (nome_so, "ffmpeg.exe", "ffmpeg"):
        if (pasta_src / nome).exists():
            return str(pasta_src / nome)

    return shutil.which("ffmpeg")


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


def detectar_idioma(page: ft.Page) -> str:
    cfg = carregar_config()
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

def main(page: ft.Page):
    page.title = "ABW-Converter"
    page.theme_mode = ft.ThemeMode.DARK
    page.bgcolor = "#121316"
    page.padding = 14
    page.window.width = 430
    page.window.height = 780

    pasta_inicial = Path("/storage/emulated/0")
    if not pasta_inicial.exists():
        pasta_inicial = Path.home()

    estado = {
        "lang": detectar_idioma(page),
        "pasta": pasta_inicial.resolve(),
        "arquivo_unico": None,
        "presets": carregar_presets(),
        "editando_idx": None,
        "ocupado": False,
    }

    def tr(chave: str, **kwargs) -> str:
        txt = TEXTOS[estado["lang"]].get(chave, chave)
        return txt.format(**kwargs) if kwargs else txt

    # --- Controles Principais ---
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

    def atualizar_estado_ocupado(ocupado: bool):
        estado["ocupado"] = ocupado
        btn_converter.disabled = ocupado
        btn_pasta.disabled = ocupado
        btn_pick.disabled = ocupado
        btn_manage.disabled = ocupado
        dd_presets.disabled = ocupado
        txt_start_btn.value = tr("running_btn") if ocupado else tr("start_btn")
        page.update()

    # --- FilePickers ---
    def on_pasta_result(e: ft.FilePickerResultEvent):
        if e.path:
            estado["pasta"] = Path(e.path).resolve()
            txt_pasta.value = str(estado["pasta"])
            limpar_arquivo_click(None)
            log_msg(tr("folder_set", name=estado["pasta"].name), "titulo")

    def on_arquivo_result(e: ft.FilePickerResultEvent):
        if e.files:
            arq_escolhido = next(iter(e.files))
            estado["arquivo_unico"] = Path(arq_escolhido.path).resolve()
            txt_alvo.value = tr("mode_single", name=estado["arquivo_unico"].name)
            txt_alvo.color = "#FFD54F"
            page.update()

    def on_importar_result(e: ft.FilePickerResultEvent):
        if e.files:
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
                                estado["presets"].append(
                                    {"nome": nome_limpo, "ext": str(it["ext"]).strip(), "args": str(it["args"]).strip()}
                                )
                                nomes_atuais.add(nome_limpo.lower())
                                adicionados += 1
                    if adicionados > 0:
                        salvar_presets(estado["presets"])
                        atualizar_lista_presets_ui()
                        log_msg(tr("imported", n=adicionados), "sucesso")
            except Exception as ex:
                log_msg(f"[-] {ex}", "erro")

    seletor_pasta = ft.FilePicker(on_result=on_pasta_result)
    seletor_arquivo = ft.FilePicker(on_result=on_arquivo_result)
    seletor_importar = ft.FilePicker(on_result=on_importar_result)
    def on_exportar_result(e: ft.FilePickerResultEvent):
        if e.path:
            try:
                destino = Path(e.path).resolve() / "abw_presets_exportados.json"
                destino.write_text(json.dumps(estado["presets"], indent=2, ensure_ascii=False), encoding="utf-8")
                fechar_presets_dlg()
                log_msg(tr("exported", path=destino), "sucesso")
            except Exception as ex:
                log_msg(f"[-] {ex}", "erro")

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
                allow_multiple=False,
                initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None,
            )

    def limpar_arquivo_click(e):
        if not estado["ocupado"]:
            estado["arquivo_unico"] = None
            txt_alvo.value = tr("mode_batch")
            txt_alvo.color = "#81C784"
            page.update()

    # --- Motor de Conversão FFmpeg ---
    def iniciar_conversao_click(e):
        if estado["ocupado"]:
            log_msg(tr("wait"), "aviso")
            return

        bin_ffmpeg = obter_caminho_ffmpeg()
        if not bin_ffmpeg:
            log_msg(tr("no_ffmpeg"), "erro")
            return

        preset = next(
            (p for idx, p in enumerate(estado["presets"]) if idx == int(dd_presets.value or 0)),
            next(iter(estado["presets"])),
        )
        ext_saida = preset.get("ext", ".mp4").strip()
        if not ext_saida.startswith("."):
            ext_saida = "." + ext_saida
        args_extras = shlex.split(preset.get("args", ""))

        atualizar_estado_ocupado(True)
        lista_logs.controls.clear()
        set_progresso(0, tr("preparing"))

        def worker():
            arq_cache_limpar = None
            try:
                if estado["arquivo_unico"]:
                    arq_sel = estado["arquivo_unico"]
                    arquivos = [arq_sel]
                    if "cache" in str(arq_sel) or "/data/" in str(arq_sel):
                        arq_cache_limpar = arq_sel
                        pasta_base = estado["pasta"]
                    else:
                        pasta_base = arq_sel.parent
                else:
                    pasta_base = estado["pasta"]
                    exts_ignorar = {".json", ".txt", ".py", ".apk", ".zip", ".rar", ".7z", ".log", ".ini"}
                    arquivos = sorted(
                        [a for a in pasta_base.iterdir() if a.is_file() and a.suffix.lower() not in exts_ignorar],
                        key=lambda x: x.name.lower(),
                    )

                total = len(arquivos)
                if total == 0:
                    if str(pasta_base).startswith("/storage/emulated/0") and not tem_acesso_total_arquivos():
                        log_msg(f"[-] {tr('perm_title')}", "erro")
                        page.open(dlg_perm)
                    else:
                        log_msg(tr("no_media"), "erro")
                    return

                pasta_saida = pasta_base / "conv_abw"
                pasta_saida.mkdir(parents=True, exist_ok=True)
                pasta_tmp = pasta_saida / ".abw_tmp"
                pasta_tmp.mkdir(parents=True, exist_ok=True)

                log_msg(tr("starting", n=total), "titulo")
                log_msg(f"    Preset: {preset['nome']}", "info")

                ok_count = 0
                for i, arq in enumerate(arquivos, 1):
                    nome_curto = arq.name if len(arq.name) <= 20 else arq.name[:17] + "..."
                    set_progresso((i - 1) / total, tr("converting", i=i, t=total, name=nome_curto, pct=0))

                    destino_final = pasta_saida / f"{arq.stem}{ext_saida}"
                    destino_ascii = pasta_tmp / f"out_{i}{ext_saida}"
                    link_ascii = pasta_tmp / f"in_{i}{arq.suffix}"

                    entrada_cmd = str(arq)
                    try:
                        if link_ascii.exists() or link_ascii.is_symlink():
                            link_ascii.unlink()
                        os.symlink(str(arq), str(link_ascii))
                        entrada_cmd = str(link_ascii)
                    except Exception:
                        entrada_cmd = str(arq)

                    duracao_total = obter_duracao_segundos(bin_ffmpeg, entrada_cmd)

                    cmd = [
                        bin_ffmpeg, "-y", "-v", "error", "-nostats",
                        "-progress", "pipe:1", "-i", entrada_cmd
                    ] + args_extras + [str(destino_ascii)]

                    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="ignore")
                    ultimo_pct = -1

                    if proc.stdout:
                        for linha in proc.stdout:
                            linha = linha.strip()
                            if linha.startswith("out_time_us=") and duracao_total > 0:
                                try:
                                    us = int(linha.split("=", 1).pop())
                                    seg_atual = us / 1_000_000.0
                                    pct_item = max(0, min(99, int((seg_atual / duracao_total) * 100)))
                                    if pct_item != ultimo_pct:
                                        ultimo_pct = pct_item
                                        prog_global = ((i - 1) + (pct_item / 100.0)) / total
                                        set_progresso(
                                            prog_global,
                                            tr("converting", i=i, t=total, name=nome_curto, pct=pct_item),
                                        )
                                except Exception:
                                    pass

                    proc.wait()

                    if link_ascii.exists() or link_ascii.is_symlink():
                        try:
                            link_ascii.unlink()
                        except Exception:
                            pass

                    if proc.returncode == 0 and destino_ascii.exists() and destino_ascii.stat().st_size > 0:
                        if destino_final.exists():
                            destino_final.unlink()
                        destino_ascii.replace(destino_final)
                        ok_count += 1
                    else:
                        if destino_ascii.exists():
                            destino_ascii.unlink()
                        log_msg(f"  [!] #{i}/{total}", "erro")

                    set_progresso(i / total, tr("done_item", i=i, t=total))

                try:
                    pasta_tmp.rmdir()
                except Exception:
                    pass

                if arq_cache_limpar and arq_cache_limpar.exists():
                    try:
                        arq_cache_limpar.unlink()
                    except Exception:
                        pass

                log_msg(tr("finished", ok=ok_count, t=total), "sucesso")
            except Exception as ex:
                log_msg(f"[-] {ex}", "erro")
            finally:
                atualizar_estado_ocupado(False)
                set_progresso(1.0, tr("status_ready"))

        threading.Thread(target=worker, daemon=True).start()

    # --- Gerenciador de Presets (AlertDialog) ---
    inp_nome = ft.TextField(label=tr("name_lbl"), dense=True, text_size=13)
    inp_ext = ft.TextField(label=tr("ext_lbl"), dense=True, text_size=13)
    inp_args = ft.TextField(label=tr("args_lbl"), multiline=True, min_lines=2, max_lines=3, dense=True, text_size=12)
    col_lista_presets = ft.Column(spacing=6, scroll=ft.ScrollMode.AUTO, height=160)

    def fechar_presets_dlg(e=None):
        page.close(dialog_presets)
        recarregar_dropdown(int(dd_presets.value or 0))
        page.update()

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

    def editar_preset_click(idx: int):
        p = next((item for i, item in enumerate(estado["presets"]) if i == idx), None)
        if p:
            estado["editando_idx"] = idx
            inp_nome.value, inp_ext.value, inp_args.value = p["nome"], p["ext"], p["args"]
            page.update()

    def apagar_preset_click(idx: int):
        if len(estado["presets"]) <= 1:
            return
        estado["presets"].pop(idx)
        salvar_presets(estado["presets"])
        atualizar_lista_presets_ui()

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
            idx_ed = estado["editando_idx"]
            estado["presets"] = [item if i == idx_ed else p for i, p in enumerate(estado["presets"])]
            estado["editando_idx"] = None
        else:
            estado["presets"].append(item)
        salvar_presets(estado["presets"])
        inp_nome.value = inp_ext.value = inp_args.value = ""
        atualizar_lista_presets_ui()

    def restaurar_presets_click(e):
        nomes_existentes = {p["nome"] for p in estado["presets"]}
        for p in PRESETS_INICIAIS:
            if p["nome"] not in nomes_existentes:
                estado["presets"].append(dict(p))
        salvar_presets(estado["presets"])
        atualizar_lista_presets_ui()
        log_msg(tr("restored"), "sucesso")

    def exportar_presets_click(e):
        seletor_exportar.get_directory_path(
            initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None
        )

    def importar_presets_click(e):
        seletor_importar.pick_files(
            allow_multiple=False,
            allowed_extensions=["json"],
            initial_directory=str(estado["pasta"]) if estado["pasta"].exists() else None,
        )


    btn_salvar_preset = ft.ElevatedButton(text=tr("save"), icon=ft.Icons.SAVE, on_click=salvar_preset_click)
    btn_restaurar = ft.TextButton(text=tr("restore"), icon=ft.Icons.RESTORE, on_click=restaurar_presets_click)
    btn_importar = ft.TextButton(text=tr("import"), icon=ft.Icons.UPLOAD_FILE, on_click=importar_presets_click)
    btn_exportar = ft.TextButton(text=tr("export"), icon=ft.Icons.DOWNLOAD, on_click=exportar_presets_click)
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
        if estado["ocupado"]:
            return
        estado["presets"] = carregar_presets()
        estado["editando_idx"] = None
        inp_nome.value = inp_ext.value = inp_args.value = ""
        atualizar_lista_presets_ui()
        page.open(dialog_presets)

    # --- Botões da Tela Principal ---
    btn_pasta = ft.ElevatedButton(
        text=tr("folder_btn"),
        icon=ft.Icons.FOLDER_OPEN,
        style=ft.ButtonStyle(
            bgcolor="#2B3240",
            color="#FFFFFF",
            shape=ft.RoundedRectangleBorder(radius=8),
        ),
        on_click=mudar_pasta_click,
    )
    btn_pick = ft.ElevatedButton(
        text=tr("pick_file"),
        icon=ft.Icons.AUDIO_FILE,
        style=ft.ButtonStyle(
            bgcolor="#232834",
            color="#E0E0E0",
            shape=ft.RoundedRectangleBorder(radius=8),
        ),
        expand=True,
        on_click=escolher_arquivo_click,
    )
    btn_manage = ft.IconButton(
        icon=ft.Icons.TUNE,
        icon_color="#CE93D8",
        tooltip=tr("manage_tip"),
        on_click=abrir_presets_click,
    )
    txt_start_btn = ft.Text(tr("start_btn"), size=14, weight=ft.FontWeight.BOLD, color="#FFFFFF")

    def alternar_idioma_click(e):
        estado["lang"] = "en" if estado["lang"] == "pt" else "pt"
        salvar_config({"lang": estado["lang"]})

        btn_lang.text = estado["lang"].upper()
        txt_subtitulo.value = tr("subtitle")
        txt_lbl_pasta.value = tr("work_folder")
        btn_pasta.text = tr("folder_btn")
        btn_pick.text = tr("pick_file")
        dd_presets.label = tr("preset_lbl")
        btn_manage.tooltip = tr("manage_tip")
        txt_start_btn.value = tr("running_btn") if estado["ocupado"] else tr("start_btn")
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

        txt_alvo.value = (
            tr("mode_single", name=estado["arquivo_unico"].name)
            if estado["arquivo_unico"]
            else tr("mode_batch")
        )
        page.update()

    btn_lang = ft.TextButton(
        text=estado["lang"].upper(),
        style=ft.ButtonStyle(color="#CE93D8"),
        on_click=alternar_idioma_click,
    )

    # --- Cartões e Layout ---
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
                    [
                        txt_alvo,
                        ft.IconButton(icon=ft.Icons.CLEAR, icon_size=18, on_click=limpar_arquivo_click),
                    ],
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
            [ft.Icon(ft.Icons.PLAY_ARROW_ROUNDED, size=22, color="#FFFFFF"), txt_start_btn],
            alignment=ft.MainAxisAlignment.CENTER,
        ),
        style=ft.ButtonStyle(
            bgcolor="#7B1FA2",
            shape=ft.RoundedRectangleBorder(radius=10),
            padding=ft.padding.symmetric(vertical=14),
        ),
        on_click=iniciar_conversao_click,
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
                ft.Text("ABW-Converter", size=18, weight=ft.FontWeight.BOLD, color="#CE93D8"),
                ft.Row([txt_subtitulo, btn_lang], spacing=4),
            ],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
        ),
        card_pasta,
        card_alvo,
        card_preset,
        ft.Row([ft.Container(content=btn_converter, expand=True)]),
        ft.Column([txt_status, barra_progresso], spacing=4),
        card_logs,
    )

    # --- Verificação de Permissão Android ---
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

    def verificar_permissao_android():
        if not tem_acesso_total_arquivos():
            page.open(dlg_perm)

    verificar_permissao_android()
    log_msg(f"[✓] {tr('ready')}", "sucesso")


if __name__ == "__main__":
    ft.app(target=main)
