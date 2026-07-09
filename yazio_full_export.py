"""
yazio_full_export.py  â€“  v3  (2-Pass)
======================================
Pass 1: Diary scannen â†’ alle Produkt- & Rezept-IDs sammeln
Pass 2: Details laden, NÃ¤hrwerte berechnen, CSVs + JSONs schreiben

Ausgabe:
  nutrition_log.csv    â€“ jeder Diary-Eintrag
  daily_summary.csv    â€“ Tagessummen
  meal_summary.csv     â€“ pro Mahlzeit pro Tag
  yazio_recipes.json   â€“ eigene Rezepte
  yazio_foods.json     â€“ manuell / gescannte Produkte (per 100g)
"""

import json, csv, urllib.request, urllib.error, ssl, getpass
from datetime import date, timedelta, datetime
from collections import defaultdict

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE

BASE          = "https://yzapi.yazio.com"
CLIENT_ID     = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

MEAL = {"breakfast":"FrÃ¼hstÃ¼ck","lunch":"Mittagessen","dinner":"Abendessen","snack":"Snack"}

def api(path, token=None, method="GET", body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:  req.add_header("Authorization", "Bearer " + token)
    if body:
        req.add_header("Content-Type", "application/json")
        req.data = body.encode()
    try:
        with urllib.request.urlopen(req, context=ssl_ctx) as r:
            return json.loads(r.read())
    except Exception:
        return None

def login(email, pw):
    resp = api("/v9/oauth/token", method="POST", body=json.dumps({
        "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
        "username": email, "password": pw, "grant_type": "password"}))
    return resp.get("access_token") if resp else None

def norm_n(raw):
    """nutrients: list of {name,value} OR dict â†’ dict"""
    if isinstance(raw, list):
        return {e["name"]: e["value"] for e in raw if "name" in e}
    return raw or {}

def v(n, key, factor=1.0):
    return round(float(n.get(key) or 0) * factor, 2)

# â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def main():
    print("=" * 56)
    print("  Yazio Full Export v3  â€“  Foods Â· Rezepte Â· Diary")
    print("=" * 56)

    email = input("\nEmail: ").strip()
    try:    pw = getpass.getpass("Passwort: ")
    except: pw = input("Passwort: ")

    print("\nðŸ”‘ Einloggen ...")
    token = login(email, pw)
    if not token:
        print("âŒ Login fehlgeschlagen.")
        input("\nEnter ..."); return
    print("âœ… Login OK!\n")

    user     = api("/v9/user", token=token) or {}
    reg_str  = user.get("registration_date", "2023-01-01 00:00:00")
    reg_date = datetime.strptime(reg_str.split(" ")[0], "%Y-%m-%d").date()
    today    = date.today()
    print(f"ðŸ‘¤ {user.get('email', email)}")
    print(f"ðŸ“… Mitglied seit {reg_date}  ({(today-reg_date).days} Tage)\n")

    all_dates = []
    d = reg_date
    while d <= today:
        all_dates.append(d.isoformat()); d += timedelta(days=1)

    # â”€â”€ PASS 1: Diary scannen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print("ðŸ“– Pass 1 â€“ Diary scannen ...")
    diary_by_day = {}
    prod_ids_diary = set()
    rec_ids_diary  = set()

    for i, day in enumerate(all_dates):
        if i % 60 == 0: print(f"  ... {day}")
        data = api(f"/v9/user/consumed-items?date={day}", token=token)
        if not data: continue
        diary_by_day[day] = data
        for item in data.get("products", []):
            pid = item.get("product_id") or item.get("id")
            if pid: prod_ids_diary.add(str(pid))
        for item in data.get("recipe_portions", []):
            rid = item.get("recipe_id") or item.get("id")
            if rid: rec_ids_diary.add(str(rid))

    print(f"  â†’ {len(diary_by_day)} Tage mit Daten, "
          f"{len(prod_ids_diary)} Produkte, {len(rec_ids_diary)} Rezepte\n")

    # â”€â”€ PASS 2a: Produkt-Details laden â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print(f"ðŸ” Lade {len(prod_ids_diary)} Produkt-Details ...")
    prod_cache = {}
    for j, pid in enumerate(prod_ids_diary):
        if j % 50 == 0: print(f"  ...{j}/{len(prod_ids_diary)}")
        p = api(f"/v9/products/{pid}", token=token)
        if p: prod_cache[pid] = p

    # â”€â”€ PASS 2b: Rezept-Details laden â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print(f"ðŸ” Lade {len(rec_ids_diary)} Rezept-Details ...")
    rec_cache = {}
    for j, rid in enumerate(rec_ids_diary):
        if j % 50 == 0: print(f"  ...{j}/{len(rec_ids_diary)}")
        r = api(f"/v9/recipes/{rid}", token=token)
        if r: rec_cache[rid] = r

    # â”€â”€ PASS 3: eigene User-Produkte (manuell / Barcode) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print("\nðŸ“¦ Lade eigene/manuell angelegte Produkte ...")
    user_prod_ids = api("/v9/user/products", token=token) or []
    print(f"  {len(user_prod_ids)} IDs gefunden.")
    for j, pid in enumerate(user_prod_ids):
        if j % 50 == 0: print(f"  ...{j}/{len(user_prod_ids)}")
        pid = str(pid)
        if pid not in prod_cache:
            p = api(f"/v9/products/{pid}", token=token)
            if p: prod_cache[pid] = p

    # â”€â”€ Diary verarbeiten â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print("\nðŸ“Š Verarbeite Diary ...")
    log_rows   = []
    daily_acc  = defaultdict(lambda: dict(kcal=0,protein=0,carbs=0,fat=0,fiber=0,sugar=0))
    meal_acc   = defaultdict(lambda: dict(kcal=0,protein=0,carbs=0,fat=0,fiber=0,sugar=0))

    for day, data in sorted(diary_by_day.items()):

        for item in data.get("products", []):
            pid      = str(item.get("product_id") or item.get("id") or "")
            meal_key = item.get("meal_type") or item.get("daytime") or "snack"
            amount_g = float(item.get("amount") or 0)

            # NÃ¤hrwerte: zuerst direkt im Eintrag, dann aus Cache (per gram * amount)
            raw_n = item.get("nutrients")
            if raw_n:
                n = norm_n(raw_n)
                kcal=v(n,"energy.energy"); prot=v(n,"nutrient.protein")
                carb=v(n,"nutrient.carb"); fat=v(n,"nutrient.fat")
                fib=v(n,"nutrient.dietaryfiber"); sug=v(n,"nutrient.sugar")
            elif pid and amount_g:
                pc = prod_cache.get(pid, {})
                n  = norm_n(pc.get("nutrients", {}))
                # API speichert per-gram Werte
                kcal=v(n,"energy.energy",amount_g); prot=v(n,"nutrient.protein",amount_g)
                carb=v(n,"nutrient.carb",amount_g); fat=v(n,"nutrient.fat",amount_g)
                fib=v(n,"nutrient.dietaryfiber",amount_g); sug=v(n,"nutrient.sugar",amount_g)
            else:
                kcal=prot=carb=fat=fib=sug=0.0

            name  = item.get("name") or (prod_cache.get(pid) or {}).get("name","")
            brand = item.get("producer") or (prod_cache.get(pid) or {}).get("producer","") or ""

            log_rows.append(dict(date=day, meal=MEAL.get(meal_key,meal_key), type="product",
                name=name, brand=brand, amount_g=round(amount_g,1),
                kcal=kcal, protein_g=prot, carbs_g=carb, fat_g=fat, fiber_g=fib, sugar_g=sug,
                kcal_per_g=round(kcal/amount_g,5) if amount_g else 0,
                protein_per_g=round(prot/amount_g,5) if amount_g else 0,
                carbs_per_g=round(carb/amount_g,5) if amount_g else 0,
                fat_per_g=round(fat/amount_g,5) if amount_g else 0,
                fiber_per_g=round(fib/amount_g,5) if amount_g else 0))

            for k,vv in [("kcal",kcal),("protein",prot),("carbs",carb),("fat",fat),("fiber",fib),("sugar",sug)]:
                daily_acc[day][k]            = round(daily_acc[day][k]+vv, 2)
                meal_acc[(day,meal_key)][k]  = round(meal_acc[(day,meal_key)][k]+vv, 2)

        for item in data.get("recipe_portions", []):
            rid      = str(item.get("recipe_id") or item.get("id") or "")
            meal_key = item.get("meal_type") or item.get("daytime") or "snack"
            portions = float(item.get("portion_count") or 1)

            rc = rec_cache.get(rid, {})
            n  = norm_n(rc.get("nutrients", {}))
            # recipe nutrients = per 1 serving â†’ multiply by portions eaten
            kcal=v(n,"energy.energy",portions); prot=v(n,"nutrient.protein",portions)
            carb=v(n,"nutrient.carb",portions); fat=v(n,"nutrient.fat",portions)
            fib=v(n,"nutrient.dietaryfiber",portions); sug=v(n,"nutrient.sugar",portions)

            pw_g     = float(rc.get("portion_weight") or 0)
            amount_g = round(portions * pw_g, 1)
            name     = rc.get("name") or item.get("name","")

            log_rows.append(dict(date=day, meal=MEAL.get(meal_key,meal_key), type="recipe",
                name=name, brand="", amount_g=amount_g,
                kcal=kcal, protein_g=prot, carbs_g=carb, fat_g=fat, fiber_g=fib, sugar_g=sug,
                kcal_per_g=round(kcal/amount_g,5) if amount_g else 0,
                protein_per_g=round(prot/amount_g,5) if amount_g else 0,
                carbs_per_g=round(carb/amount_g,5) if amount_g else 0,
                fat_per_g=round(fat/amount_g,5) if amount_g else 0,
                fiber_per_g=round(fib/amount_g,5) if amount_g else 0))

            for k,vv in [("kcal",kcal),("protein",prot),("carbs",carb),("fat",fat),("fiber",fib),("sugar",sug)]:
                daily_acc[day][k]            = round(daily_acc[day][k]+vv, 2)
                meal_acc[(day,meal_key)][k]  = round(meal_acc[(day,meal_key)][k]+vv, 2)

    # â”€â”€ Rezept-Ausgabe-Liste â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    recipes = []
    for rid, r in rec_cache.items():
        n = norm_n(r.get("nutrients", {}))
        ingr = []
        for s in r.get("servings", []):
            ingr.append({"name":s.get("name",""),"amount":s.get("amount",0),
                         "unit":s.get("serving","g"),"producer":s.get("producer")})
        recipes.append({"id":rid,"title":r.get("name",""),
            "servings":r.get("portion_count",1),
            "caloriesPerServing":v(n,"energy.energy"),
            "proteinPerServing":v(n,"nutrient.protein"),
            "carbsPerServing":v(n,"nutrient.carb"),
            "fatPerServing":v(n,"nutrient.fat"),
            "fiberPerServing":v(n,"nutrient.dietaryfiber"),
            "imageUrl":r.get("image"),
            "ingredients":ingr})

    # â”€â”€ Foods-Ausgabe-Liste â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    foods = []
    for pid, p in prod_cache.items():
        if p.get("is_deleted"): continue
        n = norm_n(p.get("nutrients", {}))
        foods.append({"id":pid,"name":p.get("name",""),"brand":p.get("producer"),
            "barcode":p.get("ean"),"category":p.get("category"),
            "caloriesPer100g":v(n,"energy.energy",100),
            "proteinPer100g":v(n,"nutrient.protein",100),
            "carbsPer100g":v(n,"nutrient.carb",100),
            "fatPer100g":v(n,"nutrient.fat",100),
            "fiberPer100g":v(n,"nutrient.dietaryfiber",100),
            "sugarPer100g":v(n,"nutrient.sugar",100),
            "source":"manual" if p.get("is_custom") else "yazio",
            "imageUrl":p.get("image")})

    # â”€â”€ Dateien schreiben â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    print("ðŸ’¾ Schreibe Dateien ...")

    log_fields = ["date","meal","type","name","brand","amount_g","kcal","protein_g",
                  "carbs_g","fat_g","fiber_g","sugar_g","kcal_per_g","protein_per_g",
                  "carbs_per_g","fat_per_g","fiber_per_g"]
    with open("nutrition_log.csv","w",newline="",encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=log_fields); w.writeheader(); w.writerows(log_rows)

    daily_fields = ["date","kcal","protein_g","carbs_g","fat_g","fiber_g","sugar_g"]
    with open("daily_summary.csv","w",newline="",encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=daily_fields); w.writeheader()
        for day in sorted(daily_acc):
            a = daily_acc[day]
            w.writerow(dict(date=day,kcal=a["kcal"],protein_g=a["protein"],
                carbs_g=a["carbs"],fat_g=a["fat"],fiber_g=a["fiber"],sugar_g=a["sugar"]))

    meal_fields = ["date","meal","kcal","protein_g","carbs_g","fat_g","fiber_g","sugar_g"]
    with open("meal_summary.csv","w",newline="",encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=meal_fields); w.writeheader()
        for (day, mk) in sorted(meal_acc.keys()):
            a = meal_acc[(day,mk)]
            w.writerow(dict(date=day,meal=MEAL.get(mk,mk),kcal=a["kcal"],protein_g=a["protein"],
                carbs_g=a["carbs"],fat_g=a["fat"],fiber_g=a["fiber"],sugar_g=a["sugar"]))

    with open("yazio_recipes.json","w",encoding="utf-8") as f:
        json.dump(recipes, f, ensure_ascii=False, indent=2)
    with open("yazio_foods.json","w",encoding="utf-8") as f:
        json.dump(foods, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 56)
    print("  âœ…  EXPORT ABGESCHLOSSEN")
    print("=" * 56)
    print(f"  ðŸ“„ nutrition_log.csv   â†’  {len(log_rows)} Zeilen")
    print(f"  ðŸ“„ daily_summary.csv   â†’  {len(daily_acc)} Tage")
    print(f"  ðŸ“„ meal_summary.csv    â†’  {len(meal_acc)} Mahlzeiten")
    print(f"  ðŸ³ yazio_recipes.json  â†’  {len(recipes)} Rezepte")
    print(f"  ðŸ“¦ yazio_foods.json    â†’  {len(foods)} Produkte")
    print("=" * 56)

    # Zeige erste echte Zeile zur Verifikation
    non_zero = [r for r in log_rows if r["kcal"] > 0]
    if non_zero:
        s = non_zero[0]
        print(f"\nâœ… Verifikation (erster Eintrag mit Daten):")
        print(f"   {s['date']} | {s['meal']} | {s['name']} | {s['amount_g']}g | {s['kcal']} kcal | P:{s['protein_g']}g K:{s['carbs_g']}g F:{s['fat_g']}g")
    else:
        print("\nâš ï¸  Alle NÃ¤hrwerte sind 0 â€“ API-Struktur hat sich geÃ¤ndert.")
        print("   Bitte ersten Diary-Eintrag manuell prÃ¼fen.")

    input("\nEnter zum Beenden ...")

if __name__ == "__main__":
    main()

