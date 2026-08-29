# Design-Audit NutriSnap (Stand: August 2026)

Analyse des aktuellen UI/Design-Systems (`ui/theme/Theme.kt`, alle `ui/screens/*`)
mit Fokus auf: was ist im Code wirklich wirksam, wo klafft eine Lücke zwischen
Anspruch und Umsetzung, und was sind die nächsten sinnvollen Schritte.

**Leitprinzip (unverändert):** Jede Design-Änderung nur hinter einem Settings-Toggle
(Mehr → Design), Default = aktuelles Verhalten, damit A/B-Vergleich am echten Gerät
möglich ist, bevor etwas fest verdrahtet wird.

---

## 1. Kritischer Befund: Ein Grossteil der bestehenden Design-Toggles ist tot

In `Mehr → Design` existieren bereits **23 Toggles** (Commits `a864e10`, `7912da3`,
`e0881e3`). Das Preference-Key + die Switch-Zeile in `SettingsScreen.kt` wurden für
alle 23 angelegt – aber nur **8 von 23** lesen den Wert irgendwo ausserhalb der
Settings-Seite. Die übrigen **15 Schalter tun beim Umlegen buchstäblich nichts**:
Sie schreiben einen Wert in den DataStore, den keine andere Composable je liest.

| # | Kategorie | Toggle | Default | Status | Verdrahtet in |
|---|---|---|---|---|---|
| 1 | Touch-Targets | Meal-Quick-Add (Home) | an | ✅ live | `HomeCards.kt` |
| 2 | Touch-Targets | Meal-Icon-Kreis (Home) | an | ✅ live | `HomeCards.kt` |
| 3 | Touch-Targets | Diary Zeilen-Icons | an | ✅ live | `DiaryScreen.kt` |
| 4 | Touch-Targets | Meal-Header Copy/Expand | an | ✅ live | `DiaryScreen.kt` |
| 5 | Touch-Targets | „Gestern übernehmen“ | an | ✅ live | `DiaryScreen.kt` |
| 6 | Touch-Targets | Rezept-Karten-Menü (⋮) | an | ✅ live | `RecipeCardV2.kt` |
| 7 | FABs & Buttons | Diary-FAB konsolidieren | aus | ✅ live | `DiaryScreen.kt` |
| 8 | Typografie/Nav | Nav-Label „Einstellungen“ | aus | ✅ live | `MainActivity.kt` |
| 9 | Farben | Macro-Farben absetzen | aus | ❌ tot | nur `SettingsScreen.kt` |
| 10 | Farben | Card-Elevation erhöhen | aus | 🔧 **teilweise verdrahtet** (s. §8) | `Components.kt` (`NutriCard`), Home-Screen migriert |
| 11 | Farben | Kontrast-Modus (Text auf Primärfarbe) | aus | 🔧 **verdrahtet** (s. §8) | `Theme.kt` (`toColorScheme`/`toDarkColorScheme`) |
| 12 | Farben | Cropper Theme-Farbe | **an** | ❌ tot + verwaist (s. §4.4) | nur `SettingsScreen.kt` |
| 13 | Farben | „Noch X kcal übrig“ hervorheben | aus | 🔧 **in dieser Session verdrahtet** | `HomeCards.kt` |
| 14 | Layout | Spacing-Tokens | an | ❌ tot | nur `SettingsScreen.kt` |
| 15 | Layout | Activity-Karten zusammenlegen | aus | ❌ tot | nur `SettingsScreen.kt` |
| 16 | Layout | Home-Reihenfolge neu | aus | ❌ tot | nur `SettingsScreen.kt` |
| 17 | Layout | Diary kompakter | aus | ❌ tot | nur `SettingsScreen.kt` |
| 18 | FABs & Buttons | Recipes-FABs konsolidieren | aus | ❌ tot | nur `SettingsScreen.kt` |
| 19 | FABs & Buttons | Button-Standardgrösse | an | ❌ tot | nur `SettingsScreen.kt` |
| 20 | FABs & Buttons | Portion-Chips grösser | aus | ❌ tot | nur `SettingsScreen.kt` |
| 21 | FABs & Buttons | Progress-Bar bei Überschreitung | aus | ❌ tot | nur `SettingsScreen.kt` |
| 22 | Typografie/Nav | Primärzahlen grösser | aus | ❌ tot | nur `SettingsScreen.kt` |
| 23 | Typografie/Nav | Nav-Shortcuts sichtbar | aus | ❌ tot | nur `SettingsScreen.kt` |

