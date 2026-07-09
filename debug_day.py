import json, urllib.request, ssl, getpass

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE

BASE = "https://yzapi.yazio.com"
CLIENT_ID     = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"

email = input("Email: ").strip()
import getpass; pw = getpass.getpass("Passwort: ")

req = urllib.request.Request(BASE + "/v9/oauth/token", method="POST")
req.add_header("Content-Type", "application/json")
req.data = json.dumps({"client_id":CLIENT_ID,"client_secret":CLIENT_SECRET,
    "username":email,"password":pw,"grant_type":"password"}).encode()
with urllib.request.urlopen(req, context=ssl_ctx) as r:
    token = json.loads(r.read())["access_token"]

req2 = urllib.request.Request(BASE + "/v9/user/consumed-items?date=2026-07-06")
req2.add_header("Authorization", "Bearer " + token)
with urllib.request.urlopen(req2, context=ssl_ctx) as r:
    data = json.loads(r.read())

print("TOP-LEVEL KEYS:", list(data.keys()) if isinstance(data, dict) else type(data))
if isinstance(data, dict):
    for k, v in data.items():
        print(f"  {k}: {type(v).__name__} -> {len(v) if isinstance(v, list) else v}")
elif isinstance(data, list):
    print(f"  Liste mit {len(data)} Eintraegen")
    print("  Erster Eintrag keys:", list(data[0].keys()) if data else "leer")

with open("raw_06jul.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("\nGespeichert: raw_06jul.json")
