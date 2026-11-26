# -*- coding: utf-8 -*-
"""Сборка отчётов по лабораторным работам в .docx.

Титульный лист берётся из «Титульник.docx» (образец ПНИПУ), текст — из исходников
src/labN.md. Строка «@include путь» вставляет Markdown-документ из репозитория (README,
Docs/*.md), «@file путь» — содержимое файла как код. Так отчёт всегда совпадает с актуальными
материалами репозитория.

Запуск (нужен python-docx):
    pip install python-docx
    python Docs/Reports/build_reports.py          # все отчёты
    python Docs/Reports/build_reports.py 3 5      # только № 3 и № 5
"""
from __future__ import annotations

import copy
import os
import re
import sys

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
TEMPLATE = os.path.join(HERE, "Титульник.docx")
SRC = os.path.join(HERE, "src")

DISCIPLINE = "Дисциплина: «Технология командной разработки программ»"
STUDENTS = ["Вековшинин И.Н.", "Кобзев С.И.", "Камалетдинов М.В.", "Хабарова А.А.", "Крючков К.П."]
FONT = "Times New Roman"
MONO = "Consolas"
TEXT_WIDTH_CM = 16.5


# ---------- Титульный лист ----------

def set_par_text(par, text: str) -> None:
    runs = par.runs
    if not runs:
        par.add_run(text)
        return
    runs[0].text = text
    for r in runs[1:]:
        r.text = ""


def fill_title(doc, number: int, topic: str) -> None:
    pars = doc.paragraphs
    student_i = 0
    for p in pars:
        t = p.text.strip()
        if t.startswith("Дисциплина:"):
            set_par_text(p, DISCIPLINE)
        elif t.startswith("по лабораторной работе"):
            set_par_text(p, f"по лабораторной работе № {number}")
        elif t.startswith("Тема:"):
            set_par_text(p, f"Тема: «{topic}»")
        elif "___" in t and not t.startswith(("Дата", "Сарманаев")) and student_i < len(STUDENTS):
            # Строка студента: «Фамилия И.О. ___________»
            set_par_text(p, f"{STUDENTS[student_i]}    ___________")
            student_i += 1


# ---------- Оформление ----------

def style_base(doc) -> None:
    st = doc.styles["Normal"]
    st.font.name = FONT
    st.font.size = Pt(14)
    st.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)


def fmt_par(p, *, align=WD_ALIGN_PARAGRAPH.JUSTIFY, indent=True, spacing=1.5, before=0, after=0, keep=False):
    pf = p.paragraph_format
    pf.alignment = align
    pf.first_line_indent = Cm(1.25) if indent else Cm(0)
    pf.line_spacing = spacing
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    if keep:
        pf.keep_with_next = True


def add_runs(p, text: str, size=None, bold=False, mono_size=None):
    """Простой разбор **жирного**, *курсива*, `кода` и [ссылок](url)."""
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)

    def link(m):
        label, url = m.group(1), m.group(2)
        if url.startswith("http") and label.strip("`") != url:
            return f"{label} ({url})"
        return label

    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, text)
    text = text.replace("<br>", "\n")
    parts = re.split(r"(\*\*[^*]+\*\*|`[^`]+`|(?<![\w*])\*[^*\s][^*]*\*(?![\w*]))", text)
    for part in parts:
        if not part:
            continue
        if part.startswith("**") and part.endswith("**"):
            r = p.add_run(part[2:-2])
            r.bold = True
        elif part.startswith("`") and part.endswith("`"):
            r = p.add_run(part[1:-1])
            r.font.name = MONO
            r.font.size = Pt(mono_size or ((size or 14) - 2))
        elif part.startswith("*") and part.endswith("*") and len(part) > 2:
            r = p.add_run(part[1:-1])
            r.italic = True
        else:
            r = p.add_run(part)
        if bold:
            r.bold = True
        if size and not (part.startswith("`") and part.endswith("`")):
            r.font.size = Pt(size)
    return p


def shade(cell_or_par_el, fill: str) -> None:
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    cell_or_par_el.append(shd)


