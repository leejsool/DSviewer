import sys, struct
import os
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hwpdump.py'), encoding='utf-8').read().split("path = sys.argv[1]")[0])
import zlib
st = read_cfb(sys.argv[1])
def get(n):
    return zlib.decompress(st[n](), -15)
mode = sys.argv[2]
if mode == 'eq':
    n = 0
    for tag, lvl, rec in records(get('BodyText/Section0')):
        if tag == 0x58:
            l = struct.unpack_from('<H', rec, 4)[0]
            s = rec[6:6+l*2].decode('utf-16le', 'replace')
            n += 1
            if n <= int(sys.argv[3]):
                print(repr(s))
elif mode == 'faces':
    for tag, lvl, rec in records(get('DocInfo')):
        if tag == 0x13:
            l = struct.unpack_from('<H', rec, 1)[0]
            print(rec[3:3+l*2].decode('utf-16le'))
elif mode == 'ctx':
    # print records around the first occurrence of a ctrl id
    want = sys.argv[3]
    recs = list(records(get('BodyText/Section0')))
    idx = [i for i, (t, l, r) in enumerate(recs) if t == 0x47 and ctrlid(r) == want]
    k = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    i0 = idx[k]
    base = recs[i0][1]
    for t, l, r in recs[i0:i0+int(sys.argv[5]) if len(sys.argv) > 5 else i0+30]:
        name = TAGS.get(t, hex(t))
        extra = r[:48].hex()
        if name == 'PARA_TEXT': extra = repr(r.decode('utf-16le','replace'))[:120]
        if name == 'CTRL_HEADER': extra = ctrlid(r) + ' ' + r[4:48].hex()
        if name == 'PARA_LINE_SEG':
            extra = str([struct.unpack_from('<8iI', r, q) for q in range(0, len(r), 36)][:3])
        if t == 0x58:
            ll = struct.unpack_from('<H', r, 4)[0]; extra = repr(r[6:6+ll*2].decode('utf-16le'))
        print('  ' * (l - base) + name, len(r), extra)
