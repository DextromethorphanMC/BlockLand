"""Copies maps from a backend's data directory into the maps that ship with the backend.

The shipped maps (server/backend/src/main/resources/maps/shipped) are the source of every built-in map: per map
<id>.nbt (or .schem), <id>.json (name, game type, void height, team spawns, the game type's settings) and an optional
<id>.png cover. A fresh backend installs them through its seeds (ShippedMaps); a running one keeps its own copies.

Use it after editing a map on a dev backend (admin upload, spawns, settings, cover):

    python -I tools/maps/export_maps.py dust_a            # one map
    python -I tools/maps/export_maps.py --all             # every map the data directory has
    python -I tools/maps/export_maps.py --data <dir> ...  # another backend's data directory

Maps produced by a generator (src/test, see tools/maps/MAPS.md) are exported by GeneratedMapsTest instead.
"""
import argparse
import json
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_DATA = ROOT / "server" / "backend" / "data"
SHIPPED = ROOT / "server" / "backend" / "src" / "main" / "resources" / "maps" / "shipped"
FORMATS = ("nbt", "schem")


def export(map_meta: dict, data: pathlib.Path, out: pathlib.Path) -> str:
    map_id = map_meta["id"]
    source = data / "maps" / f"{map_id}.{map_meta['format']}"
    if not source.is_file():
        raise SystemExit(f"{map_id}: map file missing: {source}")
    for fmt in FORMATS:
        (out / f"{map_id}.{fmt}").unlink(missing_ok=True)
    shutil.copyfile(source, out / f"{map_id}.{map_meta['format']}")

    meta = {"id": map_id, "name": map_meta["name"], "gameType": map_meta["gameType"]}
    if map_meta.get("spawnMarkerBlock"):
        meta["spawnMarkerBlock"] = map_meta["spawnMarkerBlock"]
    meta["voidY"] = map_meta["voidY"]
    meta["teamSpawns"] = map_meta.get("teamSpawns") or []
    meta["settings"] = map_meta.get("settings")
    with open(out / f"{map_id}.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")

    cover = data / "media" / "map" / f"{map_id}.png"
    target = out / f"{map_id}.png"
    if map_meta.get("coverVersion") and cover.is_file():
        shutil.copyfile(cover, target)
        return f"{map_id}: {source.stat().st_size} bytes, cover"
    target.unlink(missing_ok=True)
    return f"{map_id}: {source.stat().st_size} bytes"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("ids", nargs="*", help="map ids to export")
    parser.add_argument("--all", action="store_true", help="export every map in the data directory")
    parser.add_argument("--data", type=pathlib.Path, default=DEFAULT_DATA, help="backend data directory")
    args = parser.parse_args()
    if not args.ids and not args.all:
        parser.error("name the map ids to export, or --all")

    maps = {m["id"]: m for m in json.loads((args.data / "maps.json").read_text(encoding="utf-8"))}
    ids = sorted(maps) if args.all else args.ids
    unknown = [i for i in ids if i not in maps]
    if unknown:
        raise SystemExit(f"not in {args.data / 'maps.json'}: {', '.join(unknown)}")
    SHIPPED.mkdir(parents=True, exist_ok=True)
    for map_id in ids:
        print(export(maps[map_id], args.data, SHIPPED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
