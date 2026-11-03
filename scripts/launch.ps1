#requires -Version 5.1
param(
    [ValidateSet('SteamVR', 'Stereo', 'Desktop', 'Apk', 'Stop')]
    [string]$Mode = 'SteamVR',
    [string]$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')),
    [switch]$CheckOnly,
    [switch]$Loopback,
    [ValidateRange(0, 3600)][int]$RunSeconds = 0
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Require-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing ${Description}: $Path. Build or restore this file first; see README.md."
    }
}

function Get-ShortPath([string]$Path) {
    if (-not ('VRBoxLauncher.Paths' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
namespace VRBoxLauncher {
    public static class Paths {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern uint GetShortPathName(string path, StringBuilder buffer, uint capacity);
    }
}
'@
    }
    $buffer = [Text.StringBuilder]::new(32768)
    $length = [VRBoxLauncher.Paths]::GetShortPathName([IO.Path]::GetFullPath($Path), $buffer, $buffer.Capacity)
    if ($length -eq 0 -or $length -ge $buffer.Capacity) {
        throw "Cannot resolve an existing Windows path: $Path"
    }
    return $buffer.ToString().TrimEnd([char[]]'\/')
}

function New-ConsoleProcessInfo([string]$File, [string]$Arguments, [string]$Directory) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File
    $info.Arguments = $Arguments
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    # Inherit the launcher's console: Ctrl+C and closing its window also reach the server.
    $info.CreateNoWindow = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    return $info
}

function Invoke-PathRegistry([string]$Tool, [string]$Arguments) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-ConsoleProcessInfo $Tool $Arguments ([IO.Path]::GetDirectoryName($Tool))
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Cannot start vrpathreg.exe.' }
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) { throw 'SteamVR path registration timed out.' }
        return @{ Code = $process.ExitCode; Text = ($output.GetAwaiter().GetResult() + $errors.GetAwaiter().GetResult()).Trim() }
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(3000) }
        $process.Dispose()
    }
}

function Get-SteamVR([string]$Project) {
    $registry = if ($env:VR_PATHREG_OVERRIDE) { $env:VR_PATHREG_OVERRIDE } else {
        Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'openvr\openvrpaths.vrpath'
    }
    Require-File $registry 'OpenVR path registry (install/start SteamVR once, then close it)'
    $paths = [IO.File]::ReadAllText($registry) | ConvertFrom-Json
    $runtime = $null
    foreach ($candidate in @($paths.runtime)) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\win64\vrstartup.exe') -PathType Leaf)) {
            $runtime = [IO.Path]::GetFullPath($candidate)
            break
        }
    }
    if (-not $runtime) { throw 'SteamVR was not found in the OpenVR path registry. Install/start SteamVR in Steam once.' }
    $tool = Join-Path $runtime 'bin\win64\vrpathreg.exe'
    Require-File $tool 'SteamVR registration tool'
    $package = Join-Path $Project 'pc-server\build\steamvr\vrbox'
    Require-File (Join-Path $package 'driver.vrdrivermanifest') 'VR Box driver manifest'
    Require-File (Join-Path $package 'bin\win64\driver_vrbox.dll') 'VR Box HMD driver'
    Require-File (Join-Path $package 'resources\settings\default.vrsettings') 'VR Box driver settings'
    $manifest = [IO.File]::ReadAllText((Join-Path $package 'driver.vrdrivermanifest')) | ConvertFrom-Json
    if ($manifest.name -ne 'vrbox') { throw 'Unexpected driver manifest; refusing to register another driver.' }
    $package = Get-ShortPath $package
    if ($package -match '[^\x00-\x7F]') {
        throw 'SteamVR needs an ASCII driver path. Windows has no short alias here; build the project in an ASCII path.'
    }
    return @{ Runtime = $runtime; Tool = $tool; Startup = (Join-Path $runtime 'bin\win64\vrstartup.exe'); Package = $package }
}

