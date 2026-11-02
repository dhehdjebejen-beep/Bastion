"""Renders the agreement source (agreement-<rev>.md) as a PDF for publishing.

python make_pdf.py 1.4 <out.pdf>

Same source as the in-game books, so the two never drift apart. Layout follows the earlier
PDF editions: Arial, bold headings, list items with the term in bold.
"""
import io, os, re, sys
from xml.sax.saxutils import escape

from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import HRFlowable, ListFlowable, ListItem, Paragraph, SimpleDocTemplate, Spacer

FONTS = r'C:/Windows/Fonts'
pdfmetrics.registerFont(TTFont('Arial', os.path.join(FONTS, 'arial.ttf')))
pdfmetrics.registerFont(TTFont('Arial-Bold', os.path.join(FONTS, 'arialbd.ttf')))
pdfmetrics.registerFont(TTFont('Arial-Italic', os.path.join(FONTS, 'ariali.ttf')))
pdfmetrics.registerFont(TTFont('Arial-BoldItalic', os.path.join(FONTS, 'arialbi.ttf')))
from reportlab.lib.fonts import addMapping
addMapping('Arial', 0, 0, 'Arial')
addMapping('Arial', 1, 0, 'Arial-Bold')
addMapping('Arial', 0, 1, 'Arial-Italic')
addMapping('Arial', 1, 1, 'Arial-BoldItalic')

INK = '#202124'
TITLE = ParagraphStyle('title', fontName='Arial-Bold', fontSize=20, leading=26, textColor=INK, spaceAfter=6)
SUB = ParagraphStyle('sub', fontName='Arial-Bold', fontSize=10.5, leading=14, textColor=INK, spaceAfter=10)
H1 = ParagraphStyle('h1', fontName='Arial-Bold', fontSize=13.5, leading=18, textColor=INK, spaceBefore=12, spaceAfter=6)
H2 = ParagraphStyle('h2', fontName='Arial-Bold', fontSize=11, leading=15, textColor=INK, spaceBefore=8, spaceAfter=4)
BODY = ParagraphStyle('body', fontName='Arial', fontSize=10, leading=15.5, textColor=INK, spaceAfter=6, alignment=TA_LEFT)
ITEM = ParagraphStyle('item', parent=BODY, spaceAfter=2)
LEAD = ParagraphStyle('lead', parent=BODY, fontName='Arial-Bold', spaceBefore=4, spaceAfter=3)
FINAL = ParagraphStyle('final', parent=BODY, fontName='Arial-Bold', spaceBefore=8)


def item_text(text):
    """«Термин — описание»: the term goes bold, as in the earlier editions."""
    head, sep, tail = text.partition(' — ')
    if sep and len(head) <= 60:
        return '<b>%s</b> — %s' % (escape(head), escape(tail))
    head, sep, tail = text.partition('. ')
    if sep and len(head) <= 70:
        return '<b>%s.</b> %s' % (escape(head), escape(tail))
    return escape(text)


def build(rev, out):
    here = os.path.dirname(os.path.abspath(__file__))
    src = io.open(os.path.join(here, f'agreement-{rev}.md'), encoding='utf-8').read()
    lines = src.split('\n')
    story = [Paragraph(escape(lines[0]), TITLE), Paragraph(escape(lines[1]), SUB),
             HRFlowable(width='100%', thickness=0.6, color='#c8c8c8', spaceBefore=4, spaceAfter=10)]
    bullets = []

    def flush():
        if bullets:
            story.append(ListFlowable([ListItem(Paragraph(b, ITEM), leftIndent=14, value='•') for b in bullets],
                                      bulletType='bullet', start='•', leftIndent=14, bulletFontName='Arial',
                                      spaceAfter=6))
            bullets.clear()

    first_heading = True
    for raw in lines[2:]:
        line = raw.strip()
        if not line:
            flush()
            continue
        if line.startswith('## '):
            flush()
            story.append(Paragraph(escape(line[3:]), H1))
        elif line.startswith('* '):
            bullets.append(item_text(line[2:]))
        elif re.match(r'^\d+\.\d+\. ', line):
            flush()
            story.append(Paragraph(escape(line), H2))
        elif first_heading and line.startswith('Преамбула'):
            first_heading = False
            story.append(Paragraph(escape(line), H1))
        elif line.endswith(':') and len(line) < 120:
            flush()
            story.append(Paragraph(escape(line), LEAD))
        elif line.startswith('MAXCORE — система'):
            flush()
            story.append(Paragraph(escape(line), FINAL))
        else:
            flush()
            story.append(Paragraph(escape(line), BODY))
    flush()

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont('Arial', 8)
        canvas.setFillColor('#808080')
        canvas.drawString(22 * mm, 12 * mm, f'Пользовательское соглашение MaxCora · редакция {rev}')
        canvas.drawRightString(letter[0] - 22 * mm, 12 * mm, str(doc.page))
        canvas.restoreState()

    doc = SimpleDocTemplate(out + '.tmp', pagesize=letter, leftMargin=22 * mm, rightMargin=22 * mm,
                            topMargin=20 * mm, bottomMargin=20 * mm,
                            title=f'Пользовательское соглашение MaxCora, редакция {rev}', author='Администрация MaxCora')
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    os.replace(out + '.tmp', out)
    print('written', out)


if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2])
