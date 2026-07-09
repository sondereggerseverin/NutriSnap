import json, csv, urllib.request, urllib.parse, ssl, getpass, time

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE

BASE          = "https://yzapi.yazio.com"
CLIENT_ID     = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

def api(path, token=None, retries=3):
    req = urllib.request.Request(BASE + path)
    if token: req.add_header("Authorization", "Bearer " + token)
    for attempt in range(retries):
        try:
            time.sleep(0.35)
            with urllib.request.urlopen(req, context=ssl_ctx, timeout=20) as r:
                return json.loads(r.read())
        except KeyboardInterrupt: raise
        except: 
            if attempt < retries-1: time.sleep(1)
    return None

def search_off(name, brand, retries=2):
    q = urllib.parse.quote(f"{name} {brand}".strip())
    url = f"https://world.openfoodfacts.org/cgi/search.pl?search_terms={q}&search_simple=1&action=process&json=1&page_size=3"
    req = urllib.request.Request(url)
    req.add_header("User-Agent", "NutriSnap/1.0")
    for attempt in range(retries):
        try:
            time.sleep(0.5)
            with urllib.request.urlopen(req, context=ssl_ctx, timeout=10) as r:
                data = json.loads(r.read())
            products = data.get("products", [])
            if products:
                p = products[0]
                return p.get("code", ""), p.get("product_name", "")
            return "", ""
        except KeyboardInterrupt: raise
        except:
            if attempt < retries-1: time.sleep(2)
    return "", ""

def norm_n(raw):
    if isinstance(raw, list): return {e["name"]: e["value"] for e in raw if "name" in e}
    return raw or {}

# Login
email = input("Email: ").strip()
pw    = getpass.getpass("Passwort: ")
req = urllib.request.Request(BASE + "/v9/oauth/token", method="POST")
req.add_header("Content-Type", "application/json")
req.data = json.dumps({"client_id":CLIENT_ID,"client_secret":CLIENT_SECRET,
    "username":email,"password":pw,"grant_type":"password"}).encode()
with urllib.request.urlopen(req, context=ssl_ctx) as r:
    token = json.loads(r.read())["access_token"]
print("Login OK\n")

# Alle Diary-Tage laden
from datetime import date, timedelta, datetime
user = api("/v9/user", token=token) or {}
reg  = datetime.strptime(user.get("registration_date","2023-01-01 00:00:00").split(" ")[0], "%Y-%m-%d").date()
today = date.today()
all_dates = [(reg + timedelta(days=i)).isoformat() for i in range((today-reg).days+1)]

print(f"Scanne {len(all_dates)} Tage fuer Produkt-IDs ...")
prod_ids = set()
for i, day in enumerate(all_dates):
    if i % 60 == 0: print(f"  ... {day}")
    data = api(f"/v9/user/consumed-items?date={day}", token=token)
    if not data: continue
    for item in data.get("products", []):
        pid = item.get("product_id") or item.get("id")
        if pid: prod_ids.add(str(pid))

print(f"\n{len(prod_ids)} einzigartige Produkte gefunden. Lade Details ...\n")

# Produkt-Details laden
prod_cache = {}
for j, pid in enumerate(sorted(prod_ids)):
    if j % 25 == 0: print(f"  ...{j}/{len(prod_ids)}")
    p = api(f"/v9/products/{pid}", token=token)
    if p: prod_cache[pid] = p

print(f"\n{len(prod_cache)} Produkte geladen. Starte OFF Barcode-Lookup ...\n")

# OFF Lookup + CSV schreiben
fields = ["yazio_id","name","brand","category","barcode_off","off_name_match",
          "has_ean_yazio","kcal_per_100g","protein_per_100g","carbs_per_100g",
          "fat_per_100g","fiber_per_100g","sugar_per_100g"]

rows = []
found = 0
for j, (pid, p) in enumerate(prod_cache.items()):
    name    = p.get("name") or ""
    brand   = p.get("producer") or ""
    cat     = p.get("category") or ""
    has_ean = p.get("has_ean", False)
    n       = norm_n(p.get("nutrients", {}))
    kcal    = round(float(n.get("energy.energy") or 0) * 100, 2)
    prot    = round(float(n.get("nutrient.protein") or 0) * 100, 2)
    carb    = round(float(n.get("nutrient.carb") or 0) * 100, 2)
    fat     = round(float(n.get("nutrient.fat") or 0) * 100, 2)
    fib     = round(float(n.get("nutrient.dietaryfiber") or 0) * 100, 2)
    sug     = round(float(n.get("nutrient.sugar") or 0) * 100, 2)

    barcode = off_match = ""
    if name:
        barcode, off_match = search_off(name, brand)
        if barcode: found += 1

    status = "✅" if barcode else ("⚠️ has_ean" if has_ean else "❌")
    print(f"  [{j+1:3d}/{len(prod_cache)}] {status} {name[:35]:35s} | {barcode or '-'}")

    rows.append({"yazio_id":pid,"name":name,"brand":brand,"category":cat,
        "barcode_off":barcode,"off_name_match":off_match[:60],
        "has_ean_yazio":has_ean,"kcal_per_100g":kcal,"protein_per_100g":prot,
        "carbs_per_100g":carb,"fat_per_100g":fat,"fiber_per_100g":fib,"sugar_per_100g":sug})

with open("all_products_with_barcodes.csv","w",newline="",encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=fields)
    w.writeheader(); w.writerows(rows)

print(f"\n{'='*55}")
print(f"  Gespeichert: all_products_with_barcodes.csv")
print(f"  Produkte total:       {len(rows)}")
print(f"  Mit Barcode (OFF):    {found}")
print(f"  Ohne Barcode:         {len(rows)-found}")
print(f"{'='*55}")
