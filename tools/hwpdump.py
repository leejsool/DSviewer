import struct, sys, zlib, glob

def read_cfb(path):
    d = open(path, 'rb').read()
    ss = 1 << struct.unpack_from('<H', d, 0x1E)[0]
    mss = 1 << struct.unpack_from('<H', d, 0x20)[0]
    nfat = struct.unpack_from('<I', d, 0x2C)[0]
    dir0 = struct.unpack_from('<I', d, 0x30)[0]
    cutoff = struct.unpack_from('<I', d, 0x38)[0]
    minifat0 = struct.unpack_from('<I', d, 0x3C)[0]
    difat0 = struct.unpack_from('<I', d, 0x44)[0]
    difat = list(struct.unpack_from('<109I', d, 0x4C))
    s = difat0
    while s < 0xFFFFFFFA:
        off = 512 + s * ss
        vals = struct.unpack_from('<%dI' % (ss // 4), d, off)
        difat += vals[:-1]; s = vals[-1]
    fat = []
    for fs in difat[:nfat]:
        fat += struct.unpack_from('<%dI' % (ss // 4), d, 512 + fs * ss)
    def chain(start):
        out = []; s = start
        while s < 0xFFFFFFFA and len(out) < 1000000:
            out.append(s); s = fat[s]
        return out
    def read_chain(start):
        return b''.join(d[512 + s * ss: 512 + (s + 1) * ss] for s in chain(start))
    dirdata = read_chain(dir0)
    entries = []
    for i in range(len(dirdata) // 128):
        e = dirdata[i*128:(i+1)*128]
        nl = struct.unpack_from('<H', e, 64)[0]
        name = e[:max(0, nl-2)].decode('utf-16le')
        typ = e[66]
        left, right, child = struct.unpack_from('<iii', e, 68)
        start = struct.unpack_from('<I', e, 116)[0]
        size = struct.unpack_from('<Q', e, 120)[0]
        entries.append(dict(name=name, type=typ, left=left, right=right, child=child, start=start, size=size))
    root = entries[0]
    ministream = read_chain(root['start'])
    minifat = []
    if minifat0 < 0xFFFFFFFA:
        mf = read_chain(minifat0)
        minifat = list(struct.unpack('<%dI' % (len(mf)//4), mf))
    def read_stream(e):
        if e['size'] < cutoff:
            out = []; s = e['start']
            while s < 0xFFFFFFFA:
                out.append(ministream[s*mss:(s+1)*mss]); s = minifat[s]
            return b''.join(out)[:e['size']]
        return read_chain(e['start'])[:e['size']]
    paths = {}
    def walk(idx, prefix):
        if idx < 0: return
        e = entries[idx]
        walk(e['left'], prefix)
        p = prefix + e['name']
        if e['type'] == 2: paths[p] = e
        if e['type'] in (1, 5) and e['child'] >= 0:
            walk(e['child'], '' if e['type'] == 5 else p + '/')
        walk(e['right'], prefix)
    walk(0, '')
    return {p: (lambda e=e: read_stream(e)) for p, e in paths.items()}

TAGS = {0x10:'DOC_PROPS',0x11:'ID_MAPPINGS',0x12:'BIN_DATA',0x13:'FACE_NAME',0x14:'BORDER_FILL',0x15:'CHAR_SHAPE',0x16:'TAB_DEF',0x17:'NUMBERING',0x18:'BULLET',0x19:'PARA_SHAPE',0x1A:'STYLE',
0x42:'PARA_HEADER',0x43:'PARA_TEXT',0x44:'PARA_CHAR_SHAPE',0x45:'PARA_LINE_SEG',0x46:'PARA_RANGE_TAG',0x47:'CTRL_HEADER',0x48:'LIST_HEADER',0x49:'PAGE_DEF',0x4A:'FOOTNOTE_SHAPE',0x4B:'PAGE_BORDER_FILL',
0x4C:'SHAPE_COMPONENT',0x4D:'TABLE',0x4E:'SC_LINE',0x4F:'SC_RECT',0x50:'SC_ELLIPSE',0x51:'SC_ARC',0x52:'SC_POLYGON',0x53:'SC_CURVE',0x54:'SC_OLE',0x55:'SC_PICTURE',0x56:'SC_CONTAINER',0x57:'CTRL_DATA',0x58:'EQEDIT'}

def records(data):
    i = 0
    while i + 4 <= len(data):
        h = struct.unpack_from('<I', data, i)[0]; i += 4
        tag = h & 0x3FF; lvl = (h >> 10) & 0x3FF; size = (h >> 20) & 0xFFF
        if size == 0xFFF:
            size = struct.unpack_from('<I', data, i)[0]; i += 4
        yield tag, lvl, data[i:i+size]; i += size

def ctrlid(b):
    v = struct.unpack_from('<I', b, 0)[0]
    return bytes([(v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255]).decode('latin1')

path = sys.argv[1]
st = read_cfb(path)
print(sorted(st.keys()))
fh = st['FileHeader']()
flags = struct.unpack_from('<I', fh, 36)[0]
ver = struct.unpack_from('<I', fh, 32)[0]
print('version %08X flags %08X' % (ver, flags))
comp = flags & 1
def get(name):
    b = st[name]()
    return zlib.decompress(b, -15) if comp else b
mode = sys.argv[2] if len(sys.argv) > 2 else 'body'
if mode == 'docinfo':
    for tag, lvl, rec in records(get('DocInfo')):
        print(lvl, TAGS.get(tag, hex(tag)), len(rec), rec[:80].hex())
else:
    for tag, lvl, rec in records(get('BodyText/Section0')):
        name = TAGS.get(tag, hex(tag))
        extra = ''
        if name == 'PARA_TEXT':
            extra = repr(rec.decode('utf-16le', 'replace'))[:150]
        elif name == 'CTRL_HEADER':
            extra = ctrlid(rec) + ' ' + rec[4:60].hex()
        elif name == 'PARA_LINE_SEG':
            segs = [struct.unpack_from('<8iI', rec, k) for k in range(0, len(rec), 36)]
            extra = str(segs[:3])
        elif name == 'PARA_HEADER':
            extra = str(struct.unpack_from('<IIHBBHHH', rec, 0))
        else:
            extra = rec[:64].hex()
        print('  ' * lvl + name, len(rec), extra)