function Register-Driver($SteamVR, [bool]$ReadOnly) {
    $found = Invoke-PathRegistry $SteamVR.Tool 'finddriver vrbox'
    if ($found.Code -eq 0) {
        $current = Get-ShortPath $found.Text
        if (-not [string]::Equals($current, $SteamVR.Package, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Another VR Box driver is registered at $($found.Text). No registrations were changed."
        }
        Write-Host 'VR Box driver is already registered; keeping the existing registration.'
        return
    }
    if ($found.Code -ne 1) { throw "Cannot resolve a unique VR Box registration: $($found.Text) (code $($found.Code))." }
    if ($ReadOnly) { Write-Host "Would register: $($SteamVR.Package)"; return }
    $added = Invoke-PathRegistry $SteamVR.Tool ('adddriver "' + $SteamVR.Package + '"')
    if ($added.Code -ne 0) { throw "Driver registration failed: $($added.Text)" }
    $verified = Invoke-PathRegistry $SteamVR.Tool 'finddriver vrbox'
    if ($verified.Code -ne 0 -or -not [string]::Equals((Get-ShortPath $verified.Text), $SteamVR.Package, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SteamVR did not confirm this driver registration. Server and SteamVR were not started.'
    }
    Write-Host 'VR Box driver registered successfully.'
}

function Get-SessionName([string]$Project) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes((Get-ShortPath $Project).ToUpperInvariant())
        return 'Local\VRBoxLauncher_' + [BitConverter]::ToString($hash.ComputeHash($bytes)).Replace('-', '').Substring(0, 24)
    } finally { $hash.Dispose() }
}

function Start-SteamVR([string]$Startup) {
    Write-Host 'Server is ready. Starting SteamVR...'
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Startup
    $info.WorkingDirectory = [IO.Path]::GetDirectoryName($Startup)
    $info.UseShellExecute = $true
    $started = [Diagnostics.Process]::Start($info)
    if ($null -ne $started) { $started.Dispose() }
    Write-Host 'On the phone: Connect with the IPv4/PIN above. Start your VR game on the PC, then use Recenter in 3s.'
}

function Invoke-Server([string]$Server, [string]$Arguments, [string]$Project,
                       [Threading.EventWaitHandle]$Stop, [string]$SteamVRStartup) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-ConsoleProcessInfo $Server $Arguments $Project
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Cannot start VR Box server.' }
        $readers = @($process.StandardOutput, $process.StandardError)
        $tasks = @($readers[0].ReadLineAsync(), $readers[1].ReadLineAsync())
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $ready = $false
        while ($null -ne $tasks[0] -or $null -ne $tasks[1] -or -not $process.HasExited) {
            if ($Stop.WaitOne(0)) {
                Write-Host 'Stop requested. Stopping only this launcher-owned VR Box server; SteamVR and games are left open.'
                return
            }
            $progress = $false
            for ($i = 0; $i -lt 2; $i++) {
                if ($null -eq $tasks[$i] -or -not $tasks[$i].IsCompleted) { continue }
                $progress = $true
                $line = $tasks[$i].GetAwaiter().GetResult()
                if ($null -eq $line) { $tasks[$i] = $null; continue }
                Write-Host $line
                $tasks[$i] = $readers[$i].ReadLineAsync()
                if ($i -eq 0 -and -not $ready -and $line.StartsWith('Server ready on ', [StringComparison]::Ordinal)) {
                    $ready = $true
                    if (-not $process.HasExited -and -not $Stop.WaitOne(0) -and $SteamVRStartup) {
                        Start-SteamVR $SteamVRStartup
                    }
                }
            }
            if (-not $ready -and $clock.Elapsed.TotalSeconds -ge 15) {
                throw 'Server did not become ready within 15 seconds. SteamVR was not started.'
            }
            if (-not $progress) { Start-Sleep -Milliseconds 20 }
        }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "VR Box server exited with code $($process.ExitCode). See the error above." }
        if (-not $ready) { throw 'Server exited without becoming ready. SteamVR was not started.' }
    } finally {
        # Never kill by process name: this handle belongs only to the child we started.
        if ($started -and -not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(3000) }
        $process.Dispose()
    }
}

