from pathlib import Path
from PIL import Image, ImageDraw

# Paleta extraída do seu ícone original
BG_COLOR = (20, 24, 33, 255)         # #141821 (Fundo escuro)
CARD_FILL = (35, 41, 54, 255)        # #232936 (Interior do quadrado)
CARD_BORDER = (171, 71, 188, 255)    # #AB47BC (Borda roxa)
BAR_COLOR = (216, 150, 230, 255)     # #D896E6 (Barras lilás)
PLAY_COLOR = (77, 208, 225, 255)     # #4DD0E1 (Triângulo ciano)


def desenhar_foreground(base_size: int = 1024, scale: int = 4) -> Image.Image:
    s = base_size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    cx, cy = s // 2, s // 2
    box_size = int(s * 0.54)
    half = box_size // 2
    radius = int(box_size * 0.21)
    border_w = max(2, int(s * 0.022))

    # Quadrado arredondado com borda roxa
    draw.rounded_rectangle(
        [cx - half, cy - half, cx + half, cy + half],
        radius=radius,
        fill=CARD_FILL,
        outline=CARD_BORDER,
        width=border_w,
    )

    # 4 barras verticais (onda sonora)
    bar_w = int(s * 0.032)
    gap = int(s * 0.024)
    alturas = [0.11, 0.21, 0.30, 0.21]  # proporção em relação a s
    start_x = cx - int(s * 0.145)

    for idx, h_ratio in enumerate(alturas):
        bx = start_x + idx * (bar_w + gap)
        bh = int(s * h_ratio)
        by0 = cy - bh // 2
        by1 = cy + bh // 2
        draw.rounded_rectangle(
            [bx, by0, bx + bar_w, by1],
            radius=bar_w // 2,
            fill=BAR_COLOR,
        )

    # Triângulo Play ciano à direita das barras
    tri_x0 = start_x + 4 * (bar_w + gap) + int(s * 0.008)
    tri_w = int(s * 0.115)
    tri_h = int(s * 0.175)
    pontos_play = [
        (tri_x0, cy - tri_h // 2),
        (tri_x0, cy + tri_h // 2),
        (tri_x0 + tri_w, cy),
    ]
    draw.polygon(pontos_play, fill=PLAY_COLOR)

    return img.resize((base_size, base_size), Image.Resampling.LANCZOS)


def gerar_todos_icones():
    res_dir = Path("app/src/main/res")
    fg_master = desenhar_foreground(1024, scale=4)
    bg_master = Image.new("RGBA", (1024, 1024), BG_COLOR)
    full_master = Image.alpha_composite(bg_master, fg_master)

    # 1. Ícones padrão e redondos (48dp base)
    densidades_launcher = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for pasta, px in densidades_launcher.items():
        d = res_dir / pasta
        d.mkdir(parents=True, exist_ok=True)
        icone = full_master.resize((px, px), Image.Resampling.LANCZOS)
        icone.save(d / "ic_launcher.png", "PNG")
        icone.save(d / "ic_launcher_round.png", "PNG")

    # 2. Camadas de Ícone Adaptativo Android 8+ (108dp base)
    densidades_adaptativas = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }
    for pasta, px in densidades_adaptativas.items():
        d = res_dir / pasta
        d.mkdir(parents=True, exist_ok=True)
        bg_master.resize((px, px), Image.Resampling.LANCZOS).save(d / "ic_launcher_background.png", "PNG")
        fg_master.resize((px, px), Image.Resampling.LANCZOS).save(d / "ic_launcher_foreground.png", "PNG")

    # 3. XMLs adaptativos (mipmap-anydpi-v26)
    anydpi = res_dir / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    ns = bytes.fromhex("687474703a2f2f736368656d61732e616e64726f69642e636f6d2f61706b2f7265732f616e64726f6964").decode()
    xml_adaptativo = f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="{ns}">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(xml_adaptativo, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(xml_adaptativo, encoding="utf-8")
    print("Icones gerados com sucesso!")

if __name__ == "__main__":
    gerar_todos_icones()