def heading(doc, text: str, level: int) -> None:
    p = doc.add_paragraph()
    size = {1: 16, 2: 14, 3: 14, 4: 14}[min(level, 4)]
    align = WD_ALIGN_PARAGRAPH.CENTER if level == 1 else WD_ALIGN_PARAGRAPH.LEFT
    fmt_par(p, align=align, indent=level > 1, before=12 if level <= 2 else 8, after=6, keep=True)
    add_runs(p, text, size=size, bold=True)
    if level == 3:
        for r in p.runs:
            r.italic = level == 4


def code_block(doc, lines: list[str]) -> None:
    for i, line in enumerate(lines):
        p = doc.add_paragraph()
        fmt_par(p, align=WD_ALIGN_PARAGRAPH.LEFT, indent=False, spacing=1.0,
                before=4 if i == 0 else 0, after=4 if i == len(lines) - 1 else 0)
        p.paragraph_format.left_indent = Cm(0.3)
        r = p.add_run(line if line else " ")
        r.font.name = MONO
        r.font.size = Pt(9)
        r._element.rPr.rFonts.set(qn("w:eastAsia"), MONO)
        shade(p._p.get_or_add_pPr(), "F2F2F2")


def table(doc, rows: list[list[str]]) -> None:
    header, body = rows[0], rows[1:]
    ncol = len(header)
    t = doc.add_table(rows=1 + len(body), cols=ncol)
    t.style = doc.styles["Table Grid"] if "Table Grid" in [s.name for s in doc.styles] else None
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    size = 11 if ncol <= 3 else (10 if ncol <= 5 else 9)
    # Ширина столбцов — по объёму текста: короткие («№», «SP») узкие, описания широкие.
    char_cm = 0.2 * size / 10  # средняя ширина символа Times New Roman
    mins, avgs = [], []
    for ci in range(ncol):
        col = [re.sub(r"[`*]|\[|\]\([^)]*\)", "", r[ci]) if ci < len(r) else "" for r in [header] + body]
        longest_word = max((len(w) for c in col for w in c.replace("/", "/ ").split()), default=1)
        mins.append(min(longest_word * char_cm + 0.4, 4.5))
        avgs.append(max(sum(len(c) for c in col[1:]) / max(len(col) - 1, 1), 1))
    free = TEXT_WIDTH_CM - sum(mins)
    if free < 0:
        widths_cm = [m * TEXT_WIDTH_CM / sum(mins) for m in mins]
    else:
        long_cols = [a if a > 12 else 0 for a in avgs]
        share = long_cols if sum(long_cols) else avgs
        widths_cm = [m + free * s / sum(share) for m, s in zip(mins, share)]
    widths = [Cm(w) for w in widths_cm]
    tbl_layout = OxmlElement("w:tblLayout")
    tbl_layout.set(qn("w:type"), "fixed")
    t._tbl.tblPr.append(tbl_layout)
    for ci, gc in enumerate(t._tbl.tblGrid.findall(qn("w:gridCol"))):
        gc.set(qn("w:w"), str(int(widths[ci].twips)))
    for row in t.rows:
        for ci, cell in enumerate(row.cells):
            cell.width = widths[ci]
    for ri, row in enumerate([header] + body):
        for ci in range(ncol):
            cell = t.cell(ri, ci)
            cell.text = ""
            p = cell.paragraphs[0]
            fmt_par(p, align=WD_ALIGN_PARAGRAPH.LEFT, indent=False, spacing=1.0)
            add_runs(p, row[ci] if ci < len(row) else "", size=size, bold=(ri == 0), mono_size=size - 1)
            if ri == 0:
                shade(cell._tc.get_or_add_tcPr(), "D9E2F3")
                p.paragraph_format.keep_with_next = True
    # Строка заголовка повторяется на каждой странице и не отрывается от таблицы
    tr_pr = t.rows[0]._tr.get_or_add_trPr()
    for tag in ("w:tblHeader", "w:cantSplit"):
        el = OxmlElement(tag)
        el.set(qn("w:val"), "true")
        tr_pr.append(el)
    # Границы, если в шаблоне нет стиля Table Grid
    if t.style is None or t.style.name != "Table Grid":
        tbl_pr = t._tbl.tblPr
        borders = OxmlElement("w:tblBorders")
        for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
            el = OxmlElement(f"w:{edge}")
            el.set(qn("w:val"), "single")
            el.set(qn("w:sz"), "4")
            el.set(qn("w:color"), "808080")
            borders.append(el)
        tbl_pr.append(borders)
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def image(doc, path: str, caption: str | None, state: dict) -> None:
    if not os.path.exists(path):
        p = doc.add_paragraph()
        fmt_par(p, align=WD_ALIGN_PARAGRAPH.CENTER, indent=False)
        r = p.add_run(f"[Изображение не найдено: {os.path.relpath(path, REPO)}]")
        r.font.color.rgb = RGBColor(0xC0, 0, 0)
        return
    from PIL import Image as PILImage  # noqa: WPS433 (опционально: только для пропорций)

    with PILImage.open(path) as im:
        w, h = im.size
    wide = w / h > 1.45 and w > 1600  # широкая подробная схема — на отдельный альбомный лист
    if wide:
        # Заголовок прямо перед схемой переносим на альбомный лист, чтобы он не остался один
        last = doc.paragraphs[-1] if doc.paragraphs else None
        moved = None
        if last is not None and last.paragraph_format.keep_with_next and last.text.strip():
            moved = last.text
            last._p.getparent().remove(last._p)
        set_orientation(doc.add_section(WD_SECTION.NEW_PAGE), landscape=True)
        if moved:
            heading(doc, moved, 2)
        width = 25.0
        if h / w * width > 15:
            width = 15 * w / h
    else:
        width = TEXT_WIDTH_CM
        if h / w * width > 22:  # высокая картинка — ограничиваем высоту страницей
            width = 22 * w / h
    p = doc.add_paragraph()
    fmt_par(p, align=WD_ALIGN_PARAGRAPH.CENTER, indent=False, spacing=1.0, before=6, keep=True)
    p.add_run().add_picture(path, width=Cm(width))
    state["fig"] += 1
    c = doc.add_paragraph()
    fmt_par(c, align=WD_ALIGN_PARAGRAPH.CENTER, indent=False, after=8)
    add_runs(c, f"Рисунок {state['fig']} — {caption or os.path.basename(path)}", size=12)
    if wide:
        set_orientation(doc.add_section(WD_SECTION.NEW_PAGE), landscape=False)