function Invoke-Launcher([string]$Action, [string]$Project, [bool]$ReadOnly, [bool]$LocalOnly, [int]$Seconds) {
    $Project = [IO.Path]::GetFullPath($Project)
    if ($Action -eq 'Apk') {
        $apk = Join-Path $Project 'android-client\app\build\outputs\apk\debug\app-debug.apk'
        Require-File $apk 'Android APK'
        Write-Host "APK: $apk"
        Write-Host 'Transfer this APK to the phone and install/update VR Box Client. Phone installation is not automatic.'
        if (-not $ReadOnly) {
            $info = [Diagnostics.ProcessStartInfo]::new()
            $info.FileName = Join-Path $env:SystemRoot 'explorer.exe'
            $info.Arguments = '/select,"' + $apk + '"'
            $info.UseShellExecute = $true
            $explorer = [Diagnostics.Process]::Start($info)
            if ($null -ne $explorer) { $explorer.Dispose() }
        }
        return
    }
    $name = Get-SessionName $Project
    if ($Action -eq 'Stop') {
        if ($ReadOnly) { Write-Host 'Check only: no stop signal was sent.'; return }
        $signal = $null
        try {
            $signal = [Threading.EventWaitHandle]::OpenExisting($name + '_stop')
            [void]$signal.Set()
            Write-Host 'Stop request sent to this project launcher. SteamVR and games are not closed.'
        } catch [Threading.WaitHandleCannotBeOpenedException] {
            Write-Host 'No launcher-managed server is running here. A manually started server must be stopped with Ctrl+C in its own window.'
        } finally { if ($null -ne $signal) { $signal.Dispose() } }
        return
    }
    $server = Join-Path $Project 'pc-server\build\Release\vrbox_server.exe'
    Require-File $server 'PC server'
    $steam = if ($Action -eq 'SteamVR') { Get-SteamVR $Project } else { $null }
    $mutex = $null
    $stop = $null
    $owned = $false
    $oldOverride = $env:VR_PATHREG_OVERRIDE
    try {
        # Native OpenVR tools cannot reliably read a Unicode override from the Windows environment.
        if ($null -ne $steam -and $oldOverride) { $env:VR_PATHREG_OVERRIDE = Get-ShortPath $oldOverride }
        if ($ReadOnly) {
            if ($null -ne $steam) { Register-Driver $steam $true; Write-Host "SteamVR: $($steam.Runtime)" }
            Write-Host "Check passed: $Action; server: $server. No server/SteamVR start or registration changes."
            return
        }
        $mutex = [Threading.Mutex]::new($false, $name + '_lock')
        try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
        if (-not $owned) { throw 'A launcher is already running for this project. Use its open window or STOP_SERVER.bat.' }
        if (@(Get-Process -Name vrbox_server -ErrorAction SilentlyContinue).Count -gt 0) {
            throw 'A VR Box server is already running. Stop it in its own window first; no existing process was changed.'
        }
        if ($null -ne $steam -and @(Get-Process -Name vrserver,vrcompositor,vrmonitor,vrstartup -ErrorAction SilentlyContinue).Count -gt 0) {
            throw 'SteamVR is already running. Close SteamVR first, then run START_VR.bat so the server can start before it.'
        }
        if ($null -ne $steam) { Register-Driver $steam $false }
        $stop = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, $name + '_stop')
        [void]$stop.Reset()
        [string]$arguments = switch ($Action) { 'SteamVR' { '--steamvr' } 'Stereo' { '--stereo-test-pattern' } 'Desktop' { '' } }
        if ($LocalOnly) { $arguments += ' --bind 127.0.0.1' }
        if ($Seconds -gt 0) { $arguments += " --run-seconds $Seconds" }
        Write-Host 'Keep this window open. Use Ctrl+C or STOP_SERVER.bat to stop the PC stream.'
        if ($LocalOnly) { Write-Host 'Loopback test only: a phone cannot connect to this address.' }
        else { Write-Host 'Phone and PC must be on the same trusted LAN. Use the printed PC IPv4 and current Pairing PIN.' }
        if ($Action -eq 'Desktop') { Write-Host 'DESKTOP MONO: the paired phone will see your primary desktop. SteamVR is not started.' }
        if ($Action -eq 'Stereo') { Write-Host 'SYNTHETIC STEREO TEST: no desktop capture and no SteamVR.' }
        $startup = if ($null -ne $steam) { $steam.Startup } else { '' }
        Invoke-Server $server $arguments.Trim() $Project $stop $startup
    } finally {
        $env:VR_PATHREG_OVERRIDE = $oldOverride
        if ($null -ne $stop) { $stop.Dispose() }
        if ($owned) { $mutex.ReleaseMutex() }
        if ($null -ne $mutex) { $mutex.Dispose() }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        Invoke-Launcher $Mode $Root $CheckOnly.IsPresent $Loopback.IsPresent $RunSeconds
        exit 0
    } catch {
        Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}
