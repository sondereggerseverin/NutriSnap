# build.gradle.kts – Ergänzungen für Health Connect

## app/build.gradle.kts

```kotlin
dependencies {
    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
}
```

## Wichtig: minSdk muss >= 26 sein
```kotlin
android {
    defaultConfig {
        minSdk = 26  // Health Connect benötigt Android 8+
    }
}
```

## Samsung Health Sync
Samsung Health schreibt automatisch in Health Connect wenn:
1. Samsung Health App installiert ist
2. In Samsung Health: Einstellungen → Verbundene Apps → Health Connect → Aktivieren
3. Dann NutriSnap in Health Connect autorisieren

Kein Samsung-spezifisches SDK nötig – alles läuft über standard Health Connect API.
