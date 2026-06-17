# ============================================
# NutriSnap Config Push Script
# ============================================
# SO VERWENDEST DU ES:
#   1. ZIP entpacken:
#      Expand-Archive -Path "C:\Users\X281838\Downloads\NutriSnap_Config.zip" -DestinationPath "C:\Users\X281838\NutriSnap" -Force
#   2. Script ausfuehren:
#      cd C:\Users\X281838\NutriSnap\nutrisnap_config
#      .\push_config.ps1
# ============================================

Write-Host ""
Write-Host "== NutriSnap Config Push ==" -ForegroundColor Cyan

$repoRoot = "C:\Users\X281838\NutriSnap"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $repoRoot

# Branch sicherstellen
git checkout feature/nutrisnap-improvements
git pull origin feature/nutrisnap-improvements --rebase

# AndroidManifest.xml
$manifestDest = "$repoRoot\app\src\main\AndroidManifest.xml"
Copy-Item "$scriptDir\app\src\main\AndroidManifest.xml" $manifestDest -Force
Write-Host "  [OK] AndroidManifest.xml kopiert" -ForegroundColor Green

# NutriDatabase.kt
$dbDir = "$repoRoot\app\src\main\kotlin\ch\nutrisnap\app\data\db"
New-Item -ItemType Directory -Force -Path $dbDir | Out-Null
Copy-Item "$scriptDir\app\src\main\kotlin\ch\nutrisnap\app\data\db\NutriDatabase.kt" "$dbDir\NutriDatabase.kt" -Force
Write-Host "  [OK] NutriDatabase.kt kopiert" -ForegroundColor Green

# Git add, commit, push
git add "app/src/main/AndroidManifest.xml"
git add "app/src/main/kotlin/ch/nutrisnap/app/data/db/NutriDatabase.kt"
git commit -m "feat: manifest permissions + NutriDatabase v4 with WaterEntry & FastingSession migration"
git push origin feature/nutrisnap-improvements

Write-Host ""
Write-Host "== Fertig! Alles gepusht! ==" -ForegroundColor Green
Write-Host "PR oeffnen:" -ForegroundColor Yellow
Write-Host "https://github.com/sondereggerseverin/NutriSnap/compare/feature/nutrisnap-improvements" -ForegroundColor Yellow
Start-Process "https://github.com/sondereggerseverin/NutriSnap/compare/feature/nutrisnap-improvements"
