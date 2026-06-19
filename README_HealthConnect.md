# NutriSnap – Health Connect Integration

## Was ist enthalten

| Datei | Beschreibung |
|---|---|
| `HealthConnectManager.kt` | Kern-API: Schritte, Kalorien, Gewicht, Schlaf, Puls |
| `HealthConnectPermissionHandler.kt` | Anleitung für Permission-Flow in MainActivity |
| `HealthConnectCache.kt` | Room Entity – lokaler Cache der Health-Daten |
| `HealthConnectDao.kt` | DAO mit Queries für heute + 7-Tages-Verlauf |
| `NutriDatabaseMigrationPatch.kt` | Migration v8→v9 – Anleitung zum Einbauen |
| `HealthConnectRepository.kt` | Parallel-Sync aller Daten, Cache-Management |
| `HealthConnectViewModel.kt` | State + angepasstes Kalorienziel |
| `HealthConnectScreen.kt` | Vollbild-Screen: Heute + 7-Tage Charts |
| `HealthConnectCard.kt` | Dashboard-Widget (in DiaryScreen einbauen) |
| `HealthConnectModule.kt` | Hilt DI Module |

## Schritt-für-Schritt Integration

### 1. Dependency
```kotlin
implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
```

### 2. Manifest
Permissions + queries aus `manifest_additions.xml` einfügen

### 3. Datenbank
`NutriDatabaseMigrationPatch.kt` lesen → 3 Änderungen in `NutriDatabase.kt`

### 4. Hilt
`HealthConnectModule.kt` wird automatisch erkannt

### 5. MainActivity
```kotlin
val permissionLauncher = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
) { granted ->
    if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
        healthVm.onPermissionGranted()
    }
}
```

### 6. Dashboard-Card
```kotlin
// In DiaryScreen.kt:
HealthConnectCard(
    data = healthState.todayData,
    adjustedCalorieGoal = adjustedGoal,
    hasPermission = healthState.hasPermission,
    isLoading = healthState.isLoading,
    onConnectClick = { permissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS) },
    onSyncClick = { healthVm.syncNow() }
)
```

### 7. Navigation
```kotlin
composable("health") { HealthConnectScreen(onRequestPermission = { ... }) }
```

## Samsung Health Setup (Nutzer-Seite)
1. Samsung Health App öffnen
2. Einstellungen → Verbundene Apps → Health Connect
3. Health Connect aktivieren
4. NutriSnap in Health Connect autorisieren → fertig!
