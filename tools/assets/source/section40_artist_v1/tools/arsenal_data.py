"""Offline section-40 art parameters; gameplay timings/slots are read, never duplicated or rewritten."""
import json
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
ART=json.loads((HERE/'arsenal_data.json').read_text(encoding='utf-8'))
GUNS=ART['guns']
def gameplay():
    return json.loads((ROOT/'mod/src/main/resources/data/blockland/guns/guns.json').read_text(encoding='utf-8'))['guns']
