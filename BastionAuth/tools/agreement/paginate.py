"""Turns the agreement source (agreement-<rev>.md) into pre-paginated book pages.

python paginate.py 1.4
reads  tools/agreement/agreement-1.4.md
writes src/main/resources/bastionauth/agreement-1.4.txt

A written-book page is 114 px wide and 14 lines tall, and anything past that is
silently clipped, so the pagination happens here against the real glyph widths
rather than being left to the client.

Source format: the text before the first "## " is the preamble; every "## N. Title"
starts section N; "N.M. Title" lines are subheadings; "* " lines are list items.
"""
import io, os, re, sys

sys.stdout.reconfigure(encoding='utf-8')

LINES_PER_PAGE = 13          # 14 fit, one kept spare for safety
WIDTH_PX = 112               # 114 minus a small margin
MAX_PAGES_PER_BOOK = 100

# Default-font advance widths. Almost every Cyrillic glyph is 5 px + 1 px spacing.
NARROW = {'i': 2, 'l': 3, '!': 2, '.': 2, ',': 2, ':': 2, ';': 2, "'": 2, '|': 2,
          '`': 3, 'j': 3, 't': 4, 'f': 5, 'k': 5, ' ': 4, '(': 5, ')': 5, '[': 4, ']': 4}

VOLUMES = [
    ("Том I. Допуск и правила", lambda n: n <= 5),
    ("Том II. Санкции и данные", lambda n: 6 <= n <= 9),
    ("Том III. Системы и связь", lambda n: n >= 10),
]


def cw(ch):
    return NARROW.get(ch, 6)


def width(s):
    return sum(cw(c) for c in re.sub('§.', '', s))


def wrap(text, limit=WIDTH_PX):
    lines = []
    cur, curw = '', 0
    for word in text.split(' '):
        if not word:
            continue
        ww = width(word)
        space = cw(' ') if cur else 0
        if cur and curw + space + ww > limit:
            lines.append(cur)
            cur, curw = word, ww
        else:
            cur = (cur + ' ' + word) if cur else word
            curw += space + ww
        while curw > limit:                       # a single word longer than a line
            acc, accw = '', 0
            for c in cur:
                if accw + cw(c) > limit:
                    break
                acc += c
                accw += cw(c)
            lines.append(acc)
            cur = cur[len(acc):]
            curw = width(cur)
    if cur:
        lines.append(cur)
    return lines


def render(body):
    """Body text -> book lines: paragraphs separated by a blank line, list items kept tight."""
    out = []
    for para in [p.strip() for p in body.split('\n')]:
        if not para:
            if out and out[-1] != '':
                out.append('')
            continue
        if para.startswith('* '):
            out.extend(wrap('• ' + para[2:]))
        elif re.match(r'^\d+\.\d+\. ', para):
            if out and out[-1] != '':
                out.append('')
            out.extend('§l' + l + '§r' for l in wrap(para))
        else:
            out.extend(wrap(para))
    return out


def main():
    rev = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    src = io.open(os.path.join(here, f'agreement-{rev}.md'), encoding='utf-8').read()
    parts = re.split(r'\n## ', '\n' + src)
    sections = [('Преамбула', parts[0].strip())]
    for p in parts[1:]:
        nl = p.find('\n')
        sections.append((p[:nl].strip(), p[nl + 1:].strip()))

    def secnum(title):
        m = re.match(r'(\d{1,2})\.', title)
        return int(m.group(1)) if m else 0

    volumes = []
    for vtitle, pred in VOLUMES:
        lines = []
        for t, b in [s for s in sections if pred(secnum(s[0]))]:
            lines.extend('§l' + hl + '§r' for hl in wrap(t))
            lines.append('')
            lines.extend(render(b))
            lines.append('')
        pages, cur = [], []
        for ln in lines:
            if len(cur) >= LINES_PER_PAGE:
                pages.append('\n'.join(cur).rstrip())
                cur = []
            if not cur and ln == '':              # never start a page with a blank line
                continue
            cur.append(ln)
        if cur:
            pages.append('\n'.join(cur).rstrip())
        volumes.append((vtitle, [p for p in pages if p.strip()]))

    out = []
    for vtitle, pages in volumes:
        out.append('---VOLUME--- ' + vtitle)
        for pg in pages:
            out.append('---PAGE---')
            out.append(pg)
    dst = os.path.join(here, '..', '..', 'src', 'main', 'resources', 'bastionauth', f'agreement-{rev}.txt')
    with io.open(dst + '.tmp', 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out) + '\n')
    os.replace(dst + '.tmp', dst)

    ok = True
    for vtitle, pages in volumes:
        widest = max(width(l) for pg in pages for l in pg.split('\n'))
        tallest = max(len(pg.split('\n')) for pg in pages)
        ok &= len(pages) <= MAX_PAGES_PER_BOOK and widest <= WIDTH_PX and tallest <= LINES_PER_PAGE
        print('%-28s pages=%-3d maxlines=%d widest=%dpx' % (vtitle, len(pages), tallest, widest))
    print('total pages:', sum(len(p) for _, p in volumes), '|', 'OK' if ok else 'LIMIT EXCEEDED')
    print('written', os.path.normpath(dst))


if __name__ == '__main__':
    main()
