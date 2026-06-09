# NutriSnap 🥗

Eine Android-App die **Kalorientracking** mit **Rezept-Import per Link** (Instagram, TikTok, Webseiten) kombiniert.

## Features

| Feature | Details |
|---|---|
| 📊 Kalorientracker | Mahlzeiten nach Frühstück / Mittag / Abend / Snack erfassen |
| 🔍 Lebensmittelsuche | OpenFoodFacts-Datenbank (kostenlos, kein API-Key nötig) |
| 🍽 Makro-Übersicht | Kalorien, Protein, Kohlenhydrate, Fett – täglich mit Fortschrittsbalken |
| 📅 Tages-Navigation | Vor/zurück blättern |
| 🔗 Rezept-Import | URL aus Instagram, TikTok oder beliebigen Rezeptseiten einfügen |
| 📤 Share-Target | In Instagram auf "Teilen" → NutriSnap öffnet sich direkt |
| 🗃 Offline | Alle Daten lokal in Room-DB gespeichert |

## Aufbau

```
app/src/main/kotlin/ch/nutrisnap/app/
├── data/
│   ├── db/          Room DAOs & Database
│   ├── model/       Datenmodelle (FoodItem, DiaryEntry, Recipe)
│   └── repository/  Repos (Diary, Recipe, FoodSearch)
├── domain/
│   └── RecipeScraper.kt   Jsoup-basierter Scraper (Instagram og-tags + schema.org JSON-LD)
├── ui/
│   ├── screens/
│   │   ├── diary/   Tagebuch-Screen + ViewModel
│   │   └── recipes/ Rezepte-Screen + ViewModel
│   ├── components/  MacroBar, SectionHeader, EmptyState
│   └── theme/       Farben & MaterialTheme
└── MainActivity.kt  Navigation + Share-Intent-Handling
```

## APK bauen (lokal)

```bash
# Debug APK
./gradlew assembleDebug

# APK liegt dann unter:
# app/build/outputs/apk/debug/app-debug.apk
```

## APK per GitHub Actions

1. Dieses Repo auf GitHub pushen
2. Unter **Actions → Build APK** siehst du den Build-Status
3. Nach erfolgreichem Build: **Artifacts → NutriSnap-debug** → APK herunterladen
4. APK auf das Handy übertragen und installieren (`Unbekannte Quellen` erlauben)

## Rezepte importieren

**Option 1: Direkteingabe**  
Rezepte-Tab → FAB (Link-Symbol) → URL einfügen → Importieren

**Option 2: Share-Intent (bequemer)**  
In Instagram/Browser auf "Teilen" tippen → NutriSnap auswählen → App öffnet sich mit vorausgefüllter URL

## Nächste Schritte (optional)

- [ ] Barcode-Scanner mit `ml-kit barcode-scanning`
- [ ] Wochenstatistik-Diagramm
- [ ] Wassertrinken-Tracker
- [ ] Eigene Rezept-Notizen / Fotos
