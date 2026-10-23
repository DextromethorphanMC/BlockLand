"""Builds the UI fonts shipped in the mod: subsets of Noto Sans SC (SIL OFL 1.1) Regular and Bold.

    python tools/fonts/subset_fonts.py --regular NotoSansSC-Regular.otf --bold NotoSansSC-Bold.otf [--license OFL.txt]
    python tools/fonts/subset_fonts.py --check     (do the shipped fonts cover every character the mod uses?)

Sources: https://github.com/notofonts/noto-cjk (Sans/SubsetOTF/SC). Re-run it whenever the lang files or data JSONs
gain characters: every character they use is kept, on top of

* Latin (ASCII, Latin-1, Latin Extended-A), general punctuation, arrows, maths, geometric shapes and other symbols,
* CJK symbols and punctuation, kana, half/full-width forms,
* the 8105 characters of the 通用规范汉字表 (tgh8105.txt, from the Unicode Unihan database, field kTGH) and GB2312.

The CFF hinting, vertical metrics and the OpenType layout tables are dropped (our renderer draws unhinted signed
distance fields and does no shaping); the Latin pair kerning from GPOS is kept as a legacy `kern` table, which
FreeType reads (FT_Get_Kerning). Writes the fonts and OFL.txt (with the fonts' copyright line) to
mod/src/main/resources/assets/blockland/fonts/ and reports characters the fonts lack (drawn with the vanilla font).
"""
import argparse
import glob
import io
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables._k_e_r_n import KernTable_format_0

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
RESOURCES = os.path.join(ROOT, 'mod', 'src', 'main', 'resources')
OUT = os.path.join(RESOURCES, 'assets', 'blockland', 'fonts')
JAVA = os.path.join(ROOT, 'mod', 'src', 'main', 'java')

RANGES = [
    (0x0020, 0x007E),  # Basic Latin
    (0x00A0, 0x00FF),  # Latin-1 Supplement
    (0x0100, 0x017F),  # Latin Extended-A (player names)
    (0x0391, 0x03C9),  # Greek letters
    (0x0401, 0x0451),  # Cyrillic
    (0x2000, 0x206F),  # General Punctuation
    (0x2070, 0x209F),  # super/subscripts
    (0x20A0, 0x20BF),  # currency
    (0x2100, 0x214F),  # letterlike (℃ № ™)
    (0x2150, 0x218F),  # number forms (Ⅰ Ⅱ)
    (0x2190, 0x21FF),  # arrows
    (0x2200, 0x22FF),  # maths
    (0x2300, 0x23FF),  # technical
    (0x2460, 0x24FF),  # enclosed alphanumerics (①)
    (0x2500, 0x257F),  # box drawing
    (0x2580, 0x259F),  # blocks
    (0x25A0, 0x25FF),  # geometric shapes (■ ▲ ● ◆)
    (0x2600, 0x26FF),  # miscellaneous symbols (★ ☆ ♥)
    (0x2700, 0x27BF),  # dingbats (✓ ✕)
    (0x3000, 0x303F),  # CJK symbols and punctuation
    (0x3040, 0x30FF),  # hiragana, katakana
    (0x3200, 0x32FF),  # enclosed CJK
    (0xFE30, 0xFE4F),  # CJK compatibility forms
    (0xFE50, 0xFE6F),  # small form variants
    (0xFF00, 0xFFEF),  # half/full-width forms
]
# kerning is kept for these (Latin text); CJK is monospaced in this font
KERN_RANGES = [(0x0020, 0x007E), (0x00A0, 0x017F), (0x2010, 0x2027)]


