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

# Snickers - ein bekanntes Markenprodukt mit sicher vorhandenem Barcode
data = api("/v9/user/consumed-items?date=2026-07-06", token=token)
pid = None
for item in data.get("products", []):
    p = api(f"/v9/products/{item.get('product_id')}", token=token)
    if p and "snickers" in (p.get("name") or "").lower():
        pid = item.get("product_id")
        print("=== ALLE FELDER fuer Snickers ===")
        for k, v in p.items():
            print(f"  {k}: {repr(v)[:100]}")
        break

if not pid:
    print("Snickers nicht gefunden, zeige erstes Produkt:")
    item = data.get("products", [])[0]
    p = api(f"/v9/products/{item.get('product_id')}", token=token)
    for k, v in p.items():
        print(f"  {k}: {repr(v)[:100]}")
