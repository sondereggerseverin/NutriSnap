# NutriSnap – Health Connect Push Script
param([string]$RepoPath = "..\NutriSnap")

$base = "app\src\main\kotlin\ch\nutrisnap\app"

$files = @{
    "app\src\main\kotlin\ch\nutrisnap\app\health\HealthConnectManager.kt"              = "$RepoPath\$base\health\HealthConnectManager.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\health\HealthConnectPermissionHandler.kt"    = "$RepoPath\$base\health\HealthConnectPermissionHandler.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\data\model\HealthConnectCache.kt"            = "$RepoPath\$base\data\model\HealthConnectCache.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\data\db\HealthConnectDao.kt"                 = "$RepoPath\$base\data\db\HealthConnectDao.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\data\db\NutriDatabaseMigrationPatch.kt"      = "$RepoPath\$base\data\db\NutriDatabaseMigrationPatch.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\data\repository\HealthConnectRepository.kt"  = "$RepoPath\$base\data\repository\HealthConnectRepository.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\ui\viewmodel\HealthConnectViewModel.kt"      = "$RepoPath\$base\ui\viewmodel\HealthConnectViewModel.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\ui\screens\HealthConnectScreen.kt"           = "$RepoPath\$base\ui\screens\HealthConnectScreen.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\ui\components\HealthConnectCard.kt"          = "$RepoPath\$base\ui\components\HealthConnectCard.kt"
    "app\src\main\kotlin\ch\nutrisnap\app\di\HealthConnectModule.kt"                   = "$RepoPath\$base\di\HealthConnectModule.kt"
}

foreach ($src in $files.Keys) {
    $dst = $files[$src]
    $dir = Split-Path $dst -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item -Path $src -Destination $dst -Force
    Write-Host "Copied: $src"
}

Write-Host ""
Write-Host "Noch manuell erledigen:"
Write-Host "  1. manifest_additions.xml → AndroidManifest.xml"
Write-Host "  2. build_gradle_additions.md → app/build.gradle.kts"
Write-Host "  3. NutriDatabaseMigrationPatch.kt → NutriDatabase.kt anpassen"
Write-Host ""

Set-Location $RepoPath
git add .
git commit -m "feat(health-connect): Samsung Health Integration via Health Connect API"
git push origin main
Write-Host "Health Connect erfolgreich gepusht!"
