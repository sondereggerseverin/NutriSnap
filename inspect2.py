import json, urllib.request, ssl, getpass

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE
BASE = "https://yzapi.yazio.com"
CLIENT_ID     = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

def api(path, token=None):
    req = urllib.request.Request(BASE + path)
    if token: req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, context=ssl_ctx, timeout=15) as r:
        return json.loads(r.read())

email = input("Email: ").strip()
pw = getpass.getpass("Passwort: ")

req = urllib.request.Request(BASE + "/v9/oauth/token", method="POST")
req.add_header("Content-Type", "application/json")
req.data = json.dumps({"client_id":CLIENT_ID,"client_secret":CLIENT_SECRET,
    "username":email,"password":pw,"grant_type":"password"}).encode()
with urllib.request.urlopen(req, context=ssl_ctx) as r:
    token = json.loads(r.read())["access_token"]

data = json.load(open("raw_06jul.json", encoding="utf-8"))

print("=== Lade Produkt-Details fuer alle product_ids ===")
total_kcal = total_prot = total_carb = total_fat = 0

def norm_n(raw):
    if isinstance(raw, list): return {e["name"]: e["value"] for e in raw if "name" in e}
    return raw or {}

def v(n, key, factor=1.0):
    return round(float(n.get(key) or 0) * factor, 2)

for item in data.get("products", []):
    pid      = item.get("product_id") or item.get("id")
    amount_g = float(item.get("amount") or 0)
    daytime  = item.get("daytime", "?")
    p = api(f"/v9/products/{pid}", token=token)
    if not p:
        print(f"  {daytime:10s} | {pid} -> NICHT GEFUNDEN")
        continue
    name = p.get("name", "?")
    n    = norm_n(p.get("nutrients", {}))
    kcal = v(n, "energy.energy",          amount_g)
    prot = v(n, "nutrient.protein",       amount_g)
    carb = v(n, "nutrient.carb",          amount_g)
    fat  = v(n, "nutrient.fat",           amount_g)
    print(f"  {daytime:10s} | {name[:35]:35s} | {amount_g:.0f}g | {kcal:.0f} kcal | P:{prot:.1f}g K:{carb:.1f}g F:{fat:.1f}g")
    total_kcal += kcal; total_prot += prot; total_carb += carb; total_fat += fat

print()
print("=== recipe_portions ===")
for item in data.get("recipe_portions", []):
    print(f"  {item.get('daytime','?'):10s} | recipe_id={item.get('recipe_id')} | portions={item.get('portion_count')}")

print()
print(f"Produkte Summe: {round(total_kcal,1)} kcal  P:{round(total_prot,1)}g  K:{round(total_carb,1)}g  F:{round(total_fat,1)}g")
print(f"Yazio App:      2918 kcal  P:181g  K:254g  F:121g")
