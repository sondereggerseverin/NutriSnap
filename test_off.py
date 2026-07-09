import json, urllib.request, urllib.parse, time

def search_off(name, brand):
    q = urllib.parse.quote(f"{name} {brand}".strip())
    url = f"https://world.openfoodfacts.org/cgi/search.pl?search_terms={q}&search_simple=1&action=process&json=1&page_size=3"
    req = urllib.request.Request(url)
    req.add_header("User-Agent", "NutriSnap/1.0")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read())
        products = data.get("products", [])
        if products:
            p = products[0]
            return p.get("code", "-"), p.get("product_name", "?")
        return "-", "nicht gefunden"
    except:
        return "-", "Fehler"

# Testprodukte aus deiner Liste
test = [
    ("Snickers", "Mars"),
    ("Ovomaltine crunchy cream", "Wander"),
    ("Biscoff Aufstrich", "Lotus"),
    ("Dar Vida Gruyere", "Darvida"),
    ("Ice Tea Lemon", "Lipton"),
]

print(f"{'Name':<30} {'Brand':<15} {'Barcode (OFF)':<16} {'OFF-Name'}")
print("-" * 85)
for name, brand in test:
    time.sleep(0.5)
    barcode, off_name = search_off(name, brand)
    print(f"{name:<30} {brand:<15} {barcode:<16} {off_name[:25]}")
