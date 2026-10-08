#!/usr/bin/env python3
"""
Baut einen lokalen Lebensmittel-Katalog aus OpenFoodFacts (ODbL 1.0).

Zwei Quellen (in dieser Reihenfolge):
  1) REST-API, paginiert pro Land  – schnell, ~MB statt 13 GB
  2) Optionaler JSONL-Dump         – --input oder --dump-url

Der volle Dump (static.openfoodfacts.org, ~13 GB) ist auf CI oft zu langsam
oder 503. Deshalb ist API der Standardweg.

Nutzung:
    python3 scripts/build_off_catalog.py --countries switzerland --out app/src/main/assets/off_catalog.jsonl.gz
    python3 scripts/build_off_catalog.py --countries switzerland,germany --max 40000 --out ...
    python3 scripts/build_off_catalog.py --input lokal.jsonl.gz --out ...

Ausgabe: JSON Lines (gzip), Schema wie yazio_foods.json.
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DUMP_URL = "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz"
USER_AGENT = "NutriSnap-Android/1.0 (github.com/sondereggerseverin/NutriSnap; food catalog build)"
COUNTRY_TAGS = {
    "switzerland": "en:switzerland",
    "germany": "en:germany",
    "austria": "en:austria",
}
PAGE_SIZE = 100
REQUEST_PAUSE_S = 0.35


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
    if kcal is None or kcal <= 0 or kcal > 900:
        return None
    if protein is None or carbs is None or fat is None:
        return None
    if protein > 100 or carbs > 100 or fat > 100 or protein + carbs + fat > 105:
        return None

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
        "_scans": int(p.get("unique_scans_n") or 0),
    }


def http_json(url: str, retries: int = 3) -> dict | None:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=45) as resp:
                return json.loads(resp.read().decode("utf-8", errors="replace"))
        except urllib.error.HTTPError as e:
            print(f"  HTTP {e.code} für {url[:80]}… (Versuch {attempt}/{retries})", flush=True)
            if e.code in (429, 502, 503, 504) and attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return None
        except Exception as e:
            print(f"  Fehler {e!r} (Versuch {attempt}/{retries})", flush=True)
            if attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return None
    return None


def fetch_country_api(country_tag: str, max_products: int) -> list[dict]:
    slug = country_tag.split(":")[-1]
    kept: list[dict] = []
    page = 1
    total_reported = None
    while True:
        if max_products and len(kept) >= max_products:
            break
        qs = urllib.parse.urlencode(
            {
                "action": "process",
                "tagtype_0": "countries",
                "tag_contains_0": "contains",
                "tag_0": slug,
                "page_size": PAGE_SIZE,
                "page": page,
                "json": 1,
                "sort_by": "unique_scans_n",
                "fields": "code,product_name,product_name_de,brands,nutriments,"
                "categories_tags,countries_tags,unique_scans_n",
            }
        )
        hosts = (
            "https://de.openfoodfacts.org/cgi/search.pl",
            "https://world.openfoodfacts.org/cgi/search.pl",
        )
        data = None
        for host in hosts:
            data = http_json(f"{host}?{qs}")
            if data and data.get("products") is not None:
                break
        if not data or not data.get("products"):
            print(f"  Seite {page}: keine Produkte mehr / API down – Stop.", flush=True)
            break
        if total_reported is None:
            total_reported = data.get("count")
            print(f"  OFF meldet ~{total_reported} Produkte für {slug}", flush=True)
        batch = 0
        for p in data["products"]:
            m = map_product(p)
            if m is None:
                continue
            kept.append(m)
            batch += 1
            if max_products and len(kept) >= max_products:
                break
        print(f"  Seite {page}: +{batch} behalten (gesamt {len(kept)})", flush=True)
        page_count = int(data.get("page_count") or 0)
        if page >= page_count or not data["products"]:
            break
        page += 1
        time.sleep(REQUEST_PAUSE_S)
    return kept


def fetch_dump(path: str | None, dump_url: str | None, wanted: set[str], max_products: int) -> list[dict]:
    if path:
        src = gzip.open(path, "rt", encoding="utf-8", errors="replace")
    else:
        url = dump_url or DUMP_URL
        print(f"  Lade Dump {url} …", flush=True)
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        resp = urllib.request.urlopen(req, timeout=120)
        src = gzip.open(resp, "rt", encoding="utf-8", errors="replace")

    kept: list[dict] = []
    total = 0
    with src:
        for line in src:
            total += 1
            if total % 100_000 == 0:
                print(f"  {total} Zeilen, {len(kept)} behalten", flush=True)
            try:
                p = json.loads(line)
            except ValueError:
                continue
            if not wanted.intersection(p.get("countries_tags") or []):
                continue
            m = map_product(p)
            if m is None:
                continue
            kept.append(m)
            if max_products and len(kept) >= max_products * 3:
                break
    print(f"  Dump: {len(kept)} Kandidaten aus {total} Zeilen", flush=True)
    return kept


def dedup_sort(products: list[dict], max_products: int) -> list[dict]:
    seen_bc: set[str] = set()
    seen_key: set[str] = set()
    ranked: list[tuple[int, dict]] = []
    for m in products:
        bc = m.get("barcode")
        if bc and bc in seen_bc:
            continue
        key = f"{m['name'].lower()}|{(m.get('brand') or '').lower()}"
        if key in seen_key:
            continue
        if bc:
            seen_bc.add(bc)
        seen_key.add(key)
        scans = int(m.pop("_scans", 0) or 0)
        ranked.append((scans, m))
    ranked.sort(key=lambda t: t[0], reverse=True)
    if max_products and len(ranked) > max_products:
        ranked = ranked[:max_products]
    return [m for _, m in ranked]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--countries", default="switzerland")
    ap.add_argument("--max", type=int, default=60000)
    ap.add_argument("--input", help="Lokaler JSONL.GZ-Dump")
    ap.add_argument("--dump-url", help="Dump-URL statt API (langsam)")
    ap.add_argument("--prefer-dump", action="store_true", help="Dump vor API versuchen")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    wanted = set()
    countries = []
    for c in args.countries.split(","):
        c = c.strip().lower()
        if c not in COUNTRY_TAGS:
            sys.exit(f"Unbekanntes Land: {c}")
        wanted.add(COUNTRY_TAGS[c])
        countries.append(c)

    products: list[dict] = []
    use_dump = bool(args.input or args.dump_url or args.prefer_dump)

    if use_dump:
        try:
            products = fetch_dump(args.input, args.dump_url, wanted, args.max)
        except Exception as e:
            print(f"Dump fehlgeschlagen: {e!r} – fallback API", flush=True)
            products = []

    if len(products) < 50:
        print("Baue Katalog über REST-API …", flush=True)
        for c in countries:
            tag = COUNTRY_TAGS[c]
            print(f"Land: {c} ({tag})", flush=True)
            part = fetch_country_api(tag, args.max)
            products.extend(part)
            if args.max and len(products) >= args.max:
                break

    if not products:
        sys.exit("Keine Produkte gefunden (API und Dump leer/fehlerhaft).")

    final = dedup_sort(products, args.max)
    with gzip.open(args.out, "wt", encoding="utf-8", compresslevel=9) as out:
        for m in final:
            out.write(json.dumps(m, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"FERTIG: {len(final)} Produkte → {args.out}", flush=True)


if __name__ == "__main__":
    main()
