#!/usr/bin/env python3
"""
Baut einen lokalen Lebensmittel-Katalog aus dem OpenFoodFacts-Datendump (ODbL 1.0).

Warum: Die Remote-Textsuche von OFF findet Komposita wie "leinsamenbrot" schlecht.
Ein lokaler Katalog (CH/DE/AT) ist offline, schnell und durchsuchbar wie yazio_foods.json.

Der Dump wird gestreamt (nie komplett im Speicher) und direkt gefiltert.

Nutzung:
    python3 build_off_catalog.py --countries switzerland --out app/src/main/assets/off_catalog.jsonl.gz
    python3 build_off_catalog.py --countries switzerland,germany,austria --max 60000 --out ...
    python3 build_off_catalog.py --input lokaler_dump.jsonl.gz ...   (statt Download)

Ausgabe: JSON Lines (gzip), je Zeile ein Produkt im Schema von yazio_foods.json
(name, brand, barcode, caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g,
fiberPer100g, sugarPer100g, saltPer100g, category, imageUrl).

Quelle/Lizenz: https://world.openfoodfacts.org (Open Database License, ODbL 1.0)
"""
import argparse
import gzip
import json
import sys
import urllib.request

DUMP_URL = "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz"
USER_AGENT = "NutriSnap-Android/1.0 (github.com/sondereggerseverin/NutriSnap; food catalog build)"
COUNTRY_TAGS = {
    "switzerland": "en:switzerland",
    "germany": "en:germany",
    "austria": "en:austria",
}


def num(n: dict, *keys):
    for k in keys:
        v = n.get(k)
        if v is None or v == "":
            continue
        try:
            return float(v)
        except (TypeError, ValueError):
            continue
    return None


def map_product(p: dict) -> dict | None:
    name = (p.get("product_name_de") or p.get("product_name") or "").strip()
    if len(name) < 2:
        return None
    n = p.get("nutriments") or {}
    kcal = num(n, "energy-kcal_100g")
    if kcal is None:
        kj = num(n, "energy_100g", "energy-kj_100g")
        kcal = kj / 4.184 if kj else None
    protein = num(n, "proteins_100g")
    carbs = num(n, "carbohydrates_100g")
    fat = num(n, "fat_100g")
    # Für Tracking braucht es Kalorien UND die drei Makros, sonst unbrauchbar.
    if kcal is None or kcal <= 0 or kcal > 900:
        return None
    if protein is None or carbs is None or fat is None:
        return None
    if protein > 100 or carbs > 100 or fat > 100 or protein + carbs + fat > 105:
        return None  # offensichtlich fehlerhafte Eingabe

    brand = (p.get("brands") or "").split(",")[0].strip() or None
    cats = p.get("categories_tags") or []
    return {
        "name": name[:120],
        "brand": brand,
        "barcode": (p.get("code") or "").strip() or None,
        "caloriesPer100g": round(kcal, 1),
        "proteinPer100g": round(protein, 1),
        "carbsPer100g": round(carbs, 1),
        "fatPer100g": round(fat, 1),
        "fiberPer100g": round(num(n, "fiber_100g") or 0.0, 1),
        "sugarPer100g": round(num(n, "sugars_100g") or 0.0, 1),
        "saltPer100g": round(num(n, "salt_100g") or 0.0, 2),
        "category": cats[-1] if cats else None,
        "imageUrl": None,
    }


def open_source(path: str | None):
    if path:
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    req = urllib.request.Request(DUMP_URL, headers={"User-Agent": USER_AGENT})
    resp = urllib.request.urlopen(req, timeout=120)
    return gzip.open(resp, "rt", encoding="utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--countries", default="switzerland",
                    help="Kommagetrennt: switzerland,germany,austria")
    ap.add_argument("--max", type=int, default=0,
                    help="Maximale Produktzahl (0 = unbegrenzt); behält die meistgescannten")
    ap.add_argument("--input", help="Lokaler Dump statt Download")
    ap.add_argument("--out", required=True, help="Ziel (.jsonl.gz)")
    args = ap.parse_args()

    wanted = set()
    for c in args.countries.split(","):
        c = c.strip().lower()
        if c not in COUNTRY_TAGS:
            sys.exit(f"Unbekanntes Land: {c} (erlaubt: {', '.join(COUNTRY_TAGS)})")
        wanted.add(COUNTRY_TAGS[c])

    seen_barcodes = set()
    seen_keys = set()
    kept = []  # (scans, product)
    total = 0

    with open_source(args.input) as src:
        for line in src:
            total += 1
            if total % 500000 == 0:
                print(f"  {total} Zeilen gelesen, {len(kept)} behalten", flush=True)
            try:
                p = json.loads(line)
            except ValueError:
                continue
            if not wanted.intersection(p.get("countries_tags") or []):
                continue
            m = map_product(p)
            if m is None:
                continue
            bc = m["barcode"]
            if bc and bc in seen_barcodes:
                continue
            key = f"{m['name'].lower()}|{(m['brand'] or '').lower()}"
            if key in seen_keys:
                continue  # Dedup-Key analog name|brand|barcode (ohne Barcode-Varianten)
            if bc:
                seen_barcodes.add(bc)
            seen_keys.add(key)
            kept.append((p.get("unique_scans_n") or 0, m))

    kept.sort(key=lambda t: t[0], reverse=True)
    if args.max and len(kept) > args.max:
        kept = kept[: args.max]

    with gzip.open(args.out, "wt", encoding="utf-8", compresslevel=9) as out:
        for _, m in kept:
            out.write(json.dumps(m, ensure_ascii=False, separators=(",", ":")) + "\n")

    print(f"FERTIG: {len(kept)} von {total} gelesenen Zeilen -> {args.out}")


if __name__ == "__main__":
    main()
