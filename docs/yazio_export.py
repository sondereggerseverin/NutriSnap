import json
import urllib.request
import urllib.error
import ssl
import getpass
from datetime import date, timedelta, datetime

ssl_context = ssl.create_default_context()
ssl_context.check_hostname = False
ssl_context.verify_mode = ssl.CERT_NONE

BASE = "https://yzapi.yazio.com"
CLIENT_ID = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

BARCODE_KEYS = ("ean", "ean13", "barcode", "gtin", "gtin13", "upc", "code")
# Feldname fuer die Produkt-ID INNERHALB eines consumed-items "products"-Eintrags
# ist nicht dokumentiert -> mehrere Kandidaten probieren.
PRODUCT_ITEM_ID_KEYS = ("product_id", "id", "productId", "food_id", "pid")


def request(path, token=None, method="GET", body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body:
        req.add_header("Content-Type", "application/json")
        req.data = body.encode()
    try:
        with urllib.request.urlopen(req, context=ssl_context) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError:
        return None


def login(email, password):
    body = json.dumps({
        "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
        "username": email, "password": password, "grant_type": "password"
    })
    resp = request("/v9/oauth/token", method="POST", body=body)
    return resp.get("access_token") if resp else None


def extract_barcode(p):
    """Sucht EAN/GTIN an möglichst vielen Stellen in der Yazio-Produktantwort."""
    for key in BARCODE_KEYS:
        v = p.get(key)
        if v:
            return str(v).strip()
    # Verschachtelte Identifier
    for parent_key in ("identifiers", "ids", "identifier", "product_identifiers", "codes"):
        sub = p.get(parent_key)
        if isinstance(sub, dict):
            for key in BARCODE_KEYS:
                v = sub.get(key)
                if v:
                    return str(v).strip()
        if isinstance(sub, list):
            for item in sub:
                if isinstance(item, dict):
                    for key in BARCODE_KEYS:
                        v = item.get(key)
                        if v:
                            return str(v).strip()
                    # generische code/value-Paare
                    if str(item.get("type", "")).lower() in ("ean", "ean13", "gtin", "gtin13", "upc", "barcode"):
                        v = item.get("value") or item.get("code")
                        if v:
                            return str(v).strip()
    # Flache Alternativ-Keys, die Yazio gelegentlich nutzt
    for key in ("product_ean", "product_barcode", "ean_code", "gtin_code", "external_id"):
        v = p.get(key)
        if v and str(v).strip().isdigit() and len(str(v).strip()) in (8, 12, 13, 14):
            return str(v).strip()
    # Deep-Scan: beliebige dict-Werte die wie EAN aussehen (8/12/13/14 Ziffern)
    def _walk(obj, depth=0):
        if depth > 4:
            return None
        if isinstance(obj, dict):
            for k, v in obj.items():
                kl = str(k).lower()
                if any(b in kl for b in ("ean", "gtin", "barcode", "upc")) and v:
                    s = str(v).strip()
                    if s.isdigit() and len(s) in (8, 12, 13, 14):
                        return s
                found = _walk(v, depth + 1)
                if found:
                    return found
        elif isinstance(obj, list):
            for item in obj:
                found = _walk(item, depth + 1)
                if found:
                    return found
        return None
    return _walk(p)


def extract_product_id(item):
    for key in PRODUCT_ITEM_ID_KEYS:
        v = item.get(key)
        if v:
            return v
    return None


def nutrient(n, key, factor=1.0):
    v = n.get(key)
    if v is None:
        return None
    return round(float(v) * factor, 1)


def csv_escape(val):
    s = "" if val is None else str(val)
    if any(c in s for c in (",", '"', "\n")):
        s = '"' + s.replace('"', '""') + '"'
    return s


def build_food_entry(p):
    n = p.get("nutrients", {})
    return {
        "name": p.get("name", "Unbekanntes Produkt"),
        "brand": p.get("producer"),
        "barcode": extract_barcode(p),
        # Naehrwerte hier sind PRO GRAMM -> fuer 100g mit *100
        "caloriesPer100g": nutrient(n, "energy.energy", 100) or 0,
        "proteinPer100g": nutrient(n, "nutrient.protein", 100) or 0,
        "carbsPer100g": nutrient(n, "nutrient.carb", 100) or 0,
        "fatPer100g": nutrient(n, "nutrient.fat", 100) or 0,
        "fiberPer100g": nutrient(n, "nutrient.dietaryfiber", 100) or 0,
        "sugarPer100g": nutrient(n, "nutrient.sugar", 100) or 0,
        "saltPer100g": nutrient(n, "nutrient.salt", 100) or nutrient(n, "nutrient.sodium", 100) or 0,
        "category": p.get("category"),
        "imageUrl": p.get("image"),
    }


def main():
    print("=== Yazio Vollstaendiger Rezepte + Produkte + Diary Export (v3) ===\n")
    email = input("Email: ").strip()
    try:
        password = getpass.getpass("Passwort: ")
    except Exception:
        password = ""
    if not password:
        password = input("Passwort: ")

    print("\n[*] Einloggen...")
    token = login(email, password)
    if not token:
        print("[!] Login fehlgeschlagen.")
        return
    print("[OK] Login erfolgreich!\n")

    user = request("/v9/user", token=token)
    reg_date_str = (user or {}).get("registration_date", "2023-01-01 00:00:00")
    reg_date = datetime.strptime(reg_date_str.split(" ")[0], "%Y-%m-%d").date()
    today = date.today()
    print(f"[i] Account erstellt: {reg_date}. Durchsuche kompletten Zeitraum ({(today - reg_date).days} Tage)...")
    print("    (das dauert eine Weile, eventuell mehrere Minuten)\n")

    dates = []
    d = reg_date
    while d <= today:
        dates.append(d.isoformat())
        d += timedelta(days=1)

    recipe_ids = set()
    diary_product_ids = set()
    days_raw = {}
    unrecognized_sample = []
    unresolvable_product_item_sample = []

    for i, day in enumerate(dates):
        if i % 60 == 0:
            print(f"  ...{day}")
        data = request(f"/v9/user/consumed-items?date={day}", token=token)
        if not data:
            continue
        days_raw[day] = data
        for item in data.get("recipe_portions", []):
            rid = item.get("recipe_id")
            if rid:
                recipe_ids.add(rid)
        for item in data.get("products", []):
            pid = extract_product_id(item)
            if pid:
                diary_product_ids.add(pid)
            elif len(unresolvable_product_item_sample) < 5:
                unresolvable_product_item_sample.append(item)
        known = {"recipe_portions", "products", "simple_products"}
        extra_keys = set(data.keys()) - known
        if extra_keys and len(unrecognized_sample) < 5:
            unrecognized_sample.append({"date": day, "keys": list(extra_keys), "raw": data})

    print(f"\n[*] {len(recipe_ids)} einzigartige Rezepte im Diary gefunden. Lade Details...")

    recipes = []
    recipes_by_id = {}
    for rid in recipe_ids:
        r = request(f"/v9/recipes/{rid}", token=token)
        if not r:
            continue
        n = r.get("nutrients", {})
        ingredients = []
        for s in r.get("servings", []):
            ingredients.append({
                "name": s.get("name", ""),
                "amount": s.get("amount", 0),
                "unit": s.get("serving", "g"),
                "producer": s.get("producer")
            })
        recipe_entry = {
            "title": r.get("name", "Unbenanntes Rezept"),
            "servings": r.get("portion_count", 1),
            "caloriesPerServing": nutrient(n, "energy.energy") or 0,
            "proteinPerServing": nutrient(n, "nutrient.protein") or 0,
            "carbsPerServing": nutrient(n, "nutrient.carb") or 0,
            "fatPerServing": nutrient(n, "nutrient.fat") or 0,
            "fiberPerServing": nutrient(n, "nutrient.dietaryfiber") or 0,
            "sugarPerServing": nutrient(n, "nutrient.sugar") or 0,
            "imageUrl": r.get("image"),
            "ingredients": ingredients
        }
        recipes.append(recipe_entry)
        recipes_by_id[rid] = recipe_entry

    # 2) Eigene Produkte (nur die vom User selbst manuell angelegten, i.d.R.
    # ohne Barcode) - separat, weil /v9/user/products NICHT alles zurueckgibt,
    # was im Diary gegessen wurde (das sind ueberwiegend Produkte aus dem
    # globalen Yazio-Katalog / Barcode-Scans, die dort nicht gelistet sind).
    print("\n[*] Lade eigene Produkte (manuell angelegt)...")
    own_product_ids = request("/v9/user/products", token=token) or []
    print(f"   {len(own_product_ids)} eigene Produkte gefunden.")

    foods_by_id = {}
    barcode_hits = 0
    failed_product_fetches = 0

    def fetch_and_store(pid):
        nonlocal barcode_hits, failed_product_fetches
        if pid in foods_by_id:
            return
        p = request(f"/v9/products/{pid}", token=token)
        if not p or p.get("is_deleted"):
            failed_product_fetches += 1
            return
        entry = build_food_entry(p)
        if entry["barcode"]:
            barcode_hits += 1
        foods_by_id[pid] = entry

    for i, pid in enumerate(own_product_ids):
        if i % 50 == 0:
            print(f"  ...eigene {i}/{len(own_product_ids)}")
        fetch_and_store(pid)

    # 3) ALLE im Diary tatsaechlich referenzierten Produkt-IDs abfragen,
    # unabhaengig davon ob sie in der 'eigenen Produkte'-Liste stehen.
    # Das ist der eigentliche Fix: beim letzten Lauf wurden 4813 von ~5500
    # Produkt-Eintraegen NICHT gefunden, weil nur gegen die 69 eigenen
    # Produkte abgeglichen wurde statt gegen den globalen Katalog.
    to_fetch = [pid for pid in diary_product_ids if pid not in foods_by_id]
    print(f"\n[*] {len(diary_product_ids)} einzigartige Produkte im Diary referenziert, davon {len(to_fetch)} noch unbekannt. Lade Details vom globalen Katalog...")
    for i, pid in enumerate(to_fetch):
        if i % 100 == 0:
            print(f"  ...diary-produkte {i}/{len(to_fetch)}")
        fetch_and_store(pid)

    foods = list(foods_by_id.values())

    # Debug: Rohstruktur der ersten Produkte ohne Barcode speichern,
    # damit fehlende EAN-Felder nachvollzogen werden können.
    no_bc_samples = []
    for pid, entry in foods_by_id.items():
        if entry.get("barcode"):
            continue
        raw = request(f"/v9/products/{pid}", token=token)
        if raw:
            no_bc_samples.append({"pid": pid, "keys": sorted(raw.keys()), "raw_top": {k: raw.get(k) for k in list(raw.keys())[:25]}})
        if len(no_bc_samples) >= 3:
            break
    if no_bc_samples:
        with open("yazio_barcode_debug.json", "w", encoding="utf-8") as f:
            json.dump(no_bc_samples, f, ensure_ascii=False, indent=2)
        print(f"   [i] {len(no_bc_samples)} Produkte ohne Barcode als yazio_barcode_debug.json abgelegt (Keys prüfen)")

    # 4) Komplettes Diary als CSV bauen.
    diary_rows = []
    unmatched_products = 0
    for day, data in days_raw.items():
        for item in data.get("recipe_portions", []):
            rid = item.get("recipe_id")
            rec = recipes_by_id.get(rid)
            if not rec:
                continue
            amount = float(item.get("amount", 1) or 1)
            meal = item.get("daytime", "snack")
            diary_rows.append([
                day, meal, rec["title"], f"{amount} Portion(en)",
                round(rec["caloriesPerServing"] * amount, 1),
                round(rec["proteinPerServing"] * amount, 1),
                round(rec["fatPerServing"] * amount, 1),
                round(rec["carbsPerServing"] * amount, 1),
            ])
        for item in data.get("products", []):
            pid = extract_product_id(item)
            food = foods_by_id.get(pid) if pid else None
            amount_g = float(item.get("amount", 0) or 0)
            meal = item.get("daytime", "snack")
            if food:
                diary_rows.append([
                    day, meal, food["name"], f"{amount_g}g",
                    round(food["caloriesPer100g"] * amount_g / 100, 1),
                    round(food["proteinPer100g"] * amount_g / 100, 1),
                    round(food["fatPer100g"] * amount_g / 100, 1),
                    round(food["carbsPer100g"] * amount_g / 100, 1),
                ])
            else:
                unmatched_products += 1
        for item in data.get("simple_products", []):
            n = item.get("nutrients", item)
            meal = item.get("daytime", "snack")
            diary_rows.append([
                day, meal, item.get("name", "Unbekannt"), f"{item.get('amount', 0)}g",
                nutrient(n, "energy.energy") or 0,
                nutrient(n, "nutrient.protein") or 0,
                nutrient(n, "nutrient.fat") or 0,
                nutrient(n, "nutrient.carb") or 0,
            ])

    with open("yazio_recipes.json", "w", encoding="utf-8") as f:
        json.dump(recipes, f, ensure_ascii=False, indent=2)

    with open("yazio_foods.json", "w", encoding="utf-8") as f:
        json.dump(foods, f, ensure_ascii=False, indent=2)

    with open("nutrition_log.csv", "w", encoding="utf-8-sig", newline="") as f:
        f.write("Datum,Mahlzeit,Produkt,Menge,Kalorien,Protein (g),Fett (g),Kohlenhydrate (g)\n")
        for row in diary_rows:
            f.write(",".join(csv_escape(v) for v in row) + "\n")

    debug_payload = {}
    if unrecognized_sample:
        debug_payload["unrecognized_top_level_keys_samples"] = unrecognized_sample
    if unresolvable_product_item_sample:
        debug_payload["unresolvable_product_item_sample"] = unresolvable_product_item_sample
    if unmatched_products:
        debug_payload["unmatched_product_entries"] = unmatched_products
    if failed_product_fetches:
        debug_payload["failed_product_detail_fetches"] = failed_product_fetches
    if foods:
        debug_payload["sample_raw_food_entry"] = foods[0]
    if debug_payload:
        with open("yazio_diary_debug.json", "w", encoding="utf-8") as f:
            json.dump(debug_payload, f, ensure_ascii=False, indent=2)

    print(f"\n[OK] Fertig!")
    print(f"   Rezepte -> yazio_recipes.json: {len(recipes)}")
    print(f"   Produkte (eigene + aus Diary) -> yazio_foods.json: {len(foods)} (davon mit Barcode: {barcode_hits})")
    if barcode_hits == 0 and foods:
        print("   [!] Keine Barcodes gefunden – yazio_barcode_debug.json prüfen und BARCODE_KEYS ggf. erweitern.")
    print(f"   Diary-Eintraege -> nutrition_log.csv: {len(diary_rows)}")
    if unmatched_products:
        print(f"   [i] {unmatched_products} Produkt-Eintraege weiterhin ohne Detail (siehe yazio_diary_debug.json)")
    if failed_product_fetches:
        print(f"   [i] {failed_product_fetches} Produkt-Detailabfragen fehlgeschlagen (geloeschte/nicht mehr verfuegbare Produkte?)")
    input("\nEnter druecken zum Beenden...")


if __name__ == "__main__":
    main()
