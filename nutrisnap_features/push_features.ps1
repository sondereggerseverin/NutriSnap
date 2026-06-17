# ============================================
# NutriSnap Features Push Script
# ============================================
# ANLEITUNG:
#   1. ZIP entpacken:
#      Expand-Archive -Path "C:\Users\X281838\Downloads\NutriSnap_Features.zip" -DestinationPath "C:\Users\X281838\NutriSnap" -Force
#   2. Script ausfuehren:
#      cd C:\Users\X281838\NutriSnap\nutrisnap_features
#      .\push_features.ps1
# ============================================

Write-Host "" 
Write-Host "== NutriSnap Features Push ==" -ForegroundColor Cyan

$repoRoot = "C:\Users\X281838\NutriSnap"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $repoRoot
git checkout main
git pull origin main --rebase

$files = @(
    "app\src\main\AndroidManifest.xml",
    "app\src\main\kotlin\ch\nutrisnap\app\MainActivity.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\security\BiometricHelper.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\screens\security\BiometricLockScreen.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\service\NotificationHelper.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\service\NotificationReceiver.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\service\BootReceiver.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\service\NotificationScheduler.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\utils\NetworkMonitor.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\components\OfflineBanner.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\screens\settings\NotificationSettingsScreen.kt"
)

foreach ($f in $files) {
    $src  = Join-Path $scriptDir $f
    $dest = Join-Path $repoRoot  $f
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item $src $dest -Force
    Write-Host "  [OK] $f" -ForegroundColor Green
}

git add .
git commit -m "feat: biometric lock, push notifications, offline banner, notification settings"
git push origin main

Write-Host ""
Write-Host "== Fertig! Build laeuft automatisch! ==" -ForegroundColor Green
Write-Host "Actions: https://github.com/sondereggerseverin/NutriSnap/actions" -ForegroundColor Yellow
Start-Process "https://github.com/sondereggerseverin/NutriSnap/actions"
