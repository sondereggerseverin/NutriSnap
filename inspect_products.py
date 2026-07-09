import json
data = json.load(open("raw_06jul.json", encoding="utf-8"))
print("=== products (erster Eintrag) ===")
p = data["products"][0]
for k, v in p.items():
    print(f"  {k}: {repr(v)[:80]}")
print()
print("=== Alle product-Namen ===")
for p in data["products"]:
    print(f"  {p.get('daytime','?'):12s} | {p.get('name','?')[:35]:35s} | nutrients: {type(p.get('nutrients')).__name__}")
