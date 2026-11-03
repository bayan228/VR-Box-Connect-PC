param([switch]$AcceptLicense)

$ErrorActionPreference = 'Stop'
if (-not $AcceptLicense) {
    throw 'Review https://developer.android.com/studio/terms, then pass -AcceptLicense to install the project-local SDK.'
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tools = Join-Path $projectRoot '.tools'
$jdkHome = Join-Path $tools 'jdk-17.0.21.0.2+1'
if (-not (Test-Path -LiteralPath $tools -PathType Container) -or
    -not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $jdkHome '.vrbox-verified') -PathType Leaf)) {
    throw 'Prepare the verified local JDK first: powershell -NoProfile -ExecutionPolicy Bypass -File .\android-client\build.ps1 --bootstrap-only'
}

$sdkRoot = Join-Path $tools 'android-sdk'
$toolHome = Join-Path $sdkRoot 'cmdline-tools\19.0'
$sdkManager = Join-Path $toolHome 'lib\sdkmanager-classpath.jar'
$ready = Join-Path $toolHome '.vrbox-verified'
$downloads = Join-Path $tools 'downloads'
$temp = Join-Path $tools 'tmp'
$toolUserHome = Join-Path $tools 'home'
$archive = Join-Path $downloads 'commandlinetools-win-13114758_latest.zip'
$url = 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
# SHA256 of the archive verified against Google's repository2-1.xml SHA1:
# 54a582f3bf73e04253602f2d1c80bd5868aac115 (cmdline-tools;19.0, Windows).
$sha256 = '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003'

$environment = @{
    JAVA_HOME = $jdkHome
    HOME = $toolUserHome
    USERPROFILE = $toolUserHome
    LOCALAPPDATA = Join-Path $toolUserHome 'AppData\Local'
    APPDATA = Join-Path $toolUserHome 'AppData\Roaming'
    ANDROID_HOME = $sdkRoot
    ANDROID_SDK_ROOT = $sdkRoot
    ANDROID_USER_HOME = Join-Path $toolUserHome '.android'
    ANDROID_EMULATOR_HOME = Join-Path $toolUserHome '.android'
    ANDROID_AVD_HOME = Join-Path $toolUserHome '.android\avd'
    ANDROID_PREFS_ROOT = $toolUserHome
    ANDROID_SDK_HOME = $toolUserHome
    TEMP = $temp
    TMP = $temp
    TMPDIR = $temp
    JAVA_OPTS = ''
    SDKMANAGER_OPTS = ''
    JDK_JAVA_OPTIONS = ''
    _JAVA_OPTIONS = ''
    JAVA_TOOL_OPTIONS = "`"-Duser.home=$toolUserHome`" `"-Djava.io.tmpdir=$temp`" `"-XX:ErrorFile=$temp\hs_err_pid%p.log`" `"-XX:HeapDumpPath=$temp`""
}
$previous = @{}
try {
    foreach ($name in $environment.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
    }
    foreach ($directory in @($downloads, $temp, $environment.LOCALAPPDATA,
        $environment.APPDATA, $environment.ANDROID_AVD_HOME, (Join-Path $sdkRoot 'cmdline-tools'))) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }

    if (-not (Test-Path -LiteralPath $ready -PathType Leaf) -or
        -not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
            'Downloading/resuming pinned Android SDK command-line tools inside .tools...'
            & curl.exe --disable --fail --location --silent --show-error --continue-at - --connect-timeout 30 --max-time 600 --proto '=https' --proto-redir '=https' --output $archive $url
            if ($LASTEXITCODE -ne 0) { throw "SDK tools download incomplete (curl exit $LASTEXITCODE). Re-run to resume." }
        }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
            throw "SDK tools SHA256 mismatch; refusing to extract or execute: $archive"
        }
        if (Test-Path -LiteralPath $toolHome) {
            throw "Unverified/incomplete SDK tools directory already exists: $toolHome"
        }
        $staging = Join-Path $temp ('sdk-tools-' + [Guid]::NewGuid().ToString('N'))
        [IO.Directory]::CreateDirectory($staging) | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($archive, $staging)
        [IO.Directory]::Move((Join-Path $staging 'cmdline-tools'), $toolHome)
        [IO.File]::WriteAllText($ready, $sha256)
        [IO.Directory]::Delete($staging)
    }

    # Record only the explicitly approved license, never blanket-accept future terms.
    # Verified via SDK TrimStringAdapter + License.getLicenseHash() on Google's XML.
    # Avoid piped answers: Windows PowerShell 5.1 can add a BOM that sdkmanager rejects.
    $licenseHash = '24333f8a63b6825ea9c5514f83c2829b004d1fee'
    $licenseDirectory = Join-Path $sdkRoot 'licenses'
    [IO.Directory]::CreateDirectory($licenseDirectory) | Out-Null
    $licenseFile = Join-Path $licenseDirectory 'android-sdk-license'
    if (-not (Test-Path -LiteralPath $licenseFile -PathType Leaf) -or
        [IO.File]::ReadAllLines($licenseFile) -notcontains $licenseHash) {
        [IO.File]::AppendAllText($licenseFile, "`n$licenseHash`n", [Text.Encoding]::ASCII)
    }

    'Installing Android SDK Platform 34 and Build Tools 34.0.0 inside .tools/android-sdk...'
    & (Join-Path $jdkHome 'bin\java.exe') "-Dcom.android.sdklib.toolsdir=$toolHome" `
        -classpath $sdkManager com.android.sdklib.tool.sdkmanager.SdkManagerCli `
        "--sdk_root=$sdkRoot" '--channel=0' 'platforms;android-34' 'build-tools;34.0.0'
    if ($LASTEXITCODE -ne 0) { throw "SDK installation failed (exit $LASTEXITCODE). Re-run to retry." }
    foreach ($relative in @('platforms\android-34\android.jar', 'build-tools\34.0.0\aapt2.exe',
        'build-tools\34.0.0\apksigner.bat', 'build-tools\34.0.0\zipalign.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot $relative) -PathType Leaf)) {
            throw "SDK installation is incomplete: $relative"
        }
    }
    "Project-local Android SDK 34 ready: $sdkRoot"
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
