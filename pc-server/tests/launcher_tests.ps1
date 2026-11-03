#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $project 'scripts\launch.ps1')
$script:checks = 0
function Assert-That([bool]$Value, [string]$Message) {
    $script:checks++
    if (-not $Value) { throw $Message }
}

# Only fixture executables are launched. No real SteamVR, network listeners, desktop capture or registry changes.
$temporary = Join-Path $project ('.build-tmp\launcher-tests-' + [Guid]::NewGuid().ToString('N'))
$fixture = Join-Path $temporary 'space & bang! [test]'
$globalRegistry = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'openvr\openvrpaths.vrpath'
$originalRegistry = if ([IO.File]::Exists($globalRegistry)) { [IO.File]::ReadAllText($globalRegistry) } else { $null }
$oldOverride = $env:VR_PATHREG_OVERRIDE
$activeRuns = [Collections.Generic.List[object]]::new()
$ownedProcesses = [Collections.Generic.List[Diagnostics.Process]]::new()
$utf8 = [Text.UTF8Encoding]::new($false)

function Start-Batch([string]$Name, [string]$Arguments = '') {
    $file = Join-Path $fixture $Name
    $info = New-ConsoleProcessInfo $env:ComSpec ('/d /s /c ""' + $file + '" ' + $Arguments + '"') $temporary
    $info.EnvironmentVariables['VRBOX_NO_PAUSE'] = '1'
    $info.EnvironmentVariables['VR_PATHREG_OVERRIDE'] = Get-ShortPath (Join-Path $fixture 'openvrpaths.vrpath')
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    [void]$process.Start()
    $run = @{ Process = $process; Output = $process.StandardOutput.ReadToEndAsync(); Errors = $process.StandardError.ReadToEndAsync() }
    $activeRuns.Add($run)
    return $run
}

function Finish-Batch($Run) {
    if (-not $Run.Process.WaitForExit(20000)) { throw 'Fixture launcher did not finish within 20 seconds.' }
    $result = @{ Code = $Run.Process.ExitCode; Text = $Run.Output.GetAwaiter().GetResult() + $Run.Errors.GetAwaiter().GetResult() }
    return $result
}

function Run-Batch([string]$Name, [string]$Arguments = '') { return Finish-Batch (Start-Batch $Name $Arguments) }
function Clear-Markers {
    foreach ($name in @('ready.txt', 'startup.txt', 'server-pid.txt', 'arguments.txt')) { [IO.File]::Delete((Join-Path $fixture $name)) }
}

