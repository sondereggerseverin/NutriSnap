import urllib.request, json

# Test 1: Einfacher HTTP-Test
try:
    with urllib.request.urlopen("https://world.openfoodfacts.org/api/v0/product/5000159484695.json", timeout=10) as r:
        data = json.loads(r.read())
        print("OFF direkt: OK ->", data.get("product", {}).get("product_name", "?"))
except Exception as e:
    print(f"OFF direkt: FEHLER -> {e}")

# Test 2: Via Barcode direkt (Snickers bekannter Barcode)
try:
    req = urllib.request.Request("https://world.openfoodfacts.org/api/v0/product/5000159484695.json")
    req.add_header("User-Agent", "NutriSnap/1.0 (test)")
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.loads(r.read())
        print("OFF mit Header: OK ->", data.get("product", {}).get("product_name", "?"))
except Exception as e:
    print(f"OFF mit Header: FEHLER -> {e}")

# Test 3: Google erreichbar?
try:
    with urllib.request.urlopen("https://www.google.com", timeout=5) as r:
        print(f"Google: OK ({r.status})")
except Exception as e:
    print(f"Google: FEHLER -> {e}")
