<#
.SYNOPSIS
  Baut OpenCascade (statisch) für Android und installiert es nach third_party/occt-android/<abi>.

.DESCRIPTION
  Nur die Module, die für die B-Rep-Modellierung nötig sind:
  FoundationClasses, ModelingData, ModelingAlgorithms (keine Fremdbibliotheken).
  DataExchange (STEP) kann später mit -WithDataExchange zugeschaltet werden.

  Voraussetzungen: Android SDK mit NDK 28.2.13676358 und CMake 3.31.6 (inkl. Ninja).
  Quelltext: third_party/occt (OCCT V7_9_3, `git clone --depth 1 --branch V7_9_3 ...`).

.EXAMPLE
  ./scripts/build-occt.ps1 -Abis x86_64
  ./scripts/build-occt.ps1 -Abis arm64-v8a,x86_64 -WithDataExchange
#>
param(
    [string[]]$Abis = @("x86_64", "arm64-v8a"),
    [switch]$WithDataExchange,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = "28.2.13676358",
    [string]$CMakeVersion = "3.31.6",
    [int]$ApiLevel = 26
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root "third_party/occt"
$ndk = Join-Path $Sdk "ndk/$NdkVersion"
$cmakeBin = Join-Path $Sdk "cmake/$CMakeVersion/bin"
$cmake = Join-Path $cmakeBin "cmake.exe"
$ninja = Join-Path $cmakeBin "ninja.exe"
foreach ($p in @($src, $ndk, $cmake, $ninja)) { if (-not (Test-Path $p)) { throw "Nicht gefunden: $p" } }

foreach ($abi in $Abis) {
    $build = Join-Path $root "build-occt/$abi"
    $prefix = Join-Path $root "third_party/occt-android/$abi"
    Write-Host "=== OpenCascade für $abi ===" -ForegroundColor Cyan
    $dataExchange = if ($WithDataExchange) { "ON" } else { "OFF" }
    & $cmake -S $src -B $build -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" "-DANDROID_PLATFORM=android-$ApiLevel" "-DANDROID_STL=c++_shared" `
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" `
        "-DCMAKE_BUILD_TYPE=Release" "-DCMAKE_POSITION_INDEPENDENT_CODE=ON" `
        "-DCMAKE_INSTALL_PREFIX=$prefix" "-DINSTALL_DIR=$prefix" `
        "-DBUILD_LIBRARY_TYPE=Static" `
        "-DBUILD_MODULE_FoundationClasses=ON" "-DBUILD_MODULE_ModelingData=ON" "-DBUILD_MODULE_ModelingAlgorithms=ON" `
        "-DBUILD_MODULE_Visualization=OFF" "-DBUILD_MODULE_ApplicationFramework=OFF" `
        "-DBUILD_MODULE_DataExchange=$dataExchange" "-DBUILD_MODULE_DETools=OFF" "-DBUILD_MODULE_Draw=OFF" `
        "-DUSE_FREETYPE=OFF" "-DUSE_TK=OFF" "-DUSE_RAPIDJSON=OFF" "-DUSE_DRACO=OFF" "-DUSE_TBB=OFF" "-DUSE_FREEIMAGE=OFF" `
        "-DBUILD_DOC_Overview=OFF" "-DBUILD_USE_PCH=OFF" "-DBUILD_TESTING=OFF" "-DBUILD_SAMPLES_QT=OFF"
    if ($LASTEXITCODE -ne 0) { throw "CMake-Konfiguration für $abi fehlgeschlagen" }
    & $cmake --build $build --target install -j ([Environment]::ProcessorCount)
    if ($LASTEXITCODE -ne 0) { throw "Build für $abi fehlgeschlagen" }
    Write-Host "Installiert nach $prefix" -ForegroundColor Green
}
