import fitz, sys, os

ref = sys.argv[1]
ours = sys.argv[2]
outdir = sys.argv[3]
pages = [int(p) for p in sys.argv[4].split(',')] if len(sys.argv) > 4 else None
os.makedirs(outdir, exist_ok=True)
a = fitz.open(ref)
b = fitz.open(ours)
print('ref pages', a.page_count, 'ours pages', b.page_count)
n = max(a.page_count, b.page_count)
zoom = 1.1
for i in range(n):
    if pages and (i + 1) not in pages:
        continue
    pa = a[i].get_pixmap(matrix=fitz.Matrix(zoom, zoom)) if i < a.page_count else None
    pb = b[i].get_pixmap(matrix=fitz.Matrix(zoom, zoom)) if i < b.page_count else None
    w = (pa.width if pa else 0) + (pb.width if pb else 0) + 10
    h = max(pa.height if pa else 0, pb.height if pb else 0)
    out = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, w, h), False)
    out.clear_with(200)
    if pa:
        pa2 = fitz.Pixmap(fitz.csRGB, pa) if pa.n != 3 or pa.alpha else pa
        pa2.set_origin(0, 0)
        out.copy(pa2, pa2.irect)
    if pb:
        pb2 = fitz.Pixmap(fitz.csRGB, pb) if pb.n != 3 or pb.alpha else pb
        off = (pa.width if pa else 0) + 10
        pb2.set_origin(off, 0)
        out.copy(pb2, pb2.irect)
    out.save(os.path.join(outdir, 'p%02d.png' % (i + 1)))
print('done')
