$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tools = Join-Path $projectRoot '.tools'
if (-not (Test-Path -LiteralPath $projectRoot -PathType Container)) {
    throw "Project directory is missing: $projectRoot"
}
[IO.Directory]::CreateDirectory($tools) | Out-Null
if (-not (Test-Path -LiteralPath $tools -PathType Container)) {
    throw "Tool directory is missing: $tools"
}
$downloads = Join-Path $tools 'downloads'
$temp = Join-Path $tools 'tmp'
[IO.Directory]::CreateDirectory($downloads) | Out-Null
[IO.Directory]::CreateDirectory($temp) | Out-Null

# Pinned portable Microsoft JDK; checksum published alongside the official ZIP.
$jdkName = 'jdk-17.0.21.0.2+1'
$jdkHome = Join-Path $tools $jdkName
$jdkReady = Join-Path $jdkHome '.vrbox-verified'
$jdkUrl = 'https://aka.ms/download-jdk/microsoft-jdk-17.0.21.0.2-windows-x64.zip'
$jdkSha256 = '49154292866386b2f4f09a3ecf3be07ab80a419149c84ec1d4419b9eec9e4b64'
$archive = Join-Path $downloads 'microsoft-jdk-17.0.21.0.2-windows-x64.zip'
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
$oldProtocolTests = $env:VRBOX_PROTOCOL_TESTS
$exitCode = 0
try {
    $env:TEMP = $temp
    $env:TMP = $temp
    $env:VRBOX_PROTOCOL_TESTS = ''
    if (-not (Test-Path -LiteralPath $jdkReady -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe') -PathType Leaf)) {
        if (-not (Test-Path -LiteralPath $downloads -PathType Container)) {
            throw "Download directory is missing: $downloads"
        }
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $jdkSha256) {
            'Downloading/resuming pinned JDK 17 into .tools/downloads (ten-minute limit)...'
            # --disable must be first: never load a global curl configuration.
            & curl.exe --disable --fail --location --silent --show-error --continue-at - --connect-timeout 30 --max-time 600 --speed-limit 1024 --speed-time 60 --proto '=https' --proto-redir '=https' --output $archive $jdkUrl
            if ($LASTEXITCODE -ne 0) {
                throw "JDK download incomplete (curl exit $LASTEXITCODE). Re-run this command to resume: $archive"
            }
        }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $jdkSha256) {
            throw "JDK SHA256 mismatch. Remove the incomplete archive and retry: $archive"
        }
        'JDK SHA256 verified; extracting inside .tools...'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($archive, $tools)
        [IO.File]::WriteAllText($jdkReady, $jdkSha256)
    }

    if ($args.Count -eq 1 -and $args[0] -eq '--bootstrap-only') {
        "Portable JDK ready: $jdkHome"
    } else {
        $wrapper = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
        # Gradle's published 8.6 wrapper JAR checksum, not the distribution checksum.
        $wrapperSha256 = 'd3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd'
        if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ne $wrapperSha256) {
            throw 'Gradle wrapper JAR failed SHA256 verification.'
        }
        $tasks = $args
        if ($tasks.Count -gt 0 -and $tasks[0] -eq '--protocol-tests') {
            $env:VRBOX_PROTOCOL_TESTS = '1'
            $tasks = @('test')
            if ($args.Count -gt 1) { $tasks += $args[1..($args.Count - 1)] }
        } elseif ($tasks.Count -eq 0) {
            $tasks = @('assembleDebug', 'testDebugUnitTest')
        }
        if ($env:VRBOX_PROTOCOL_TESTS -ne '1' -and
            -not (Test-Path -LiteralPath (Join-Path $tools 'android-sdk\platforms\android-34\android.jar') -PathType Leaf)) {
            throw 'Project-local Android SDK 34 is missing in .tools/android-sdk. No external SDK will be used or installed automatically. Use --protocol-tests for JVM-only checks.'
        }
        & (Join-Path $PSScriptRoot 'gradlew.bat') --no-daemon --console=plain @tasks
        $exitCode = $LASTEXITCODE
    }
} finally {
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
    $env:VRBOX_PROTOCOL_TESTS = $oldProtocolTests
}
exit $exitCode
