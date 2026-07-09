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

import getpass
email = input("Email: ").strip()
pw = getpass.getpass("Passwort: ")

req = urllib.request.Request(BASE + "/v9/oauth/token", method="POST")
req.add_header("Content-Type", "application/json")
req.data = json.dumps({"client_id":CLIENT_ID,"client_secret":CLIENT_SECRET,
    "username":email,"password":pw,"grant_type":"password"}).encode()
with urllib.request.urlopen(req, context=ssl_ctx) as r:
    token = json.loads(r.read())["access_token"]

data = api("/v9/user/consumed-items?date=2026-07-06", token=token)

print("=== ALLE KEYS IM RESPONSE ===")
print(list(data.keys()))

print()
print("=== ERSTES product_portions ITEM (alle Keys) ===")
items = data.get("product_portions", [])
if items:
    print(json.dumps(items[0], indent=2, ensure_ascii=False))