**Wichtig für dich:** Wenn du bisher #9–23 (ausser #13) im Design-Menü getestet
hast und keinen Unterschied gesehen hast – das lag nicht an dir, die Schalter
haben schlicht nichts gemacht. Das war der Grund, weshalb ich das zuerst
sauber auditiert habe, bevor ich weitere „neue“ Optimierungen vorschlage, die
im selben Loch landen würden.

Separat davon gibt es zwei **funktionierende** ältere Toggle-Familien, zum
Vergleich: die „Fresh UI“-Experimente (`KEY_FRESH_UI/_HOME/_RECIPE_CARDS/_RECIPE_DETAIL`)
und die Rezept-Grid-Einstellungen (`KEY_CLASSIC_RECIPE_LIST`, `KEY_RECIPE_GRID_*`) –
beide sind korrekt in `RecipesScreen.kt`/`HomeScreen.kt`/`HomeCards.kt` verdrahtet
und schalten sichtbar um.

---

## 2. Design-System-Überblick (`ui/theme/Theme.kt`)

- **Farben:** 10 wählbare Themes (Rot/Orange/Gelb/Grün/Türkis/Blau/Lila/Rosa/Grau/Schwarz)
  + 3 Legacy-Themes (Mint/Golden/Citrus, nur für DataStore-Kompatibilität, nicht im Picker).
  Jedes Theme definiert `primary/primaryDark/primaryLight/accent/accentLight/background`.
- **Typografie:** Vollständige Material-3-Skala (`displayLarge` … `labelSmall`), konsistent
  mit Gewicht/Grösse/Letter-Spacing definiert – das Typo-System selbst ist sauber, das
  Problem liegt eher darin, ob Screens die Skala auch benutzen (s. §5.1).
- **Spacing/Radius-Tokens:** `NutriSpacing` (4–32 dp) und `NutriRadius` (8–24 dp) existieren,
  werden aber unterproportional genutzt (s. §5.1).
- **Dark Mode:** Existiert vollständig (`toDarkColorScheme()`), folgt aber 1:1 der
  System-Einstellung (`isSystemInDarkTheme()`), kein manueller Override in der App.

---

## 3. Bereits vorhandene Toggle-Familien im Überblick

| Familie | Zweck | Status |
|---|---|---|
| Rezept-Import-Experimente (`KEY_RECIPE_FAST_AI_PARSE` etc.) | Performance, nicht Design | ✅ live (separates Thema, siehe Performance-Backlog) |
| Fresh UI (`KEY_FRESH_*`) | Alternatives Home-/Rezept-Detail-Design | ✅ live |
| Rezept-Grid (`KEY_CLASSIC_RECIPE_LIST`, `KEY_RECIPE_GRID_*`) | Listen- vs. Grid-Ansicht, Spalten/Dichte | ✅ live |
| Design-Backlog (23 Toggles, §1) | Touch-Targets/Farben/Layout/Buttons/Typo/Nav | ⚠️ 8/23 live |

---

## 4. Neue/vertiefte Befunde (über den bestehenden Backlog hinaus)

### 4.1 Kontrast: „Dark-Mode-Kontrast“ ist zu eng gefasst – betrifft auch Light Mode

Toggle #11 unterstellt, nur der Dark Mode habe zu wenig Kontrast. Tatsächlich wird
`onPrimary = Color.White` **sowohl in `toColorScheme()` als auch in `toDarkColorScheme()`**
hart gesetzt – unabhängig davon, wie hell die Theme-Primärfarbe ist. Gemessen (WCAG-Kontrast
Weiss-auf-Primary, Normtext-Minimum 4.5:1):

