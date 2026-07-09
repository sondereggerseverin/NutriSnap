import csv
rows=[r for r in csv.DictReader(open('nutrition_log.csv',encoding='utf-8')) if r['date']=='2026-07-06']
total_kcal=sum(float(r.get('kcal') or 0) for r in rows)
total_prot=sum(float(r.get('protein_g') or 0) for r in rows)
total_carb=sum(float(r.get('carbs_g') or 0) for r in rows)
total_fat=sum(float(r.get('fat_g') or 0) for r in rows)
print(f"{len(rows)} Eintraege am 06.07.2026:")
for r in rows: print(f"  {r['meal']:15s} | {r['name'][:35]:35s} | {float(r.get('kcal') or 0):.0f} kcal")
print()
print(f"SUMME LOG:  {round(total_kcal,1)} kcal  P:{round(total_prot,1)}g  K:{round(total_carb,1)}g  F:{round(total_fat,1)}g")
print(f"Yazio App:  2918 kcal  P:181g  K:254g  F:121g")
print(f"Differenz:  {round(2918-total_kcal,1)} kcal")
