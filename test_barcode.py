import json, urllib.request, ssl, getpass, time

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE
BASE = "https://yzapi.yazio.com"
CLIENT_ID     = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

def api(path, token=None):
    time.sleep(0.3)
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
print("Login OK\n")

# Letzten 7 Tage scannen
from datetime import date, timedelta
tage = [(date.today() - timedelta(days=i)).isoformat() for i in range(7)]

product_ids = set()
for day in tage:
    data = api(f"/v9/user/consumed-items?date={day}", token=token)
    if not data: continue
    for item in data.get("products", []):
        pid = item.get("product_id") or item.get("id")
        if pid: product_ids.add(str(pid))

print(f"{len(product_ids)} Produkt-IDs aus letzter Woche gefunden\n")

# Details laden und Barcode pruefen
print(f"{'Name':<35} {'Brand':<20} {'Barcode':<15} {'kcal/100g'}")
print("-" * 80)
found = no_barcode = 0
for pid in product_ids:
    p = api(f"/v9/products/{pid}", token=token)
    if not p: continue
    name    = (p.get("name") or "?")[:34]
    brand   = (p.get("producer") or "-")[:19]
    barcode = p.get("ean") or "-"
    n = p.get("nutrients") or {}
    if isinstance(n, list): n = {e["name"]: e["value"] for e in n if "name" in e}
    kcal = round(float(n.get("energy.energy") or 0) * 100, 1)
    print(f"{name:<35} {brand:<20} {barcode:<15} {kcal}")
    if barcode != "-": found += 1
    else: no_barcode += 1

print()
print(f"Mit Barcode: {found}  |  Ohne Barcode: {no_barcode}")