def set_orientation(sec, landscape: bool) -> None:
    from docx.enum.section import WD_ORIENT

    long, short = max(sec.page_width, sec.page_height), min(sec.page_width, sec.page_height)
    sec.orientation = WD_ORIENT.LANDSCAPE if landscape else WD_ORIENT.PORTRAIT
    sec.page_width, sec.page_height = (long, short) if landscape else (short, long)
    if landscape:
        sec.left_margin = sec.right_margin = Cm(1.5)
        sec.top_margin = sec.bottom_margin = Cm(1.5)
    else:
        sec.left_margin, sec.right_margin = Cm(3), Cm(1.5)
        sec.top_margin = sec.bottom_margin = Cm(2)


# ---------- Markdown → docx ----------

def read_include(path: str, drop_h1: bool = True) -> tuple[list[str], str]:
    full = os.path.normpath(os.path.join(REPO, path))
    with open(full, encoding="utf-8") as f:
        lines = f.read().splitlines()
    if drop_h1:
        for i, line in enumerate(lines):
            if line.startswith("# "):
                del lines[i]
                break
    return lines, os.path.dirname(full)


def render(doc, lines: list[str], base_dir: str, state: dict, shift: int = 0) -> None:
    i = 0
    para: list[str] = []

    def flush():
        if para:
            text = " ".join(s.strip() for s in para)
            p = doc.add_paragraph()
            fmt_par(p)
            add_runs(p, text)
            para.clear()

    while i < len(lines):
        line = lines[i]
        s = line.strip()
        if s.startswith("@include "):
            flush()
            arg = s[len("@include "):].strip()
            level_shift = 0
            m = re.match(r"(.+?)\s+shift=(\d+)$", arg)
            if m:
                arg, level_shift = m.group(1), int(m.group(2))
            inc, inc_dir = read_include(arg)
            render(doc, inc, inc_dir, state, shift=level_shift)
        elif s.startswith("@file "):
            flush()
            path = os.path.normpath(os.path.join(REPO, s[len("@file "):].strip()))
            with open(path, encoding="utf-8") as f:
                code_block(doc, f.read().rstrip("\n").splitlines())
        elif s.startswith("@pagebreak"):
            flush()
            doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
        elif s.startswith("```"):
            flush()
            j = i + 1
            block = []
            while j < len(lines) and not lines[j].strip().startswith("```"):
                block.append(lines[j])
                j += 1
            code_block(doc, block)
            i = j
        elif re.match(r"^#{1,6} ", s):
            flush()
            level = len(s) - len(s.lstrip("#"))
            heading(doc, s[level:].strip(), min(level + shift, 4))
        elif s.startswith("|") and i + 1 < len(lines) and re.match(r"^\|?\s*:?-{3,}", lines[i + 1].strip()):
            flush()
            rows = []
            j = i
            while j < len(lines) and lines[j].strip().startswith("|"):
                if j != i + 1:
                    cells = [c.strip() for c in re.split(r"(?<!\\)\|", lines[j].strip().strip("|"))]
                    rows.append([c.replace("\\|", "|") for c in cells])
                j += 1
            table(doc, rows)
            i = j - 1
        elif s.startswith("!["):
            flush()
            m = re.match(r"!\[([^\]]*)\]\(([^)]+)\)", s)
            caption = m.group(1)
            # Подпись «*Рисунок … — текст*» на следующей строке заменяет alt-текст
            if i + 2 < len(lines) and lines[i + 2].strip().startswith("*Рисунок"):
                caption = re.sub(r"^\*Рисунок[^—]*—\s*", "", lines[i + 2].strip()).rstrip("*").rstrip(".")
                i += 2
            image(doc, os.path.normpath(os.path.join(base_dir, m.group(2))), caption, state)
        elif s.startswith(">"):
            flush()
            quote = []
            j = i
            while j < len(lines) and lines[j].strip().startswith(">"):
                quote.append(lines[j].strip()[1:].strip())
                j += 1
            p = doc.add_paragraph()
            fmt_par(p, indent=False)
            p.paragraph_format.left_indent = Cm(1)
            add_runs(p, " ".join(q for q in quote if q).replace("  ", " "))
            for r in p.runs:
                r.italic = True
            i = j - 1
        elif re.match(r"^(\s*)([-*]|\d+\.)\s+", line):
            flush()
            m = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", line)
            depth = len(m.group(1)) // 2
            marker = "–" if m.group(2) in "-*" else m.group(2)
            text = m.group(3)
            j = i + 1
            while j < len(lines) and lines[j].startswith("  ") and lines[j].strip() \
                    and not re.match(r"^\s*([-*]|\d+\.)\s+", lines[j]):
                text += " " + lines[j].strip()
                j += 1
            p = doc.add_paragraph()
            fmt_par(p, indent=False)
            p.paragraph_format.left_indent = Cm(1.25 + depth * 0.75)
            p.paragraph_format.first_line_indent = Cm(-0.6)
            add_runs(p, f"{marker} {text}")
            i = j - 1
        elif not s:
            flush()
        elif s.startswith("*Рисунок"):
            flush()  # подпись уже обработана вместе с картинкой
        else:
            para.append(line)
        i += 1
    flush()


