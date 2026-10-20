from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

SIZE = 512
out = Path(__file__).with_name("orange.ico")
img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))

glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((52, 52, 460, 460), fill=(255, 104, 14, 110))
img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(42)))

draw = ImageDraw.Draw(img)
draw.rounded_rectangle((24, 24, 488, 488), radius=112, fill=(10, 12, 17, 255), outline=(57, 64, 77, 255), width=5)
draw.ellipse((105, 115, 407, 417), fill=(190, 60, 7, 255))
draw.ellipse((94, 99, 397, 405), fill=(255, 119, 17, 255))
draw.ellipse((119, 116, 378, 379), fill=(255, 145, 27, 255))
draw.ellipse((151, 133, 325, 305), fill=(255, 169, 57, 95))
draw.ellipse((141, 128, 206, 193), fill=(255, 222, 151, 180))

for x, y, r in [(240,160,3),(293,193,3),(183,226,3),(335,252,3),(235,290,3),(175,315,2),(301,326,3),(261,359,2),(350,300,2),(217,205,2)]:
    draw.ellipse((x-r,y-r,x+r,y+r), fill=(195,77,9,135))

draw.rounded_rectangle((246, 88, 270, 139), radius=10, fill=(99, 61, 26, 255))
draw.ellipse((258, 63, 365, 126), fill=(61, 173, 74, 255))
draw.ellipse((271, 73, 346, 115), fill=(91, 206, 87, 255))
draw.line((270, 111, 346, 82), fill=(30, 116, 51, 255), width=7)

highlight = Image.new("RGBA", img.size, (0, 0, 0, 0))
ImageDraw.Draw(highlight).ellipse((130, 134, 194, 220), fill=(255, 255, 255, 75))
img.alpha_composite(highlight.filter(ImageFilter.GaussianBlur(12)))

img.save(out, format="ICO", sizes=[(256,256),(128,128),(64,64),(48,48),(32,32),(16,16)])
print(f"Created {out}")