try {
    if (@(Get-Process -Name vrbox_server,vrserver,vrcompositor,vrmonitor,vrstartup -ErrorAction SilentlyContinue).Count -gt 0) {
        throw 'Close existing VR Box/SteamVR sessions before launcher tests. No existing process will be stopped.'
    }
    foreach ($relative in @('scripts', 'pc-server\build\Release', 'pc-server\build\steamvr\vrbox\bin\win64',
        'pc-server\build\steamvr\vrbox\resources\settings', 'runtime\bin\win64', 'android-client\app\build\outputs\apk\debug')) {
        [void][IO.Directory]::CreateDirectory((Join-Path $fixture $relative))
    }
    $batches = @('START_VR.bat', 'TEST_STEREO.bat', 'DESKTOP_MONO.bat', 'OPEN_APK.bat', 'STOP_SERVER.bat')
    foreach ($name in $batches) { [IO.File]::Copy((Join-Path $project $name), (Join-Path $fixture $name)) }
    [IO.File]::Copy((Join-Path $project 'scripts\launch.ps1'), (Join-Path $fixture 'scripts\launch.ps1'))
    $server = Join-Path $fixture 'pc-server\build\Release\vrbox_server.exe'
    $compiled = Join-Path $temporary 'fixture.exe'
    Add-Type -OutputAssembly $compiled -OutputType WindowsApplication -TypeDefinition @'
using System;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Diagnostics;
public static class LauncherFixture {
    public static int Main(string[] args) {
        string file = Assembly.GetExecutingAssembly().Location;
        string root = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(file), "..", "..", ".."));
        string name = Path.GetFileNameWithoutExtension(file);
        if (name == "vrpathreg") {
            string state = Path.Combine(root, "driver.txt");
            if (File.Exists(Path.Combine(root, "duplicate-driver"))) { Console.Error.WriteLine("duplicate drivers"); return 2; }
            if (args[0] == "finddriver") {
                if (!File.Exists(state)) return 1;
                Console.WriteLine(File.ReadAllText(state)); return 0;
            }
            if (args[0] == "adddriver") {
                File.WriteAllText(state, args[1]);
                File.AppendAllText(Path.Combine(root, "registrations.txt"), "x"); return 0;
            }
            return 8;
        }
        if (name == "vrstartup") {
            bool ready = File.Exists(Path.Combine(root, "ready.txt"));
            File.WriteAllText(Path.Combine(root, "startup.txt"), ready ? "ready" : "early");
            return ready ? 0 : 4;
        }
        if (name == "vrserver") { Thread.Sleep(60000); return 0; }
        File.WriteAllText(Path.Combine(root, "server-pid.txt"), Process.GetCurrentProcess().Id.ToString());
        File.WriteAllText(Path.Combine(root, "arguments.txt"), String.Join(" ", args));
        if (File.Exists(Path.Combine(root, "fail-server"))) { Console.Error.WriteLine("simulated server failure"); return 23; }
        if (File.Exists(Path.Combine(root, "no-ready"))) return 0;
        File.WriteAllText(Path.Combine(root, "ready.txt"), "ready");
        Console.WriteLine("Server ready on 127.0.0.1:8765 (fixture only). Ctrl+C to stop.");
        int i = Array.IndexOf(args, "--run-seconds");
        Thread.Sleep((i >= 0 ? Int32.Parse(args[i + 1]) : 60) * 1000);
        Console.WriteLine("Server stopped.");
        return 0;
    }
}
'@
    [IO.File]::Copy($compiled, $server)
    foreach ($name in @('vrpathreg.exe', 'vrstartup.exe', 'vrserver.exe')) {
        [IO.File]::Copy($server, (Join-Path $fixture ('runtime\bin\win64\' + $name)))
    }
    $package = Join-Path $fixture 'pc-server\build\steamvr\vrbox'
    [IO.File]::WriteAllText((Join-Path $package 'driver.vrdrivermanifest'), '{"name":"vrbox"}', $utf8)
    [IO.File]::WriteAllText((Join-Path $package 'resources\settings\default.vrsettings'), '{}', $utf8)
    [IO.File]::WriteAllBytes((Join-Path $package 'bin\win64\driver_vrbox.dll'), [byte[]]@(0))
    [IO.File]::WriteAllBytes((Join-Path $fixture 'android-client\app\build\outputs\apk\debug\app-debug.apk'), [byte[]]@(0))
    $registry = @{ jsonid = 'vrpathreg'; version = 1; runtime = @((Join-Path $fixture 'runtime')); external_drivers = @() }
    [IO.File]::WriteAllText((Join-Path $fixture 'openvrpaths.vrpath'), ($registry | ConvertTo-Json), $utf8)
    $env:VR_PATHREG_OVERRIDE = Get-ShortPath (Join-Path $fixture 'openvrpaths.vrpath')

    foreach ($name in $batches) {
        $result = Run-Batch $name '-CheckOnly'
        Assert-That ($result.Code -eq 0) "$name preflight failed: $($result.Text)"
    }
    Assert-That (-not [IO.File]::Exists((Join-Path $fixture 'driver.txt'))) 'CheckOnly changed driver registration'
    Assert-That (-not [IO.File]::Exists((Join-Path $fixture 'ready.txt'))) 'CheckOnly started a server'
    Assert-That (-not [IO.File]::Exists((Join-Path $fixture 'startup.txt'))) 'CheckOnly started SteamVR'
    Assert-That ((Get-SessionName $fixture) -eq (Get-SessionName (Get-ShortPath $fixture))) 'Stop signal differs for long/short aliases'

    $result = Run-Batch 'START_VR.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -eq 0) "VR launch failed: $($result.Text)"
    Assert-That ([IO.File]::ReadAllText((Join-Path $fixture 'startup.txt')) -eq 'ready') 'SteamVR started before server readiness'
    Assert-That ([IO.File]::ReadAllText((Join-Path $fixture 'arguments.txt')) -eq '--steamvr --bind 127.0.0.1 --run-seconds 1') 'Wrong SteamVR server arguments'
    Assert-That ([IO.File]::ReadAllText((Join-Path $fixture 'registrations.txt')) -eq 'x') 'Missing initial driver registration'
    $result = Run-Batch 'START_VR.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -eq 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'registrations.txt')) -eq 'x') 'Existing driver was registered again'

    Clear-Markers
    $result = Run-Batch 'TEST_STEREO.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -eq 0) "Stereo test launch failed: $($result.Text)"
    Assert-That ([IO.File]::ReadAllText((Join-Path $fixture 'arguments.txt')) -eq '--stereo-test-pattern --bind 127.0.0.1 --run-seconds 1') 'Wrong stereo arguments'
    Assert-That (-not [IO.File]::Exists((Join-Path $fixture 'startup.txt'))) 'Synthetic mode started SteamVR'
    $result = Run-Batch 'DESKTOP_MONO.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -eq 0) "MONO launch failed: $($result.Text)"
    Assert-That ([IO.File]::ReadAllText((Join-Path $fixture 'arguments.txt')) -eq '--bind 127.0.0.1 --run-seconds 1') 'MONO accidentally selected VR/test capture'

    foreach ($failure in @('fail-server', 'no-ready', 'duplicate-driver')) {
        Clear-Markers
        [IO.File]::WriteAllText((Join-Path $fixture $failure), 'test', $utf8)
        $result = Run-Batch 'START_VR.bat' '-Loopback -RunSeconds 1'
        Assert-That ($result.Code -ne 0) "$failure did not fail the batch"
        Assert-That (-not [IO.File]::Exists((Join-Path $fixture 'startup.txt'))) "$failure still started SteamVR"
        if ($failure -eq 'fail-server') { Assert-That ($result.Text.Contains('simulated server failure')) 'Server stderr was hidden' }
        [IO.File]::Delete((Join-Path $fixture $failure))
    }
    $other = Join-Path $fixture 'another project'
    [void][IO.Directory]::CreateDirectory($other)
    [IO.File]::WriteAllText((Join-Path $fixture 'driver.txt'), $other, $utf8)
    $result = Run-Batch 'START_VR.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -ne 0 -and [IO.File]::ReadAllText((Join-Path $fixture 'driver.txt')) -eq $other) 'Foreign driver registration was overwritten'
    [IO.File]::WriteAllText((Join-Path $fixture 'driver.txt'), (Get-ShortPath $package), $utf8)

    [IO.File]::Move($server, $server + '.saved')
    $result = Run-Batch 'TEST_STEREO.bat' '-CheckOnly'
    Assert-That ($result.Code -ne 0 -and $result.Text.Contains('Missing PC server')) 'Missing EXE was not explained'
    [IO.File]::Move($server + '.saved', $server)

    $busy = [Diagnostics.Process]::Start((New-ConsoleProcessInfo (Join-Path $fixture 'runtime\bin\win64\vrserver.exe') '' $fixture))
    $ownedProcesses.Add($busy)
    $result = Run-Batch 'START_VR.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -ne 0 -and -not $busy.HasExited) 'Launcher disturbed an already running VR session'
    $busy.Kill(); [void]$busy.WaitForExit(3000)

    Clear-Markers
    $running = Start-Batch 'TEST_STEREO.bat' '-Loopback'
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not [IO.File]::Exists((Join-Path $fixture 'ready.txt')) -and [DateTime]::UtcNow -lt $deadline -and -not $running.Process.HasExited) {
        Start-Sleep -Milliseconds 20
    }
    Assert-That ([IO.File]::Exists((Join-Path $fixture 'ready.txt'))) 'Managed fixture server did not start'
    $owned = [Diagnostics.Process]::GetProcessById([int][IO.File]::ReadAllText((Join-Path $fixture 'server-pid.txt')))
    $ownedProcesses.Add($owned)
    $result = Run-Batch 'TEST_STEREO.bat' '-Loopback -RunSeconds 1'
    Assert-That ($result.Code -ne 0 -and -not $owned.HasExited) 'Second launcher replaced the first server'
    Invoke-Launcher 'Stop' $other $false $false 0
    Assert-That (-not $owned.HasExited) 'Stop from another project affected this server'
    $result = Run-Batch 'STOP_SERVER.bat'
    Assert-That ($result.Code -eq 0) "Stop batch failed: $($result.Text)"
    $result = Finish-Batch $running
    Assert-That ($result.Code -eq 0 -and $owned.WaitForExit(3000)) 'Managed server survived the stop request'
    Assert-That ((Run-Batch 'STOP_SERVER.bat').Code -eq 0) 'Stopping an already stopped session failed'

    $manual = [Diagnostics.Process]::Start((New-ConsoleProcessInfo $server '' $fixture))
    $ownedProcesses.Add($manual)
    $result = Run-Batch 'STOP_SERVER.bat'
    Assert-That ($result.Code -eq 0 -and -not $manual.HasExited) 'Stop batch killed a manually started server'
    $manual.Kill(); [void]$manual.WaitForExit(3000)
    if ($null -ne $originalRegistry) {
        Assert-That ([IO.File]::ReadAllText($globalRegistry) -eq $originalRegistry) 'Global OpenVR registry changed during tests'
    }
    Write-Host "$script:checks launcher checks passed. Fixture processes only; no real SteamVR, desktop capture or network listeners."
} finally {
    $env:VR_PATHREG_OVERRIDE = $oldOverride
    # Let a live supervisor clean up its child after a failed assertion.
    if ([IO.Directory]::Exists($fixture)) {
        try {
            $signal = [Threading.EventWaitHandle]::OpenExisting((Get-SessionName $fixture) + '_stop')
            try { [void]$signal.Set() } finally { $signal.Dispose() }
        } catch [Threading.WaitHandleCannotBeOpenedException] {}
    }
    foreach ($process in $ownedProcesses) {
        if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(3000) }
        $process.Dispose()
    }
    foreach ($run in $activeRuns) {
        if (-not $run.Process.WaitForExit(3000)) { $run.Process.Kill(); [void]$run.Process.WaitForExit(3000) }
        $run.Process.Dispose()
    }
    if ([IO.Directory]::Exists($temporary)) { [IO.Directory]::Delete($temporary, $true) }
}
