# ============================================
# NutriSnap Block A Push Script
# Kochmodus, Sammlungen, Diät-Tags
# ============================================
# ANLEITUNG:
#   1. Expand-Archive -Path "C:\Users\X281838\Downloads\NutriSnap_BlockA.zip" -DestinationPath "C:\Users\X281838\NutriSnap" -Force
#   2. cd C:\Users\X281838\NutriSnap\nutrisnap_blocka
#      .\push_blocka.ps1
# ============================================

Write-Host "" 
Write-Host "== NutriSnap Block A Push ==" -ForegroundColor Cyan

$repoRoot = "C:\Users\X281838\NutriSnap"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $repoRoot
git checkout main
git pull origin main --rebase

$files = @(
    "app\src\main\kotlin\ch\nutrisnap\app\data\model\Models.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\data\db\NutriDatabase.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\data\db\RecipeCollectionDao.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\screens\recipes\RecipeCollectionsScreen.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\screens\recipes\CookingModeScreen.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\components\DietFilterBar.kt",
    "app\src\main\kotlin\ch\nutrisnap\app\ui\components\TagEditorDialog.kt"
)

foreach ($f in $files) {
    $src  = Join-Path $scriptDir $f
    $dest = Join-Path $repoRoot  $f
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item $src $dest -Force
    Write-Host "  [OK] $f" -ForegroundColor Green
}

git add .
git commit -m "feat: cooking mode with wakelock, recipe collections, diet tag filter"
git push origin main

Write-Host ""
Write-Host "== Fertig! Build laeuft automatisch! ==" -ForegroundColor Green
Write-Host "Actions: https://github.com/sondereggerseverin/NutriSnap/actions" -ForegroundColor Yellow
Start-Process "https://github.com/sondereggerseverin/NutriSnap/actions"
