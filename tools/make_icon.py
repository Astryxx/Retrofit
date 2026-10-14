"""Draws Retrofit's icon (no copied art): an isometric block, half old-style, half new, joined by an arrow.

Writes branding/icon-400.png (CurseForge/Modrinth) and forge/src/main/resources/retrofit_logo.png
(Forge's mod list). Usage: python -I tools/make_icon.py
"""
import pathlib
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
SIZE = 400


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def draw(size):
    scale = size / SIZE
    image = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    # Rounded dark background with a vertical gradient.
    background = Image.new('RGBA', (size, size))
    bd = ImageDraw.Draw(background)
    for y in range(size):
        bd.line([(0, y), (size, y)], fill=lerp((34, 40, 58, 255), (18, 20, 30, 255), y / size))
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(64 * scale), fill=255)
    image.paste(background, (0, 0), mask)

    # Isometric block: top, left and right faces, split into a pixel grid.
    cx, cy, edge = size / 2, size * 0.50, size * 0.30
    top = [(cx, cy - edge * 0.62), (cx + edge, cy - edge * 0.05), (cx, cy + edge * 0.52), (cx - edge, cy - edge * 0.05)]
    left = [(cx - edge, cy - edge * 0.05), (cx, cy + edge * 0.52), (cx, cy + edge * 1.6), (cx - edge, cy + edge * 1.03)]
    right = [(cx + edge, cy - edge * 0.05), (cx, cy + edge * 0.52), (cx, cy + edge * 1.6), (cx + edge, cy + edge * 1.03)]
    shift = -edge * 0.55
    top = [(x, y + shift) for x, y in top]
    left = [(x, y + shift) for x, y in left]
    right = [(x, y + shift) for x, y in right]

    def face(points, colors, cells=8):
        # Fill a parallelogram face with a cells x cells grid of slightly varied colors.
        (ax, ay), (bx, by), (_, _), (dx, dy) = points[0], points[1], points[2], points[3]
        ux, uy = (bx - ax) / cells, (by - ay) / cells
        vx, vy = (dx - ax) / cells, (dy - ay) / cells
        for i in range(cells):
            for j in range(cells):
                ox, oy = ax + ux * i + vx * j, ay + uy * i + vy * j
                cell = [(ox, oy), (ox + ux, oy + uy), (ox + ux + vx, oy + uy + vy), (ox + vx, oy + vy)]
                d.polygon(cell, fill=colors[(i * 7 + j * 3) % len(colors)])

    grass = [(96, 160, 64, 255), (88, 150, 58, 255), (104, 170, 70, 255), (80, 140, 52, 255)]
    dirt_old = [(134, 96, 67, 255), (121, 85, 58, 255), (150, 108, 74, 255), (110, 78, 54, 255)]
    stone_new = [(126, 130, 140, 255), (114, 118, 128, 255), (138, 142, 150, 255), (104, 108, 118, 255)]
    face([top[0], top[1], top[2], top[3]], grass)
    face([left[0], left[1], left[2], left[3]], [lerp(c, (0, 0, 0, 255), 0.18) for c in dirt_old])
    face([right[1], right[0], right[3], right[2]], [lerp(c, (0, 0, 0, 255), 0.05) for c in stone_new])
    for poly in (top, left, right):
        d.line(poly + [poly[0]], fill=(10, 12, 18, 255), width=max(1, int(4 * scale)))

    # A curved arrow under the block, left (old) to right (new).
    arrow = (255, 196, 64, 255)
    box = [size * 0.18, size * 0.56, size * 0.82, size * 0.92]
    d.arc(box, start=20, end=160, fill=arrow, width=max(2, int(14 * scale)))
    tip_x, tip_y = size * 0.80, size * 0.80
    d.polygon([(tip_x + 10 * scale, tip_y - 40 * scale), (tip_x + 26 * scale, tip_y + 8 * scale), (tip_x - 26 * scale, tip_y - 4 * scale)], fill=arrow)
    return image


def main():
    (ROOT / 'branding').mkdir(exist_ok=True)
    draw(SIZE).save(ROOT / 'branding' / 'icon-400.png')
    draw(SIZE).resize((128, 128), Image.LANCZOS).save(ROOT / 'forge' / 'src' / 'main' / 'resources' / 'retrofit_logo.png')
    print('wrote branding/icon-400.png and forge/src/main/resources/retrofit_logo.png')


if __name__ == '__main__':
    main()
