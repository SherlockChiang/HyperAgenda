param(
    [string]$KeystorePath = "$env:USERPROFILE\.android\debug.keystore",
    [string]$KeyAlias = "androiddebugkey",
    [string]$ExpectedCertificateSha256 = "595e889f97bd7f1a810ba95aa5be5677f4d7e18986e7d4eb37a40340a310a834",
    [string]$SourceRepository = "",
    [string]$ModuleRepository = "Xposed-Modules-Repo/io.github.sherlockchiang.hyperagenda",
    [string]$Notes = "",
    [switch]$SkipModuleRepository,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "GitHub CLI was not found. Install it and run 'gh auth login'."
    }

    function Read-Secret([string]$Prompt) {
        $secure = Read-Host -Prompt $Prompt -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }

    function Resolve-JavaHome {
        # gradlew.bat aborts on a stale JAVA_HOME, so fall back to the JDK on PATH.
        if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
            return
        }
        $java = Get-Command java -ErrorAction SilentlyContinue
        if (-not $java) {
            throw "No usable JDK found. Set JAVA_HOME to a JDK 17 or newer."
        }
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source)
        Write-Host "JAVA_HOME was unusable, using $env:JAVA_HOME instead."
    }

    function Get-BuildTool([string]$ToolName) {
        $sdk = $env:ANDROID_HOME
        if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
        if (-not $sdk) {
            $localProperties = Join-Path $repoRoot "local.properties"
            if (Test-Path $localProperties) {
                $line = Select-String -Path $localProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
                if ($line) {
                    # local.properties escapes the drive colon and every separator.
                    $sdk = $line.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\')
                }
            }
        }
        if (-not $sdk -or -not (Test-Path $sdk)) {
            return $null
        }
        $buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending
        foreach ($toolDir in $buildTools) {
            foreach ($candidate in @("$ToolName.bat", $ToolName)) {
                $path = Join-Path $toolDir.FullName $candidate
                if (Test-Path $path) { return $path }
            }
        }
        return $null
    }

    # Version metadata drives the release tag required by the module repo: VersionCode-VersionName.
    $gradleFile = Join-Path $repoRoot "app\build.gradle"
    $gradleText = Get-Content -Raw $gradleFile
    if ($gradleText -notmatch 'versionCode\s+(\d+)') { throw "Could not read versionCode from app/build.gradle." }
    $versionCode = $matches[1]
    if ($gradleText -notmatch "versionName\s+'([^']+)'") { throw "Could not read versionName from app/build.gradle." }
    $versionName = $matches[1]
    $tag = "$versionCode-$versionName"
    Write-Host "Publishing HyperAgenda $versionName (versionCode $versionCode), tag $tag."

    if (-not (Test-Path -LiteralPath $KeystorePath)) {
        throw "Existing release signing keystore not found at $KeystorePath. Restore it from backup."
    }

    if (-not $DryRun) {
        # The release tag is created on the remote default branch, so the tagged source has to be
        # exactly what gets built here.
        $dirty = (& git status --porcelain --untracked-files=no)
        if ($dirty) {
            throw "Tracked files have uncommitted changes, so the release tag would not match the built source:`n$dirty"
        }
        & git fetch origin main --quiet
        if ($LASTEXITCODE -eq 0) {
            $localHead = (& git rev-parse HEAD).Trim()
            $remoteHead = (& git rev-parse origin/main).Trim()
            if ($localHead -ne $remoteHead) {
                throw "HEAD ($($localHead.Substring(0, 7))) differs from origin/main ($($remoteHead.Substring(0, 7))). Push before releasing."
            }
        }
    }

    if (-not $env:SIGNING_STORE_PASSWORD -and $KeyAlias -eq "androiddebugkey") {
        $env:SIGNING_STORE_PASSWORD = "android"
        $env:SIGNING_KEY_PASSWORD = "android"
    }
    if (-not $env:SIGNING_STORE_PASSWORD) {
        $storePassword = Read-Secret "Keystore password"
        $env:SIGNING_STORE_PASSWORD = $storePassword
        $keyPassword = Read-Secret "Key password (press Enter to reuse the keystore password)"
        if ([string]::IsNullOrEmpty($keyPassword)) { $keyPassword = $storePassword }
        $env:SIGNING_KEY_PASSWORD = $keyPassword
    }
    if (-not $env:SIGNING_KEY_PASSWORD) {
        $env:SIGNING_KEY_PASSWORD = $env:SIGNING_STORE_PASSWORD
    }
    $env:SIGNING_STORE_FILE = $KeystorePath
    $env:SIGNING_KEY_ALIAS = $KeyAlias

    Resolve-JavaHome
    & (Join-Path $repoRoot "gradlew.bat") :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "gradlew :app:assembleRelease failed."
    }

    $apk = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) {
        throw "Release APK not found at $apk."
    }

    $apksigner = Get-BuildTool "apksigner"
    if ($apksigner) {
        $certs = & $apksigner verify --print-certs $apk 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw "APK signature verification failed."
        }
        if ($certs -notmatch 'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})') {
            throw "APK signer certificate could not be read."
        }
        if ($matches[1] -ine $ExpectedCertificateSha256) {
            throw "APK signing certificate differs from the expected release certificate."
        }
        $shaLine = ($certs -split "`n" | Select-String "certificate SHA-256 digest" | Select-Object -First 1)
        Write-Host "Signer: $($shaLine.Line.Trim())"
    } else {
        throw "apksigner was not found; cannot verify release signing identity."
    }

    $sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$sha256  app-release.apk" | Set-Content -Path "$apk.sha256" -Encoding ascii
    Write-Host "SHA-256: $sha256"

    if ($DryRun) {
        Write-Host "Dry run complete: the signed APK is ready, nothing was uploaded."
        return
    }

    if ([string]::IsNullOrEmpty($SourceRepository)) {
        $remoteUrl = (& git remote get-url origin).Trim()
        if ($remoteUrl -match "github\.com[:/]([^/]+)/([^/.]+)(?:\.git)?$") {
            $SourceRepository = "$($matches[1])/$($matches[2])"
        }
    }

    $targets = @()
    if ($SourceRepository) { $targets += $SourceRepository }
    if (-not $SkipModuleRepository) {
        & gh repo view $ModuleRepository --json name 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $targets += $ModuleRepository
        } else {
            Write-Warning "$ModuleRepository does not exist yet. Apply at https://modules.lsposed.org/submission, then rerun with -SkipModuleRepository:`$false."
        }
    }
    if (-not $targets) {
        throw "No target repository to publish to."
    }

    foreach ($target in $targets) {
        Write-Host "Publishing $tag to $target"
        & gh release view $tag --repo $target 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            if ($Notes) {
                & gh release create $tag --repo $target --title $versionName --notes $Notes --target main $apk "$apk.sha256"
            } else {
                & gh release create $tag --repo $target --title $versionName --generate-notes --target main $apk "$apk.sha256"
            }
            if ($LASTEXITCODE -ne 0) { throw "gh release create failed for $target." }
        } else {
            & gh release upload $tag --repo $target $apk "$apk.sha256" --clobber
            if ($LASTEXITCODE -ne 0) { throw "gh release upload failed for $target." }
            # Asset-only changes do not notify the module index bot; also update release content.
            $releaseNotes = if ($Notes) { $Notes } else {
                (& gh release view $tag --repo $target --json body --jq .body) -join "`n"
            }
            if ($LASTEXITCODE -ne 0) { throw "Could not read release notes for $target." }
            $releaseNotes += "`n`nAPK SHA-256: $sha256"
            & gh release edit $tag --repo $target --notes $releaseNotes
            if ($LASTEXITCODE -ne 0) { throw "Could not update release content for $target." }
        }
    }

    Write-Host ""
    Write-Host "Done. Release $tag is live for: $($targets -join ', ')"
} finally {
    Pop-Location
}
