param(
    [string]$Pin = '123456',
    [switch]$Stereo,
    [switch]$SteamVR,
    [switch]$ExpectCompositor
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Run ONLY against: vrbox_server --test-pattern --bind 127.0.0.1 --pin 123456 --fps 30
# This script requests video; synthetic-only safety depends on those server launch flags.
# -Stereo requires --stereo-test-pattern. -SteamVR requires --steamvr and tests tracking/control.
# Add -ExpectCompositor only for an explicitly started SteamVR test runtime; never a desktop source.
if ($ExpectCompositor -and -not $SteamVR) { throw '-ExpectCompositor requires -SteamVR' }
$expectedSource = if ($SteamVR) { 'steamvr' } else { 'test_pattern' }
$expectedLayout = if ($Stereo -or $SteamVR) { 'stereo_sbs' } else { 'mono' }
$script:Checks = 0

function Assert-That([bool]$Condition, [string]$Message) {
    $script:Checks++
    if (-not $Condition) { throw $Message }
}

function Get-Crc([byte[]]$Bytes, [int]$Count) {
    [uint32]$crc = 65535
    for ($i = 0; $i -lt $Count; $i++) {
        $crc = $crc -bxor ([uint32]$Bytes[$i] -shl 8)
        for ($bit = 0; $bit -lt 8; $bit++) {
            if ($crc -band 32768) { $crc = (($crc -shl 1) -bxor 4129) -band 65535 }
            else { $crc = ($crc -shl 1) -band 65535 }
        }
    }
    return [uint16]$crc
}

function New-Control([byte]$Type, [byte[]]$Body) {
    [byte[]]$message = @(0x52, 0x56, 1, $Type) + [BitConverter]::GetBytes([uint32]$Body.Length) + $Body
    return ,$message
}

function Send-Control($Stream, [byte]$Type, [hashtable]$Object) {
    [byte[]]$body = [Text.Encoding]::UTF8.GetBytes(($Object | ConvertTo-Json -Compress))
    [byte[]]$message = New-Control $Type $body
    $Stream.Write($message, 0, $message.Length)
}

function Read-Exact($Stream, [int]$Length) {
    [byte[]]$bytes = New-Object byte[] $Length
    $position = 0
    while ($position -lt $Length) {
        $count = $Stream.Read($bytes, $position, $Length - $position)
        if ($count -eq 0) { throw 'Unexpected control EOF' }
        $position += $count
    }
    return ,$bytes
}

function Read-Control($Stream) {
    [byte[]]$header = Read-Exact $Stream 8
    Assert-That ($header[0] -eq 0x52 -and $header[1] -eq 0x56 -and $header[2] -eq 1) 'Invalid control header'
    $length = [BitConverter]::ToUInt32($header, 4)
    Assert-That ($length -le 65536) 'Oversized control message'
    [byte[]]$body = Read-Exact $Stream $length
    $object = $null
    if ($header[3] -ne 14) { $object = [Text.Encoding]::UTF8.GetString($body) | ConvertFrom-Json }
    return @{ Type = [int]$header[3]; Body = $body; Object = $object }
}

function Wait-Control($Stream, [int]$Type) {
    for ($i = 0; $i -lt 20; $i++) {
        $message = Read-Control $Stream
        if ($message.Type -eq 11) { throw "Server error: $($message.Object.msg)" }
        if ($message.Type -eq $Type) { return $message }
    }
    throw "Expected control type $Type was not received"
}

function Open-Control {
    $client = New-Object Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', 8765)
    $client.ReceiveTimeout = 4000
    $client.SendTimeout = 4000
    return $client
}

function New-Datagram([byte]$Channel, [byte[]]$Payload, [uint32]$Sequence) {
    [byte[]]$header = @(0x52, 0x56, 1, $Channel, 0, 0) + [BitConverter]::GetBytes($Sequence) +
        [BitConverter]::GetBytes([uint16]$Payload.Length)
    [byte[]]$packet = $header + [BitConverter]::GetBytes((Get-Crc $header 12)) + $Payload
    return ,$packet
}

function Read-Idr($Udp, [long]$AfterFrame = -1, [long]$BeforeFrame = [long]::MaxValue) {
    $deadline = [DateTime]::UtcNow.AddSeconds(4)
    $parts = @{}
    $frameId = -1L
    $total = 0
    while ([DateTime]::UtcNow -lt $deadline) {
        $remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
        [byte[]]$packet = $Udp.Receive([ref]$remote)
        Assert-That ($packet.Length -ge 14 -and $packet.Length -le 1200) 'UDP size limit violated'
        Assert-That ((Get-Crc $packet 12) -eq [BitConverter]::ToUInt16($packet, 12)) 'UDP header CRC failed'
        Assert-That ([BitConverter]::ToUInt16($packet, 10) -eq $packet.Length - 14) 'UDP payload length mismatch'
        if ($packet[3] -ne 1) { continue }
        Assert-That ($packet.Length -gt 30 -and $packet[22] -eq 0) 'Video header/subtype mismatch'
        $id = [long][BitConverter]::ToUInt32($packet, 18)
        if ($id -le $AfterFrame) { continue }
        if ($id -ge $BeforeFrame) { throw "No recovery IDR before frame $BeforeFrame; periodic GOPs do not count" }
        if ($packet[23] -ne 0) { continue }
        $index = [BitConverter]::ToUInt16($packet, 14)
        $fragmentCount = [BitConverter]::ToUInt16($packet, 16)
        Assert-That ($fragmentCount -gt 0 -and $fragmentCount -le 2048 -and $index -lt $fragmentCount) 'Invalid video fragments'
        if ($frameId -ne $id) { $frameId = $id; $total = $fragmentCount; $parts = @{} }
        Assert-That ($fragmentCount -eq $total) 'Inconsistent fragment count'
        [byte[]]$fragment = $packet[30..($packet.Length - 1)]
        $parts[[int]$index] = $fragment
        if ($parts.Count -eq $total) {
            $output = New-Object IO.MemoryStream
            try {
                for ($i = 0; $i -lt $total; $i++) { $output.Write($parts[$i], 0, $parts[$i].Length) }
                [byte[]]$bytes = $output.ToArray()
            } finally { $output.Dispose() }
            $types = @{}
            for ($i = 0; $i -lt $bytes.Length - 3; $i++) {
                if ($bytes[$i] -eq 0 -and $bytes[$i + 1] -eq 0 -and $bytes[$i + 2] -eq 1) {
                    $types[[int]($bytes[$i + 3] -band 31)] = $true
                }
            }
            Assert-That ($types.ContainsKey(5) -and $types.ContainsKey(7) -and $types.ContainsKey(8)) 'IDR lacks Annex B SPS/PPS'
            return $frameId
        }
    }
    throw 'No complete IDR received'
}

function Test-Imu($Stream, $Udp) {
    Send-Control $Stream 7 @{ imu_rate_hz = 100 }
    $imuAck = Wait-Control $Stream 4
    Assert-That ($imuAck.Object.imu_rate_hz -eq 100) 'IMU acknowledgement mismatch'
    $baseline = Wait-Control $Stream 9
    Assert-That ($baseline.Object.imu_samples -eq 0) 'New session inherited IMU samples'
    # Reuse sequence 1 in both sessions to also check that IMU sequence tracking resets.
    [byte[]]$imuPayload = [BitConverter]::GetBytes([uint64]123) + [BitConverter]::GetBytes([uint32]1) + @(1, 0, 0, 0)
    foreach ($value in @(0, 0, 0, 1, 0, 0, 0, 0, 0, 9.81)) { $imuPayload += [BitConverter]::GetBytes([single]$value) }
    [byte[]]$imuPacket = New-Datagram 2 $imuPayload 1
    [void]$Udp.Send($imuPacket, $imuPacket.Length)
    $receivedImu = $false
    for ($i = 0; $i -lt 3 -and -not $receivedImu; $i++) {
        $telemetry = Wait-Control $Stream 9
        $receivedImu = $telemetry.Object.imu_samples -ge 1
    }
    Assert-That $receivedImu 'Server did not receive IMU sample'
    Assert-That ($telemetry.Object.imu_samples -eq 1) 'Unexpected IMU sample count'
    Assert-That ($telemetry.Object.fps -gt 0) 'Server reports no video frames'
}

$video = New-Object Net.Sockets.UdpClient(0)
$imu = New-Object Net.Sockets.UdpClient(0)
$reconnectVideo = $null
$reconnectImu = $null
$control = $null
$busy = $null
$invalid = $null
try {
    $video.Client.ReceiveBufferSize = 1048576
    $video.Client.ReceiveTimeout = 3000
    $video.Connect('127.0.0.1', 8766)
    $imu.Connect('127.0.0.1', 8767)
    $video.Client.ReceiveTimeout = 200
    [byte[]]$unauthenticated = New-Datagram 3 (New-Object byte[] 14) 0
    [void]$video.Send($unauthenticated, $unauthenticated.Length)
    $receivedBeforeAuth = $false
    try {
        $remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
        [void]$video.Receive([ref]$remote)
        $receivedBeforeAuth = $true
    } catch [Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -ne [Net.Sockets.SocketError]::TimedOut) { throw }
    }
    Assert-That (-not $receivedBeforeAuth) 'Unauthenticated UDP must not be echoed'
    $video.Client.ReceiveTimeout = 3000
    $hello = @{ proto = 1; device = 'loopback-test'; pin = $Pin;
        video_port = $video.Client.LocalEndPoint.Port; imu_port = $imu.Client.LocalEndPoint.Port }
    if ($Stereo -or $SteamVR) { $hello.layouts = @('mono', 'stereo_sbs') }
    if ($SteamVR) { $hello.display_rotation = 90 }
    if ($Stereo -or $SteamVR) {
        $legacyHello = $hello.Clone()
        $legacyHello.Remove('layouts')
        $control = Open-Control
        Send-Control ($control.GetStream()) 1 $legacyHello
        $legacyReply = Read-Control ($control.GetStream())
        Assert-That ($legacyReply.Type -eq 11 -and $legacyReply.Object.msg.Contains('stereo_sbs')) 'Stereo mode did not reject an old MONO-only client'
        $control.Close()
    }
    $control = Open-Control
    $stream = $control.GetStream()
    [byte[]]$helloBody = [Text.Encoding]::UTF8.GetBytes(($hello | ConvertTo-Json -Compress))
    [byte[]]$helloPacket = New-Control 1 $helloBody
    # Deliberately split a control header across writes.
    $stream.Write($helloPacket, 0, 3)
    $stream.Write($helloPacket, 3, 4)
    $stream.Write($helloPacket, 7, $helloPacket.Length - 7)
    $ack = Wait-Control $stream 2
    Assert-That ($ack.Object.proto -eq 1 -and $ack.Object.ports.tcp -eq 8765 -and
        $ack.Object.ports.video -eq 8766 -and $ack.Object.codecs[0] -eq 'h264') 'HELLO capabilities mismatch'

    $busy = Open-Control
    $busyReply = Read-Control ($busy.GetStream())
    Assert-That ($busyReply.Type -eq 11 -and $busyReply.Object.code -eq 8) 'Second client was not rejected as busy'
    $busy.Close()
    $busy = $null

    [byte[]]$settingsBody = [Text.Encoding]::UTF8.GetBytes('{"codec":"h264","width":1280,"height":720,"fps":30,"bitrate_kbps":8000}')
    [byte[]]$pingBody = [BitConverter]::GetBytes([uint64]123456789)
    # Deliberately coalesce two control messages in one write.
    [byte[]]$messages = (New-Control 3 $settingsBody) + (New-Control 14 $pingBody)
    $stream.Write($messages, 0, $messages.Length)
    $sawPing = $false
    $sawSettings = $false
    $videoSettings = $null
    do {
        $reply = Read-Control $stream
        if ($reply.Type -eq 11) { throw "Server error: $($reply.Object.msg)" }
        if ($reply.Type -eq 14) {
            Assert-That ([BitConverter]::ToUInt64($reply.Body, 0) -eq 123456789) 'TCP ping did not echo'
            $sawPing = $true
        }
        if ($reply.Type -eq 4) {
            Assert-That ($reply.Object.source -eq $expectedSource -and $reply.Object.layout -eq $expectedLayout) 'Server source/layout does not match this test mode'
            $videoSettings = $reply.Object
            $sawSettings = $true
        }
    } while ($reply.Type -ne 5)
    Assert-That ($sawPing -and $sawSettings) 'Coalesced control framing failed'
    Assert-That ($videoSettings.fps -ge 4 -and $videoSettings.gop_s -eq 1 -and $videoSettings.b_frames -eq 0) 'Recovery test requires fps >= 4, a one-second GOP and no B-frames'
    $recoveryWindow = [long][Math]::Floor($videoSettings.fps / 2)
    if ($SteamVR) {
        Assert-That ($ack.Object.features -contains 'recenter') 'SteamVR must advertise Recenter'
        Send-Control $stream 7 @{ imu_rate_hz = 100 }
        $imuAck = Wait-Control $stream 4
        Assert-That ($imuAck.Object.imu_rate_hz -eq 100) 'SteamVR must start IMU before any compositor frame'
        Send-Control $stream 15 @{}
        $recenter = Wait-Control $stream 4
        Assert-That (-not $recenter.Object.recentered) 'Recenter succeeded without a head sample'
        $recentered = $false
        # Exceed the phone control socket timeout while no compositor is required.
        $cycles = if ($ExpectCompositor) { 100 } else { 16 }
        $pauseMs = if ($ExpectCompositor) { 10 } else { 500 }
        for ($i = 1; $i -le $cycles; $i++) {
            [byte[]]$imuPayload = [BitConverter]::GetBytes([uint64]($i * 10000000)) +
                [BitConverter]::GetBytes([uint32]$i) + @(1, 0, 0, 0)
            $halfAngle = $i * 0.01
            foreach ($value in @(0, 0, [Math]::Sin($halfAngle), [Math]::Cos($halfAngle), 0, 0, 0, 0, 0, 9.81)) {
                $imuPayload += [BitConverter]::GetBytes([single]$value)
            }
            [byte[]]$imuPacket = New-Datagram 2 $imuPayload ([uint32]$i)
            [void]$imu.Send($imuPacket, $imuPacket.Length)
            Start-Sleep -Milliseconds 80
            Send-Control $stream 15 @{}
            $recenter = Wait-Control $stream 4
            $recentered = $recentered -or $recenter.Object.recentered
            [byte[]]$ping = New-Control 14 ([BitConverter]::GetBytes([uint64]$i))
            $stream.Write($ping, 0, $ping.Length)
            $pong = Wait-Control $stream 14
            Assert-That ([BitConverter]::ToUInt64($pong.Body, 0) -eq $i) 'Control stalled while waiting for SteamVR'
            Start-Sleep -Milliseconds $pauseMs
        }
        Assert-That $recentered 'Fresh authenticated IMU never enabled Recenter'
        if ($ExpectCompositor) {
            $compositorIdr = Read-Idr $video
            Assert-That ($compositorIdr -ge 0) 'No complete SteamVR compositor IDR'
        } else {
            Assert-That ($video.Client.Available -eq 0) 'Unexpected video without a compositor; no fallback is permitted'
        }
        Start-Sleep -Milliseconds 300
        Send-Control $stream 15 @{}
        $recenter = Wait-Control $stream 4
        Assert-That (-not $recenter.Object.recentered) 'Stale tracking still allowed Recenter'
        Send-Control $stream 13 @{}
        Assert-That ($stream.ReadByte() -eq -1) 'SteamVR session did not close cleanly'
        "SteamVR session smoke passed ($script:Checks checks): startup without frames, live control, IMU, fresh/stale Recenter; compositor required: $ExpectCompositor."
        return
    }
    $firstIdr = Read-Idr $video
    Assert-That ($firstIdr -eq 0) 'Initial session did not start with IDR frame 0'
    # Request immediately, accepting only an IDR in the first half-GOP, never the periodic one.
    Send-Control $stream 12 @{}
    $nextIdr = Read-Idr $video $firstIdr ($firstIdr + $recoveryWindow)
    Assert-That ($nextIdr -gt $firstIdr) 'TCP keyframe recovery failed'

    [byte[]]$timingPayload = [BitConverter]::GetBytes([uint64]0x0102030405060708) +
        [BitConverter]::GetBytes([uint32]0x0A0B0C0D) + @(0, 0)
    [byte[]]$timing = New-Datagram 3 $timingPayload 0x0A0B0C0D
    [void]$video.Send($timing, $timing.Length)
    $echoed = $false
    for ($i = 0; $i -lt 300 -and -not $echoed; $i++) {
        $remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
        [byte[]]$packet = $video.Receive([ref]$remote)
        if ($packet[3] -eq 3) {
            Assert-That ([Convert]::ToBase64String($packet) -eq [Convert]::ToBase64String($timing)) 'Timing echo changed bytes'
            $echoed = $true
        }
    }
    Assert-That $echoed 'Timing echo not received'

    Test-Imu $stream $imu
    Send-Control $stream 13 @{ reason = 'smoke_test' }
    Assert-That ($stream.ReadByte() -eq -1) 'SESSION_END did not close TCP'
    $control.Close()

    # Keep the old sockets bound so queued packets or reused ports cannot fake a restart.
    $reconnectVideo = New-Object Net.Sockets.UdpClient(0)
    $reconnectVideo.Client.ReceiveBufferSize = 1048576
    $reconnectVideo.Client.ReceiveTimeout = 3000
    $reconnectVideo.Connect('127.0.0.1', 8766)
    $reconnectImu = New-Object Net.Sockets.UdpClient(0)
    $reconnectImu.Connect('127.0.0.1', 8767)
    $hello.video_port = $reconnectVideo.Client.LocalEndPoint.Port
    $hello.imu_port = $reconnectImu.Client.LocalEndPoint.Port
    $control = Open-Control
    $stream = $control.GetStream()
    Send-Control $stream 1 $hello
    [void](Wait-Control $stream 2)
    Send-Control $stream 3 @{ codec = 'h264' }
    $reconnectSettings = Wait-Control $stream 4
    Assert-That ($reconnectSettings.Object.source -eq 'test_pattern' -and $reconnectSettings.Object.layout -eq $expectedLayout -and
        $reconnectSettings.Object.fps -eq $videoSettings.fps -and $reconnectSettings.Object.gop_s -eq 1 -and
        $reconnectSettings.Object.b_frames -eq 0) 'Reconnected video settings mismatch'
    [void](Wait-Control $stream 5)
    $reconnectIdr = Read-Idr $reconnectVideo
    Assert-That ($reconnectIdr -eq 0) 'Reconnected video did not restart at IDR frame 0'
    # Simulate a missing fragment; a new session also avoids the TCP request's 500 ms throttle.
    [byte[]]$nackPayload = [BitConverter]::GetBytes([uint32]$reconnectIdr) +
        [BitConverter]::GetBytes([uint16]1) + [BitConverter]::GetBytes([uint16]0)
    [byte[]]$nack = New-Datagram 4 $nackPayload 1
    [void]$reconnectVideo.Send($nack, $nack.Length)
    $nackIdr = Read-Idr $reconnectVideo $reconnectIdr ($reconnectIdr + $recoveryWindow)
    Assert-That ($nackIdr -gt $reconnectIdr) 'UDP NACK keyframe recovery failed'
    Test-Imu $stream $reconnectImu
    Send-Control $stream 13 @{}
    Assert-That ($stream.ReadByte() -eq -1) 'Reconnected session did not close cleanly'
    $control.Close()
    $control = $null

    $invalid = Open-Control
    [byte[]]$oversizedHeader = @(0x52, 0x56, 1, 1, 255, 255, 255, 255)
    $invalid.GetStream().Write($oversizedHeader, 0, $oversizedHeader.Length)
    Assert-That ($invalid.GetStream().ReadByte() -eq -1) 'Oversized TCP body must close immediately'
    $invalid.Close()

    $invalid = Open-Control
    $hello.pin = if ($Pin -eq '000000') { '999999' } else { '000000' }
    Send-Control ($invalid.GetStream()) 1 $hello
    $denied = Read-Control ($invalid.GetStream())
    Assert-That ($denied.Type -eq 11 -and $denied.Object.code -eq 9) 'Wrong pairing PIN was not rejected'
    "Session smoke passed ($script:Checks checks): control framing, pairing, single-client limit, H.264 fragments, timing, off-GOP TCP/NACK IDRs, video/IMU reconnect."
} finally {
    if ($control) { $control.Close() }
    if ($busy) { $busy.Close() }
    if ($invalid) { $invalid.Close() }
    $video.Close()
    $imu.Close()
    if ($reconnectVideo) { $reconnectVideo.Close() }
    if ($reconnectImu) { $reconnectImu.Close() }
}