| Theme | Primary | Kontrast weiss/primary | WCAG AA |
|---|---|---|---|
| Gelb (SUNNY) | `#EAB308` | 1.92 : 1 | ❌ |
| Türkis (LAGOON_TEAL) | `#0891B2` | 3.68 : 1 | ❌ |
| Orange (SUNSET_ORANGE) | `#EA580C` | 3.56 : 1 | ❌ |
| **Grün (FOREST_GREEN, Default-Theme!)** | `#16A34A` | 3.30 : 1 | ❌ |
| Rot (CHERRY_RED) | `#DC2626` | 4.83 : 1 | ✅ |
| Blau (OCEAN_BLUE) | `#2563EB` | 5.17 : 1 | ✅ |
| Lila, Rosa, Grau, Schwarz | – | 4.60–14.6 : 1 | ✅ |

4 von 10 Themes fallen durch – **inklusive des Default-Themes Grün**, betroffen ist
u. a. der weisse Text im `HomeHeader`-Gradient (Begrüssung, „Heute/Gestern“, Kalorienzahl
im Ring). Empfehlung: Toggle #11 umbenennen/erweitern zu „Kontrast-Modus“ (wirkt in
Light + Dark) und pro Theme einen dunkleren, kontrastsicheren `onPrimary`-Farbwert
statt pauschal `Color.White` berechnen (ähnlich der Luminanz-Logik, die in
`CropperDefaults.toolbarColor()` schon existiert, s. 4.4 – dort nur nie benutzt).

### 4.2 Spacing-Tokens: Ausmass des Hardcodings

Toggle #14 „Spacing-Tokens“ ist als Refactor beschrieben – hier die Grössenordnung:
**1485 hartkodierte `N.dp`-Literale** in `ui/screens/*` gegenüber **282 Verwendungen**
von `NutriSpacing.*`. D. h. nur ~16 % der Abstände laufen über das Token-System.
Das ist kein Wochenend-Toggle, sondern ein schrittweiser Screen-für-Screen-Refactor;
sollte in der Priorisierung (§6) eher hinten stehen, aber realistisch geplant werden
(z. B. 1 Screen pro Commit statt „an/aus“-Schalter).

### 4.3 Card-Elevation: Umfang der Inkonsistenz

Toggle #10: von **193 `Card(...)`-Vorkommen** in Screens/Components setzen nur
**12 Dateien** explizit `CardDefaults.cardElevation(...)`. Der Rest läuft auf dem
Material-3-Default (1 dp, weisse/onSurface-Fläche) – das erklärt den in der
Toggle-Beschreibung angesprochenen "flachen" Look. Sinnvoller Einstieg: einen
zentralen `NutriCard(...)`-Wrapper einführen, der die Elevation aus dem Toggle liest,
statt 193 Stellen einzeln zu ändern.

### 4.4 Cropper-Theme-Toggle zeigt auf totes Feature

`CropperDefaults.kt` (mit der eigentlich cleveren Luminanz-Logik für Kontrast) ist
**vollständig unbenutzt** – kein einziger Call von `CropperDefaults.options(...)` im
gesamten Code. Grund: Der Foto-Zuschnitt läuft seit dem Wechsel weg von canhub
(`ComposeCropScreen.kt`, wegen Main-Thread-Blocking) über einen selbstgebauten
Compose-Cropper mit fest schwarzem Hintergrund/weisser Schrift – dort gibt es gar
keine „Toolbar-Farbe“ mehr, die eingefärbt werden könnte. Empfehlung: Toggle #12
entweder **entfernen** (Feature existiert im aktuellen Cropper nicht) oder **neu
zuschneiden** auf ein tatsächlich vorhandenes Element in `ComposeCropScreen.kt`
(z. B. Akzentfarbe des „Speichern“-Buttons oder des Auswahlrahmens statt Weiss).

### 4.5 Accessibility (niedrige Priorität, zur Kenntnis)

Grobe Zählung: 36× `contentDescription = null` vs. 48× mit Text in `ui/screens/*`.
Kein Alarmsignal, aber beim nächsten grösseren Screen-Umbau lohnt sich ein Blick,
ob rein dekorative Icons (ok mit `null`) von interaktiven Icons ohne Label (nicht ok)
sauber getrennt sind. Kein eigener Toggle nötig – das ist kein A/B-Fall, sondern
sollte einfach korrekt sein.