def wanted_codepoints():
    cps = set()
    for lo, hi in RANGES:
        cps.update(range(lo, hi + 1))
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tgh8105.txt'), encoding='utf-8') as f:
        for line in f:
            if not line.startswith('#'):
                cps.update(ord(c) for c in line.strip())
    # GB2312: every two-byte code that decodes
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                cps.update(ord(c) for c in bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                pass
    used = used_codepoints()
    cps.update(used)
    return cps, used


def used_codepoints():
    """Every character in the lang files, the data/asset JSONs and the Java sources."""
    files = glob.glob(os.path.join(RESOURCES, 'assets', 'blockland', '**', '*.json'), recursive=True)
    files += glob.glob(os.path.join(RESOURCES, 'data', 'blockland', '**', '*.json'), recursive=True)
    files += glob.glob(os.path.join(JAVA, '**', '*.java'), recursive=True)
    cps = set()
    for path in files:
        with open(path, encoding='utf-8') as f:
            cps.update(ord(c) for c in f.read())
    return {c for c in cps if c >= 0x20 and c not in (0xFEFF,)}


def kerning_pairs(font, glyphs):
    """Pair adjustments (font units) from the GPOS 'kern' feature between the given glyphs."""
    pairs = {}
    if 'GPOS' not in font:
        return pairs
    gpos = font['GPOS'].table
    if gpos.FeatureList is None or gpos.LookupList is None:
        return pairs
    lookups = set()
    for record in gpos.FeatureList.FeatureRecord:
        if record.FeatureTag == 'kern':
            lookups.update(record.Feature.LookupListIndex)
    for index in sorted(lookups):
        lookup = gpos.LookupList.Lookup[index]
        subtables = lookup.SubTable
        if lookup.LookupType == 9:  # extension
            subtables = [s.ExtSubTable for s in subtables]
        for st in subtables:
            if getattr(st, 'LookupType', 2) != 2:
                continue
            if st.Format == 1:
                for i, first in enumerate(st.Coverage.glyphs):
                    if first not in glyphs:
                        continue
                    for record in st.PairSet[i].PairValueRecord:
                        value = getattr(record.Value1, 'XAdvance', 0) or 0
                        if value and record.SecondGlyph in glyphs:
                            pairs.setdefault((first, record.SecondGlyph), value)
            elif st.Format == 2:
                class1 = st.ClassDef1.classDefs
                class2 = st.ClassDef2.classDefs
                for first in st.Coverage.glyphs:
                    if first not in glyphs:
                        continue
                    c1 = class1.get(first, 0)
                    row = st.Class1Record[c1].Class2Record
                    for second in glyphs:
                        value = getattr(row[class2.get(second, 0)].Value1, 'XAdvance', 0) or 0
                        if value:
                            pairs.setdefault((first, second), value)
    return pairs


def build(source, target, codepoints):
    options = subset.Options()
    options.layout_features = ['kern']
    options.hinting = False
    options.desubroutinize = False
    options.name_IDs = ['*']
    options.name_languages = ['*']
    options.notdef_outline = True
    options.drop_tables += ['VORG', 'vhea', 'vmtx', 'BASE', 'DSIG', 'GDEF', 'JSTF', 'MATH', 'STAT']
    font = TTFont(source, lazy=False)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(codepoints))
    subsetter.subset(font)

    cmap = font.getBestCmap()
    kern_glyphs = {cmap[c] for lo, hi in KERN_RANGES for c in range(lo, hi + 1) if c in cmap}
    pairs = kerning_pairs(font, kern_glyphs)
    for table in ('GPOS', 'GSUB'):
        if table in font:
            del font[table]
    if pairs:
        kern = newTable('kern')
        kern.version = 0
        sub = KernTable_format_0()
        sub.version = 0
        sub.coverage = 1
        sub.format = 0
        sub.kernTable = dict(sorted(pairs.items())[:10900])  # one format-0 subtable holds at most ~10920 pairs
        kern.kernTables = [sub]
        font['kern'] = kern
    font.save(target)
    return font, len(pairs)


def license_text(font, license_path):
    copyright = ''
    for record in font['name'].names:
        if record.nameID == 0 and record.platformID == 3:
            copyright = record.toUnicode()
            break
    with open(license_path, encoding='utf-8') as f:
        body = f.read()
    header = (copyright + '\n\n' if copyright else '') + (
        'The files notosanssc-regular.otf and notosanssc-bold.otf in this folder are subsets of Noto Sans SC\n'
        '(https://github.com/notofonts/noto-cjk) made with tools/fonts/subset_fonts.py.\n\n')
    return header + body


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--regular')
    parser.add_argument('--bold')
    parser.add_argument('--license', help='the OFL text from the font repository (default: keep the current OFL.txt)')
    parser.add_argument('--check', action='store_true', help='only check the shipped fonts against the characters in use')
    args = parser.parse_args()
    if args.check:
        return check()
    if not args.regular or not args.bold:
        parser.error('--regular and --bold are required (or --check)')

    codepoints, used = wanted_codepoints()
    os.makedirs(OUT, exist_ok=True)
    total = 0
    missing_all = None
    font = None
    for source, name in ((args.regular, 'notosanssc-regular.otf'), (args.bold, 'notosanssc-bold.otf')):
        target = os.path.join(OUT, name)
        font, kerned = build(source, target, codepoints)
        cmap = font.getBestCmap()
        missing = sorted(c for c in used if c not in cmap and c != 0x0A)
        missing_all = missing if missing_all is None else sorted(set(missing_all) | set(missing))
        size = os.path.getsize(target)
        total += size
        print(f'{name}: {len(cmap)} characters, {kerned} kerning pairs, {size / 1048576:.2f} MB')
    if args.license:
        with open(os.path.join(OUT, 'OFL.txt'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(license_text(font, args.license))
    print(f'total {total / 1048576:.2f} MB; {len(used)} characters used by the mod')
    if missing_all:
        listing = ' '.join(f'U+{c:04X}' for c in missing_all)
        print(f'not in the font (drawn with the vanilla font): {listing}')
    return 0


def check():
    """Exit code 1 when a letter or CJK character in use is missing (the subset must be rebuilt); symbols the font
    lacks are only listed (the game draws them with the vanilla font)."""
    used = used_codepoints()
    status = 0
    for name in ('notosanssc-regular.otf', 'notosanssc-bold.otf'):
        cmap = TTFont(os.path.join(OUT, name)).getBestCmap()
        missing = sorted(c for c in used if c not in cmap and c != 0x0A)
        letters = [c for c in missing if chr(c).isalnum()]
        print(f'{name}: {len(used)} characters in use, {len(missing)} not in the font')
        if missing:
            print('  drawn with the vanilla font: ' + ' '.join(f'U+{c:04X} {chr(c)}' for c in missing))
        if letters:
            print('  letters/CJK missing, rebuild the subset: ' + ' '.join(chr(c) for c in letters))
            status = 1
    return status


if __name__ == '__main__':
    sys.exit(main())