def add_page_numbers(doc) -> None:
    sec = doc.sections[0]
    sec.different_first_page_header_footer = True
    p = sec.footer.paragraphs[0] if sec.footer.paragraphs else sec.footer.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    for kind, text in (("begin", None), (None, "PAGE"), ("end", None)):
        if kind:
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), kind)
        else:
            el = OxmlElement("w:instrText")
            el.set(qn("xml:space"), "preserve")
            el.text = text
        run._r.append(el)
    run.font.name = FONT
    run.font.size = Pt(12)


def build(number: int) -> str:
    src_path = os.path.join(SRC, f"lab{number}.md")
    with open(src_path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    topic = lines[0].lstrip("# ").strip()
    doc = Document(TEMPLATE)
    style_base(doc)
    fill_title(doc, number, topic)
    doc.paragraphs[-1].add_run().add_break(WD_BREAK.PAGE)
    add_page_numbers(doc)
    state = {"fig": 0}
    render(doc, lines[1:], SRC, state)
    out = os.path.join(HERE, f"lab{number}.docx")
    doc.save(out)
    return out


if __name__ == "__main__":
    numbers = [int(a) for a in sys.argv[1:]] or [n for n in range(1, 8) if os.path.exists(os.path.join(SRC, f"lab{n}.md"))]
    for n in numbers:
        print("готово:", os.path.relpath(build(n), REPO))