---

## 5. Bereits umgesetzt in dieser Session

- **Toggle #13 „Noch X kcal übrig hervorheben“ verdrahtet** (`HomeCards.kt`,
  `HomeHeader`): Bei aktiviertem Toggle färbt sich die grosse Restkalorien-Zahl im
  Ring mit `MacroColors.calories` (grün) bzw. bei Zielüberschreitung mit dem
  vorhandenen `overflowColor` (Gold) statt immer Weiss zu sein – Default bleibt aus,
  bisheriges Verhalten unverändert.

---

## 6. Empfohlene Priorisierung für die verbleibenden 12 toten Toggles

1. ~~Card-Elevation (#10)~~ – **NutriCard-Wrapper live**, Home migriert; restliche
   Screens folgen schrittweise (s. §8.1).
2. ~~Dark-Mode-/Kontrast-Fix (#11)~~ – **verdrahtet** (s. §8.2).
3. **Progress-Bar-Farbwechsel (#21)** und **Macro-Farben absetzen (#9)** – klar
   abgegrenzt auf `AnalysisCards.kt`/`HomeCards.kt`, kein grosser Blast-Radius.
4. **Diary kompakter (#17)**, **Home-Reihenfolge (#16)**, **Activity-Karten
   zusammenlegen (#15)** – je auf einen Screen begrenzt.
5. **Button-Standardgrösse (#19)**, **Portion-Chips (#20)**, **Recipes-FAB (#18)**,
   **Primärzahlen grösser (#22)**, **Nav-Shortcuts (#23)** – breiter, aber mechanisch.
6. **Cropper-Theme-Farbe (#12)** – erst entscheiden (entfernen vs. neu zuschneiden),
   dann umsetzen.
7. **Spacing-Tokens (#14)** – kein einzelner Schritt, sondern laufender Refactor
   parallel zu anderer Screen-Arbeit.

Wie gehabt: pro Punkt eigener Commit, CI muss grün sein, bevor der nächste beginnt.

---

## 8. Bereits umgesetzt (29. August 2026, Fortsetzung)

### 8.1 Toggle #10 „Card-Elevation erhöhen“ – NutriCard-Wrapper eingeführt

Zentraler `NutriCard(...)`-Composable in `Components.kt` ersetzt schrittweise die
~193 einzeln aufgesetzten `Card(...)`-Stellen mit flachem Standard-Look. Default
(Toggle aus) = bisheriges Verhalten (1dp, `surface`/Weiss) unverändert. Bei
aktiviertem Toggle: 2dp Elevation + `surfaceContainer` statt Weiss. Bislang auf dem
Home-Screen migriert (4 Karten); restliche Screens folgen einzeln, damit jeder
Schritt für sich überschaubar bleibt – nur Karten mit neutralem Container (Weiss/
`surface`) werden migriert, eingefärbte Karten (Macro-Tint, `secondaryContainer`,
reine Border-Karten) bleiben bewusst `Card(...)`.

### 8.2 Toggle #11 „Kontrast-Modus“ – jetzt Light **und** Dark, nicht nur Dark

Der Toggle hiess bisher „Dark-Mode-Kontrast“, betraf laut §4.1 aber auch Light Mode
(4 von 10 Themes fallen mit `onPrimary = Color.White` unter WCAG-AA, u. a. das
Default-Theme Grün). Umgesetzt in `Theme.kt`:

- `contrastSafeOnColor(background)`: berechnet den echten WCAG-Kontrastwert
  Weiss-gegen-Hintergrund (`(1.05) / (luminance + 0.05)`, gleiche Luminanz-Basis wie
  `CropperDefaults.toolbarColor()`); unter 4.5:1 wird Schwarz statt Weiss verwendet.
- `toColorScheme()`/`toDarkColorScheme()` nehmen neu einen `contrastSafeOnPrimary`-
  Parameter (Default `false` = bisheriges Verhalten, immer Weiss).
- `NutriSnapTheme` liest den Toggle-Wert aus dem DataStore und reicht ihn in beide
  Farbschema-Funktionen durch – wirkt dadurch automatisch in Light **und** Dark Mode.
- Settings-Text entsprechend umbenannt in „Kontrast-Modus (Text auf Primärfarbe)“,
  damit klar ist, dass es nicht nur Dark Mode betrifft.

Betrifft u. a. den weissen Text im `HomeHeader`-Gradient (Begrüssung, Kalorienzahl im
Ring) bei den vier durchfallenden Themes.

---

## 7. Nachtrag (29. August 2026): Recipes-Screen – Status der zuletzt offenen Layout-Punkte

Aus der letzten Session war offen, ob nach dem Speed-Dial-Umbau (`317cb90`) noch
FAB/Grid-Überlappung, Sync-Chip-Position, Suchfeld-Bereich und Bottom-Nav-Abstand
Probleme bereiten. Code-Stand geprüft, nicht nur Commit-Messages gelesen:

### 7.1 Bereits behoben (zwischen `317cb90` und `be570b3`, alle vor dieser Session)

- **FAB/Grid-Überlappung beim Ausklappen**: `317cb90` hat direkt mit dem Speed-Dial
  auch einen Scrim eingebaut (`Color.Black.copy(alpha = 0.32f)`, `fillMaxSize`,
  schliesst bei Klick daneben). Das Grid wird beim Ausklappen abgedunkelt statt roh
  überlappt – visuell kein Bug mehr, sondern das übliche Material-Speed-Dial-Muster.
- **Sync-Chip**: in zwei Schritten korrigiert – `e540281` (reservierte keinen
  Statusleisten-Leerraum mehr) und `48ba907` (auf den KI-Tab verschoben).
- **Suchfeld-Bereich**: `48ba907` (toter Streifen über der Suche behoben) und
  `560ef93` (Such-/Top-Abstand poliert).
- **Bottom-Nav / abgeschnittene Kacheln**: `560ef93` (untere Kacheln nicht mehr
  abgeschnitten) und `be570b3` (Bottom-Nav-Ecken, Labels nicht mehr abgeschnitten).
- **3-Spalten-Lesbarkeit & Karten-Höhe**: `ae07393`, `c8aef58`.

Kurz: Die in der letzten Session als offen notierten Punkte sind zwischenzeitlich
bereits gefixt worden – falls du das auf dem Gerät noch anders siehst, ist das
vermutlich ein Stale-APK-Fall (neuester CI-Build noch nicht installiert), kein
Code-Problem.

### 7.2 Ein echter, kleiner Rest-Befund: Grid vs. klassische Liste inkonsistent gepolstert

`LazyVerticalGrid` (Standard-Ansicht) reserviert unten **`bottom = 132.dp`** für
Bottom-Nav + FAB. Die klassische 1-Spalten-Liste (Toggle „Altes Design (vor #758)“,
`KEY_CLASSIC_RECIPE_LIST`) nutzt für **dieselbe** Bottom-Nav/FAB-Situation nur
**`bottom = 80.dp`** – 52dp weniger Luft. Kein akuter Überlapp-Bug (der Scrim-Fall
oben betrifft nur den ausgeklappten Zustand), aber die letzte Listenkarte hat
spürbar weniger Abstand zur Nav als im Grid. Reine Konsistenz-Korrektur (Liste auf
132dp anheben), kein gestalterischer Ermessensspielraum – würde ich wie die
bisherigen "Fix:"-Commits direkt beheben statt extra zu toggeln, sag Bescheid falls
du es trotzdem A/B-vergleichen willst.

### 7.3 Weitere Screens mit FAB geprüft, kein vergleichbarer Befund

`CustomFoodListScreen`, `MealTemplateScreen`, `ShoppingListScreen`,
`SupplementsScreen` nutzen je einen einzelnen, nicht animierten FAB (keine
Höhenänderung → kein Überlapp-Risiko). `DiaryScreen` hat einen Zwei-FAB-Stack
(Kamera + Add), der aber **statisch** ist (kein Auf-/Zuklappen) – dort besteht das
Speed-Dial-spezifische Muster gar nicht erst.
