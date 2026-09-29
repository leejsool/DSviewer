import pymupdf as fitz, sys
a = fitz.open(sys.argv[1]); b = fitz.open(sys.argv[2]); out = sys.argv[3]
start = int(sys.argv[4]) if len(sys.argv) > 4 else 1
count = int(sys.argv[5]) if len(sys.argv) > 5 else 11
z = 0.32
cols = []
tw = int(595 * z); th = int(842 * z)
W = (tw * 2 + 14) * count
H = th + 4
sheet = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, W, H), False)
sheet.clear_with(120)
for k in range(count):
    i = start - 1 + k
    x = k * (tw * 2 + 14)
    for j, d in enumerate((a, b)):
        if i < d.page_count:
            pm = d[i].get_pixmap(matrix=fitz.Matrix(z, z))
            if pm.alpha or pm.n != 3:
                pm = fitz.Pixmap(fitz.csRGB, pm)
            pm.set_origin(x + j * (tw + 3), 2)
            sheet.copy(pm, pm.irect)
sheet.save(out)
print(a.page_count, b.page_count)
