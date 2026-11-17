#!/usr/bin/env python3
"""Render the dark-gold treble-clef launcher icon from the OFL Bravura font."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = Path("/tmp/fonts/Bravura.otf")
CLEF = "\U0001D11E"
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"


def gold_gradient(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height))
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            t = (0.72 * y / max(1, height - 1)) + (0.28 * x / max(1, width - 1))
            if t < 0.42:
                u = t / 0.42
                color = lerp((246, 232, 186), (214, 176, 92), u)
            else:
                u = (t - 0.42) / 0.58
                color = lerp((214, 176, 92), (122, 84, 36), u)
            pixels[x, y] = color
    return image


def background(size: int) -> Image.Image:
    image = Image.new("RGB", (size, size))
    pixels = image.load()
    cx = cy = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            vertical = y / max(1, size - 1)
            base = lerp((42, 32, 24), (12, 10, 9), vertical)
            dx = (x - cx) / size
            dy = (y - cy) / size
            glow = max(0.0, 1 - (dx * dx + dy * dy) * 5.5)
            warm = lerp(base, (68, 50, 32), glow * 0.55)
            pixels[x, y] = warm
    return image


def clef_mask(size: int) -> Image.Image:
    canvas = size * 4
    font = ImageFont.truetype(str(FONT), int(size * 1.35))
    mask = Image.new("L", (canvas, canvas), 0)
    draw = ImageDraw.Draw(mask)
    draw.text((canvas / 2, canvas / 2), CLEF, font=font, fill=255, anchor="mm")
    bbox = mask.getbbox()
    glyph = mask.crop(bbox)
    # A hair of weight so the clef stays readable after the launcher mask.
    glyph = glyph.filter(ImageFilter.MaxFilter(3))
    target_h = int(size * 0.70)
    target_w = max(1, int(glyph.width * target_h / glyph.height))
    glyph = glyph.resize((target_w, target_h), Image.Resampling.LANCZOS)
    layer = Image.new("L", (size, size), 0)
    layer.paste(glyph, ((size - target_w) // 2, (size - target_h) // 2))
    return layer


def compose(size: int) -> tuple[Image.Image, Image.Image, Image.Image]:
    mask = clef_mask(size)
    paint = gold_gradient(size, size)
    foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    foreground.paste(paint, (0, 0), mask)
    back = background(size).convert("RGBA")
    icon = back.copy()
    icon.alpha_composite(foreground)
    return foreground, back, icon


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def main() -> None:
    foreground, back, icon = compose(432)
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    foreground.save(nodpi / "ic_launcher_foreground.png")
    back.convert("RGB").save(nodpi / "ic_launcher_background.png")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in densities.items():
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        legacy = icon.resize((size, size), Image.Resampling.LANCZOS).convert("RGB")
        legacy.save(out / "ic_launcher.png")
        legacy.save(out / "ic_launcher_round.png")

    preview = icon.resize((512, 512), Image.Resampling.LANCZOS)
    preview_path = Path("/opt/cursor/artifacts/icon_treble_clef_preview.png")
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(preview_path)
    icon.resize((192, 192), Image.Resampling.LANCZOS).save("/tmp/icon_192.png")
    icon.resize((48, 48), Image.Resampling.LANCZOS).resize((192, 192), Image.Resampling.NEAREST).save("/tmp/icon_48.png")
    print(preview_path)


if __name__ == "__main__":
    main()
