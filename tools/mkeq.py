import zipfile, re, html

src = r'C:\gradle\tmp\t1.hwpx'
dst = r'C:\gradle\tmp\eq.hwpx'
eqs = [
    r'x = {-b +- sqrt {b^2 - 4ac}} over {2a}',
    r'sum from {k=1} to n k^2 = {n(n+1)(2n+1)} over 6',
    r'int _0 ^{pi} sin x ~dx = 2',
    r'lim from {x rarrow 0} {sin x} over x = 1',
    r'A = left ( matrix{a & b # c & d} right ) , ~ E = pmatrix{1 & 0 # 0 & 1}',
    r'f(x) = cases{x^2 & (x >= 0) # -x & (x < 0)}',
    r'root 3 of {x+1} , ~ bar {AB} , ~ vec a cdot vec b = LEFT | vec a RIGHT | LEFT | vec b RIGHT | cos theta',
    r'alpha + beta = gamma , ~ DELTA ABC , ~ A subset B , ~ therefore ~ x in R',
    r'"넓이" ~ S = 1 over 2 ab sin C',
    r'a_n = a_1 r^{n-1} , ~ e^{i pi} + 1 = 0 , ~ log _2 8 = 3',
    r'{partial f} over {partial x} , ~ x^2 + y^2 <= r^2 , ~ 3 times 4 div 2 = 6',
]

z = zipfile.ZipFile(src)
sec = z.read('Contents/section0.xml').decode('utf-8')
head = sec[:sec.index('<hp:p ')]
secpr = re.search(r'<hp:secPr.*?</hp:secPr>', sec, re.S).group(0)
pagenum = '<hp:ctrl><hp:pageNum pos="BOTTOM_CENTER" formatType="DIGIT" sideChar="-"/></hp:ctrl>'

def para(inner, pp=0):
    return f'<hp:p id="0" paraPrIDRef="{pp}" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0">{inner}</hp:p>'

body = para(f'<hp:run charPrIDRef="21">{secpr}{pagenum}<hp:t>□ 수식 표시 테스트</hp:t></hp:run>')
for n, e in enumerate(eqs, 1):
    eqxml = ('<hp:equation id="%d" zOrder="0" numberingType="EQUATION" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" '
             'lock="0" dropcapstyle="None" version="Equation Version 60" baseLine="85" textColor="#000000" baseUnit="1400" '
             'lineMode="CHAR" font="HancomEQN"><hp:sz width="0" widthRelTo="ABSOLUTE" height="0" heightRelTo="ABSOLUTE" protect="0"/>'
             '<hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" '
             'horzRelTo="PARA" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>'
             '<hp:outMargin left="56" right="56" top="0" bottom="0"/><hp:shapeComment>수식입니다.</hp:shapeComment>'
             '<hp:script>%s</hp:script></hp:equation>') % (1000 + n, html.escape(e, quote=False))
    body += para(f'<hp:run charPrIDRef="14"><hp:t>({n}) </hp:t>{eqxml}<hp:t/></hp:run>', 32)
    body += para('<hp:run charPrIDRef="14"><hp:t/></hp:run>', 32)
newsec = head + body + '</hs:sec>'

out = zipfile.ZipFile(dst, 'w')
for item in z.infolist():
    data = z.read(item.filename)
    if item.filename == 'Contents/section0.xml':
        data = newsec.encode('utf-8')
    if item.filename.startswith('BinData/'):
        continue
    ct = zipfile.ZIP_STORED if item.filename == 'mimetype' else zipfile.ZIP_DEFLATED
    out.writestr(item.filename, data, compress_type=ct)
out.close()
print('ok', len(newsec))
