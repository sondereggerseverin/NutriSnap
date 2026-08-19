#!/usr/bin/env python3
"""
Zieht Eigenmarken-Produkte eines Schweizer Retailers von OpenFoodFacts (ODbL-lizenziert,
frei nutzbar mit Attribution) und mappt sie ins NutriSnap-Katalogschema (identisch zu
app/src/main/assets/yazio_foods.json).

Warum OpenFoodFacts statt Retailer-Website-Scraping:
- Migros/Aldi/Lidl/Coop bieten keine offene Produkt-API; Website-Scraping ist ToS-Risiko,
  bricht bei Layout-Änderungen und liefert keine strukturierten Barcodes.
- OFF ist crowdsourced, aber für Schweizer Einzelhandelsprodukte inzwischen gut befüllt
  (Nutzer scannen beim Einkauf), enthält Barcode + Nährwerte + Markentags, frei exportierbar.

Nutzung:
    python3 fetch_off_ch_brand.py --brand migros --out ../app/src/main/assets/off_migros_ch.json
    python3 fetch_off_ch_brand.py --brand aldi-suisse --out ../app/src/main/assets/off_aldi_ch.json

Quelle/Lizenz: https://world.openfoodfacts.org (Open Database License, ODbL 1.0)
"""
import argparse
import json
import sys
import time
import urllib.request
import urllib.parse

USER_AGENT = "NutriSnap-Android/1.0 (github.com/sondereggerseverin/NutriSnap; food catalog import)"
API_BASE = "https://world.openfoodfacts.org/api/v2/search"
FIELDS = "code,product_name,product_name_de,brands,brands_tags,categories_tags,quantity,nutriments"
PAGE_SIZE = 100
MAX_PAGES = 50  # Sicherheitslimit, ~5000 Produkte pro Marke


def fetch_page(brand_tag: str, page: int) -> dict:
    params = {
        "brands_tags": brand_tag,
        "countries_tags": "switzerland",
        "fields": FIELDS,
        "page_size": PAGE_SIZE,
        "page": page,
        "json": "1",
    }
    url = f"{API_BASE}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8")), url


def nutri(nutriments: dict, *keys, default=0.0) -> float:
    """Nimmt den ersten vorhandenen *_100g-Wert aus einer Key-Kandidatenliste."""
    for k in keys:
        v = nutriments.get(k)
        if v is not None:
            try:
                return float(v)
            except (TypeError, ValueError):
                pass
    return default


def map_product(p: dict, brand_label: str) -> dict | None:
    name = (p.get("product_name_de") or p.get("product_name") or "").strip()
    if not name:
        return None  # kein Titel -> für Suche/Anzeige unbrauchbar, ueberspringen
    n = p.get("nutriments") or {}
    calories_kcal = nutri(n, "energy-kcal_100g")
    if calories_kcal == 0.0:
        kj = nutri(n, "energy_100g", "energy-kj_100g")
        calories_kcal = round(kj / 4.184, 1) if kj else 0.0
    if calories_kcal <= 0:
        return None  # kein Kalorienwert -> Datensatz für Tracking unbrauchbar

    return {
        "name": name[:120],
        "brand": brand_label,
        "barcode": (p.get("code") or "").strip() or None,
        "caloriesPer100g": round(calories_kcal, 1),
        "proteinPer100g": round(nutri(n, "proteins_100g"), 1),
        "carbsPer100g": round(nutri(n, "carbohydrates_100g"), 1),
        "fatPer100g": round(nutri(n, "fat_100g"), 1),
        "fiberPer100g": round(nutri(n, "fiber_100g"), 1),
        "sugarPer100g": round(nutri(n, "sugars_100g"), 1),
        "saltPer100g": round(nutri(n, "salt_100g"), 1),
        "category": (p.get("categories_tags") or [None])[-1],
        "imageUrl": None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--brand", required=True, help="OFF brands_tags Wert, z.B. 'migros', 'aldi-suisse'")
    ap.add_argument("--label", required=True, help="Anzeigename der Marke, z.B. 'Migros'")
    ap.add_argument("--out", required=True, help="Ziel-JSON-Pfad")
    args = ap.parse_args()

    all_products = []
    seen_barcodes = set()
    page = 1
    total_pages_reported = None

    while page <= MAX_PAGES:
        try:
            data, url = fetch_page(args.brand, page)
        except Exception as e:
            print(f"FEHLER bei Seite {page}: {e}", file=sys.stderr)
            print(f"URL war: {url if 'url' in dir() else '?'}", file=sys.stderr)
            sys.exit(1)

        products = data.get("products", [])
        count = data.get("count", 0)
        if total_pages_reported is None:
            total_pages_reported = -(-count // PAGE_SIZE)  # ceil
            print(f"OFF meldet {count} Treffer für brands_tags={args.brand} + CH (~{total_pages_reported} Seiten)")

        if not products:
            break

        for p in products:
            mapped = map_product(p, args.label)
            if mapped is None:
                continue
            bc = mapped["barcode"]
            if bc and bc in seen_barcodes:
                continue
            if bc:
                seen_barcodes.add(bc)
            all_products.append(mapped)

        print(f"  Seite {page}: {len(products)} roh, {len(all_products)} kumuliert brauchbar")

        if len(products) < PAGE_SIZE:
            break
        page += 1
        time.sleep(1)  # fair use / rate limit gegenüber OFF

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(all_products, f, ensure_ascii=False, indent=2)

    print(f"FERTIG: {len(all_products)} Produkte -> {args.out}")


if __name__ == "__main__":
    main()
