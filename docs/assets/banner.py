import sys
C, COLS, ROWS = 20, 48, 14
W, H = C * COLS, C * ROWS
FONT = {
 "H": ["10001","10001","10001","11111","10001","10001","10001"],
 "Y": ["10001","10001","01010","00100","00100","00100","00100"],
 "P": ["11110","10001","10001","11110","10000","10000","10000"],
 "A": ["01110","10001","10001","11111","10001","10001","10001"],
 "X": ["10001","10001","01010","00100","01010","10001","10001"],
 "E": ["11111","10000","10000","11110","10000","10000","11111"],
 "L": ["10000","10000","10000","10000","10000","10000","11111"],
}
BG, HEAD, LINE, LABEL = "#0b1220", "#111a2e", "#1c2740", "#4b5b7a"
WHITE, WHITE_HI = "#e6edf7", "#ffffff"
GREEN, GREEN_HI, GREEN_LO = "#22c55e", "#86efac", "#15803d"

def col_name(i):
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s

o = []
o.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="Hypaxel: streaming XLSX writers for .NET, Rust, Python, Node.js, Go, Java and PHP" shape-rendering="crispEdges">')
o.append("""<style>
.l{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10px;fill:%s;text-anchor:middle}
.t{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:15px;font-weight:600;fill:#9fb0c8}
.s{animation:s 4.8s steps(1,end) infinite;opacity:0}
.c{animation:c 1.2s steps(1,end) infinite}
@keyframes s{0%%{opacity:0}4%%{opacity:1}70%%{opacity:1}100%%{opacity:0}}
@keyframes c{50%%{opacity:0}}
@media (prefers-reduced-motion:reduce){.s{animation:none;opacity:1}.c{animation:none}}
</style>""" % LABEL)
o.append(f'<rect width="{W}" height="{H}" fill="{BG}"/>')
o.append(f'<rect width="{W}" height="{C}" fill="{HEAD}"/><rect width="{C}" height="{H}" fill="{HEAD}"/>')
for x in range(1, COLS):
    o.append(f'<rect x="{x*C}" y="0" width="1" height="{H}" fill="{LINE}"/>')
for y in range(1, ROWS):
    o.append(f'<rect x="0" y="{y*C}" width="{W}" height="1" fill="{LINE}"/>')
for x in range(1, COLS):
    o.append(f'<text class="l" x="{x*C+C/2+0.5}" y="14">{col_name(x-1)}</text>')
for y in range(1, ROWS):
    o.append(f'<text class="l" x="{C/2+0.5}" y="{y*C+14}">{y}</text>')

def cell(cx, cy, fill, extra=""):
    o.append(f'<rect x="{cx*C+1}" y="{cy*C+1}" width="{C-1}" height="{C-1}" fill="{fill}"{extra}/>')

x0, y0 = 4, 2
for i, ch in enumerate("HYPAXEL"):
    green = i >= 4
    for r, row in enumerate(FONT[ch]):
        for c, bit in enumerate(row):
            if bit == "1":
                if green:
                    fill = GREEN_HI if r == 0 else GREEN
                else:
                    fill = WHITE_HI if r == 0 else WHITE
                cell(x0 + i * 6 + c, y0 + r, fill)

sy = 10
n = 41
for i in range(n):
    shade = [GREEN_LO, GREEN, GREEN, GREEN_HI][(i * 7) % 4]
    cell(x0 + i, sy, shade, f' class="s" style="animation-delay:{i*0.07:.2f}s"')
cx = x0 + n
o.append(f'<rect class="c" x="{cx*C}" y="{sy*C}" width="{C+1}" height="{C+1}" fill="none" stroke="{GREEN_HI}" stroke-width="2"/>')

ty = 12
o.append(f'<rect x="{x0*C+1}" y="{ty*C+1}" width="{n*C-1}" height="{C-1}" fill="{BG}"/>')
o.append(f'<text class="t" x="{x0*C+6}" y="{ty*C+15}">=STREAM("xlsx", ".NET", "Rust", "Python", "Node.js", "Go", "Java", "PHP")</text>')
o.append("</svg>")
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(o) + "\n")
