<#
.SYNOPSIS
Bounded Docker smoke checks. Leaves synthetic records for audit; never resets data.
.DESCRIPTION
Run from any directory with Windows PowerShell 5.1. Docker Compose must already
be running. The fixed order-service must have no prior incidents, history or Redis
correlation members: repeat creation checks against a fresh isolated Compose
project/gateway, never delete retained audit data. Do not run concurrently with
other scenario producers against that service. Explicit env files are passed to
Compose in order; this script never reads their contents. ExpectedEventCount and
ExpectedThreshold default to the application's defaults (5); set them explicitly
if the running stack uses other values.
-IncludeAi authorizes ONE analysis POST (the server can make multiple provider
calls internally); it is never retried, including on timeout. Baseline incidents
expose rootService and creation time, not a direct event FK or traceId.
Dot-sourcing loads offline contract helpers only and does not contact anything.
.EXAMPLE
.\scripts\smoke-docker.ps1 -ProjectName incident-response-stage13 -EnvFile '.env','.env.stage13'
.EXAMPLE
.\scripts\smoke-docker.ps1 -ProjectName incident-response-stage13 -EnvFile '.env','.env.stage13' -IncludeAi
#>
[CmdletBinding()]
param(
    [string]$Gateway = 'http://127.0.0.1:8081',
    [string]$ProjectName,
    [string[]]$EnvFile,
    [string]$KafkaTopic = 'service-events',
    [ValidateRange(1,100)][int]$ExpectedEventCount = 5,
    [ValidateRange(1,100)][int]$ExpectedThreshold = 5,
    [switch]$IncludeAi,
    [ValidateRange(5,600)][int]$WaitSeconds = 90,
    [ValidateRange(1,120)][int]$HttpTimeoutSeconds = 30,
    [ValidateRange(10,900)][int]$AiTimeoutSeconds = 300,
    [ValidateRange(100,1000000)][int]$KafkaMaxMessages = 100000,
    [ValidateRange(1,120)][int]$KafkaTimeoutSeconds = 15
)

function Assert-SmokeSubmission {
    param($Submission)
    if ($null -eq $Submission -or $Submission.scenario -cne 'DATABASE_FAILURE' -or
        [string]::IsNullOrWhiteSpace($Submission.traceId) -or
        $Submission.eventCount -isnot [int] -or $Submission.eventCount -lt 1 -or
        @($Submission.eventIds).Count -ne $Submission.eventCount) {
        throw 'Simulator acknowledgement is empty or violates SimulationResponse.'
    }
    $ids = @($Submission.eventIds)
    foreach ($id in $ids) {
        if ($id -isnot [string] -or $id -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
            throw 'Simulator acknowledgement contains a noncanonical event UUID.'
        }
    }
    if (@($ids | Select-Object -Unique).Count -ne $ids.Count) {
        throw 'Simulator acknowledgement contains duplicate event IDs.'
    }
}

function Assert-SmokeHistory {
    param($History, [string[]]$EventIds, [string]$ServiceName, [string]$TraceId)
    $rows = @($History.events)
    if ($History.serviceName -cne $ServiceName -or $History.page -ne 0 -or
        $History.totalElements -ne $EventIds.Count -or $History.totalPages -ne 1 -or
        $rows.Count -ne $EventIds.Count) {
        throw 'REST event history does not contain the complete isolated run.'
    }
    $actualIds = @($rows | ForEach-Object { $_.eventId })
    if (@($actualIds | Select-Object -Unique).Count -ne $EventIds.Count -or
        @(Compare-Object $EventIds $actualIds -CaseSensitive).Count -ne 0) {
        throw 'REST event history IDs differ from simulator acknowledgements.'
    }
    foreach ($row in $rows) {
        if ($row.serviceName -cne $ServiceName -or $row.traceId -cne $TraceId -or
            $row.eventType -cne 'DATABASE_ERROR' -or $row.severity -cne 'ERROR' -or
            [string]::IsNullOrWhiteSpace($row.timestamp) -or [string]::IsNullOrWhiteSpace($row.processedAt)) {
            throw 'Persisted event service, trace, type, severity or timestamps do not match the run.'
        }
        try {
            $null = [DateTimeOffset]::Parse($row.timestamp)
            $null = [DateTimeOffset]::Parse($row.processedAt)
        } catch { throw 'Persisted event timestamps are invalid.' }
    }
}

function Assert-SmokeKafka {
    param([string[]]$Lines, [object[]]$PersistedEvents)
    $found = @{}
    foreach ($line in $Lines) {
        if (-not $line.TrimStart().StartsWith('{')) { continue }
        try { $event = $line | ConvertFrom-Json -ErrorAction Stop }
        catch { throw 'Kafka returned malformed JSON; records cannot be verified.' }
        $expected = @($PersistedEvents | Where-Object { $_.eventId -ceq $event.eventId })
        if ($expected.Count -eq 0) { continue }
        foreach ($field in @('eventId','serviceName','traceId','eventType','severity','message')) {
            if ($event.$field -cne $expected[0].$field) { throw 'Kafka event fields differ from persisted REST history.' }
        }
        try {
            # EventProcessingService truncates Kafka Instant nanoseconds to MICROS.
            # Truncate BEFORE .NET parsing: DateTimeOffset rounds excess precision.
            $storedWireTime = [regex]::Replace($event.timestamp, '(\.\d{6})\d+(?=Z$|[+-]\d{2}:\d{2}$)', '$1')
            if ([DateTimeOffset]::Parse($storedWireTime) -ne [DateTimeOffset]::Parse($expected[0].timestamp)) {
                throw 'timestamp mismatch'
            }
        } catch { throw 'Kafka event timestamp differs from persisted REST history.' }
        $found[$event.eventId] = $true
    }
    if ($found.Count -ne $PersistedEvents.Count) {
        throw 'Kafka scan did not contain every acknowledged ID; timeout or message cap may have limited history.'
    }
}

function Assert-SmokeAnalysis {
    param($Analysis, $Incident, [object[]]$PersistedEvents, [string]$TraceId)
    # AnalysisResponse has captured evidence, NOT the model's internal draft evidenceIds.
    if ($null -eq $Analysis -or $Analysis.incidentId -cne $Incident.id -or
        $Analysis.probableCause -isnot [string] -or [string]::IsNullOrWhiteSpace($Analysis.probableCause) -or
        @('LOW','MEDIUM','HIGH') -cnotcontains $Analysis.confidence -or
        $Analysis.recommendedActions -isnot [array] -or $Analysis.recommendedActions.Count -eq 0 -or
        $Analysis.limitations -isnot [array] -or $Analysis.evidence -isnot [array] -or $Analysis.evidence.Count -eq 0) {
        throw 'AI analysis is empty or violates AnalysisResponse.'
    }
    foreach ($text in @($Analysis.recommendedActions) + @($Analysis.limitations)) {
        if ($text -isnot [string] -or [string]::IsNullOrWhiteSpace($text)) { throw 'AI analysis has an invalid text list.' }
    }
    $seen = @{}
    $tools = @{}
    $allowed = @('get_incident','get_related_events','get_service_health','get_recent_errors','get_error_frequency')
    foreach ($evidence in $Analysis.evidence) {
        if ($evidence.id -isnot [string] -or $evidence.id -cnotmatch '^E[1-9][0-9]*$' -or
            $seen.ContainsKey($evidence.id) -or $allowed -cnotcontains $evidence.toolName -or
            $null -eq $evidence.data) { throw 'AI evidence has an invalid ID, duplicate ID, unknown tool or missing data.' }
        $seen[$evidence.id] = $true
        $tools[$evidence.toolName] = $true
        $data = $evidence.data
        switch -CaseSensitive ($evidence.toolName) {
            'get_incident' {
                if ($data.id -cne $Incident.id -or $data.rootService -cne $Incident.rootService -or
                    $data.errorCount -ne $Incident.errorCount) { throw 'AI incident evidence references a different incident.' }
            }
            'get_related_events' {
                if ($data.incidentId -cne $Incident.id -or $data.rootService -cne $Incident.rootService -or
                    $data.relationshipType -cne 'ROOT_SERVICE_TIME_WINDOW' -or @($data.events).Count -eq 0) {
                    throw 'AI related-events evidence has an invalid incident relationship.'
                }
            }
            default {
                if ($data.serviceName -cne $Incident.rootService) { throw 'AI service evidence references a different service.' }
            }
        }
        if (@('get_related_events','get_recent_errors') -ccontains $evidence.toolName) {
            foreach ($event in $data.events) {
                $matched = @($PersistedEvents | Where-Object { $_.eventId -ceq $event.eventId })
                if ($matched.Count -ne 1 -or $event.serviceName -cne $Incident.rootService -or $event.traceId -cne $TraceId) {
                    throw 'AI event evidence references an event outside this isolated run.'
                }
                foreach ($field in @('eventType','severity','message','timestamp','processedAt')) {
                    if ($matched[0].PSObject.Properties.Name -contains $field -and $event.$field -cne $matched[0].$field) {
                        throw 'AI event evidence differs from REST history.'
                    }
                }
            }
        }
    }
    foreach ($required in @('get_incident','get_related_events','get_service_health')) {
        if (-not $tools.ContainsKey($required)) { throw 'AI analysis lacks mandatory captured diagnostics.' }
    }
    # Structural checks and reference equality are not a semantic guarantee about model prose.
}

function Invoke-SmokeHttp {
    param([ValidateSet('GET','POST')][string]$Method, [string]$Path, [int]$TimeoutSeconds)
    try {
        # No retry policy: especially never repeat simulation or paid analysis POSTs.
        $result = Invoke-RestMethod -Uri ($Gateway.TrimEnd('/') + $Path) -Method $Method `
            -TimeoutSec $TimeoutSeconds -ErrorAction Stop
    } catch {
        # Do not leak provider bodies, exception URLs or credential-bearing error details.
        $status = 'unavailable'
        if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
            $status = [string][int]$_.Exception.Response.StatusCode
        }
        throw "HTTP $Method failed (status=$status; timeout/transport errors are possible). Writes were not retried."
    }
    if ($null -eq $result) { throw "HTTP $Method returned an empty response." }
    return ,$result
}

function Invoke-SmokeProcess {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory, [int]$TimeoutSeconds)
    $quoted = foreach ($argument in $ArgumentList) {
        # CommandLineToArgvW-compatible quoting, including spaces and trailing slashes.
        $encoded = [regex]::Replace($argument, '(\\*)"', '$1$1\"')
        $encoded = [regex]::Replace($encoded, '(\\+)$', '$1$1')
        '"' + $encoded + '"'
    }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = $quoted -join ' '
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        $null = $process.Start()
        # Drain both pipes concurrently, so a large Kafka history cannot deadlock.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw 'Native command exceeded its bounded timeout.'
        }
        $process.WaitForExit()
        return [pscustomobject]@{ExitCode=$process.ExitCode;Stdout=$stdout.Result;Stderr=$stderr.Result}
    } catch {
        # Native stderr can contain Compose interpolation or infrastructure details.
        throw 'Native command failed to start or exceeded its bounded timeout; output suppressed.'
    } finally { $process.Dispose() }
}

function Assert-SmokeIncident {
    param($Incident, [object[]]$PersistedEvents, [string]$ServiceName, [string]$TraceId, [int]$Threshold = 5)
    if ($null -eq $Incident -or $Incident.id -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
        $Incident.rootService -cne $ServiceName -or $Incident.status -cne 'OPEN' -or
        $Incident.errorCount -ne $Threshold -or $Threshold -gt $PersistedEvents.Count) {
        throw 'Incident identity, rootService, status or threshold/errorCount does not match this run.'
    }
    foreach ($event in $PersistedEvents) {
        if ($event.serviceName -cne $ServiceName -or $event.traceId -cne $TraceId) {
            throw 'Incident service does not match the persisted run service/trace.'
        }
    }
    try {
        $created = [DateTimeOffset]::Parse($Incident.createdAt)
        $first = @($PersistedEvents | ForEach-Object { [DateTimeOffset]::Parse($_.processedAt) } | Sort-Object)[0]
        if ($created -lt $first -or $created -gt [DateTimeOffset]::UtcNow.AddSeconds(5)) { throw 'time mismatch' }
    } catch { throw 'Incident creation time is invalid, predates the run, or is in the future.' }
    # Baseline IncidentResponse exposes no event FK or traceId. Do not invent one.
}

function Invoke-SmokeCompose {
    param([string[]]$Command, [int]$TimeoutSeconds, [switch]$KafkaRead)
    $result = Invoke-SmokeProcess $DockerExe ($ComposeArguments + $Command) $RepositoryRoot $TimeoutSeconds
    if ($result.ExitCode -ne 0) {
        # Kafka console-consumer exits 1 on its explicit idle timeout. The caller MUST
        # still prove all required event IDs exist in the captured stdout.
        $idleTimeout = $KafkaRead -and $result.ExitCode -eq 1 -and
            $result.Stderr -match 'org\.apache\.kafka\.common\.errors\.TimeoutException'
        if (-not $idleTimeout) { throw "Compose command failed (exit=$($result.ExitCode)); output suppressed." }
    }
    return $result
}

function Invoke-SmokeRun {
    # DATABASE_FAILURE is deliberately fixed to order-service in SimulationService.
    # Isolation is a fresh Compose project's network/volumes plus simulator-generated trace.
    $serviceName = 'order-service'
    $RepositoryRoot = Split-Path -Parent $PSScriptRoot
    $origin = $null
    if (-not [Uri]::TryCreate($Gateway, [UriKind]::Absolute, [ref]$origin) -or
        @('http','https') -notcontains $origin.Scheme -or $origin.UserInfo -ne '' -or
        $origin.Query -ne '' -or $origin.Fragment -ne '' -or $origin.AbsolutePath -ne '/') {
        throw 'Gateway must be an HTTP(S) origin without credentials, query, fragment or path.'
    }
    if ($ProjectName -and $ProjectName -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Invalid Compose project name.' }
    if ($KafkaTopic -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Invalid Kafka topic name.' }
    if ($ExpectedThreshold -gt $ExpectedEventCount) { throw 'ExpectedEventCount must reach ExpectedThreshold.' }
    $DockerExe = (Get-Command 'docker.exe' -ErrorAction Stop).Source
    $composeFile = Join-Path $RepositoryRoot 'docker-compose.yml'
    $ComposeArguments = @('compose','--project-directory',$RepositoryRoot,'--file',$composeFile)
    if ($ProjectName) { $ComposeArguments += @('--project-name',$ProjectName) }
    else { Write-Warning 'No explicit project selected. Use a fresh isolated Compose project; fixed-service history will cause a safe pre-write failure.' }
    foreach ($file in $EnvFile) {
        $path = $file
        if (-not [IO.Path]::IsPathRooted($path)) { $path = Join-Path $RepositoryRoot $path }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'An explicit Compose env file is missing; contents were not read.' }
        $ComposeArguments += @('--env-file',$path)
    }
    $null = Invoke-SmokeCompose @('version','--short') 15
    # Bind HTTP verification to this exact Compose project, not a native/shared gateway
    # accidentally listening on the default port. Current compose publishes loopback HTTP.
    $published = Invoke-SmokeCompose @('port','api-gateway','8081') 15
    $binding = $published.Stdout.Trim()
    if ($binding -cnotmatch '^127\.0\.0\.1:([0-9]+)$' -or
        $origin.Scheme -cne 'http' -or @('127.0.0.1','localhost') -cnotcontains $origin.Host -or
        $origin.Port -ne [int]($binding.Substring($binding.LastIndexOf(':') + 1))) {
        throw 'Gateway does not match this Compose project published api-gateway port. Supply the isolated project and its direct loopback HTTP gateway.'
    }
    $health = Invoke-SmokeHttp 'GET' '/actuator/health' $HttpTimeoutSeconds
    if ($health.status -cne 'UP') { throw 'Gateway health is not UP.' }
    $before = Invoke-SmokeHttp 'GET' '/api/incidents' $HttpTimeoutSeconds
    if ($before -isnot [array]) { throw 'Incident list response is not a JSON array.' }
    # Reject resolved incidents too: this smoke proves a previously absent service incident.
    if (@($before | Where-Object { $_.rootService -ceq $serviceName }).Count -ne 0) {
        throw 'order-service already has an incident. Use a fresh isolated Compose project and its gateway; no shared incident will be changed.'
    }
    $beforeIds = @($before | ForEach-Object { $_.id })
    $historyPath = '/api/services/' + $serviceName + '/events?page=0&size=100'
    # Unknown services legitimately return 404 from /{service}/events. Use the
    # known-service list for an absence preflight, not a fabricated empty history.
    $knownServices = Invoke-SmokeHttp 'GET' '/api/services' $HttpTimeoutSeconds
    if ($knownServices -isnot [array]) { throw 'Known-service list is not a JSON array.' }
    if (@($knownServices | Where-Object { $_.serviceName -ceq $serviceName }).Count -ne 0) {
        throw 'order-service already has event history. Use a fresh isolated Compose project; records will not be deleted.'
    }
    $ping = Invoke-SmokeCompose @('exec','-T','redis','redis-cli','--raw','PING') 10
    if ($ping.Stdout.Trim() -cne 'PONG') { throw 'Redis PING did not return PONG.' }
    $redisKey = 'errors:{' + $serviceName + '}'
    $priorRedis = Invoke-SmokeCompose @('exec','-T','redis','redis-cli','--raw','ZCARD',$redisKey) 10
    if ($priorRedis.Stdout.Trim() -cne '0') {
        throw 'order-service already has Redis correlation members. Use a fresh isolated Compose project; no keys will be reset.'
    }
    $null = Invoke-SmokeCompose @('exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--describe','--topic',$KafkaTopic) 20
    $started = [DateTimeOffset]::UtcNow
    # Exactly one simulation write. An uncertain timeout must be investigated, never retried.
    $submission = Invoke-SmokeHttp 'POST' '/simulate/database-failure' $HttpTimeoutSeconds
    Assert-SmokeSubmission $submission
    if ($submission.eventCount -ne $ExpectedEventCount) { throw 'Simulator accepted count differs from ExpectedEventCount; writes were not retried.' }
    if ($submission.traceId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw 'Simulator did not return its expected unique UUID trace.'
    }
    $eventIds = [string[]]@($submission.eventIds)
    # Print only audit handles, never event messages, analysis prose or provider details.
    Write-Host ('Accepted scenario=DATABASE_FAILURE service=order-service traceId={0} eventCount={1} eventIds={2}' -f $submission.traceId,$eventIds.Count,($eventIds -join ','))
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds)
    $history = $null
    $incident = $null
    do {
        $remaining = [Math]::Max(1,[int][Math]::Ceiling(($deadline - [DateTimeOffset]::UtcNow).TotalSeconds))
        $readTimeout = [Math]::Min($HttpTimeoutSeconds,$remaining)
        $incidents = Invoke-SmokeHttp 'GET' '/api/incidents' $readTimeout
        if ($incidents -isnot [array]) { throw 'Incident list response is not a JSON array.' }
        $matches = @($incidents | Where-Object { $_.rootService -ceq $serviceName })
        if ($matches.Count -gt 1) { throw 'Multiple order-service incidents appeared; run isolation cannot be established.' }
        # Before consumption, history legitimately returns unknown-service 404.
        # A correlated incident becomes visible only after persisted events exist.
        if ($matches.Count -eq 1) {
            $history = Invoke-SmokeHttp 'GET' $historyPath $readTimeout
            if (@($history.events).Count -ge $ExpectedEventCount) {
                $incident = $matches[0]
                break
            }
        }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($null -eq $incident) { throw 'Timed out waiting for a new incident and every accepted event in REST history.' }
    Assert-SmokeHistory $history $eventIds $serviceName $submission.traceId
    if ($beforeIds -ccontains $incident.id) { throw 'Incident UUID existed before this run.' }
    # Read back the exact created incident target before claiming persistence.
    $incidentId = $incident.id
    $incident = Invoke-SmokeHttp 'GET' ('/api/incidents/' + $incidentId) $HttpTimeoutSeconds
    if ($incident.id -cne $incidentId) { throw 'Incident detail UUID differs from the newly listed incident.' }
    Assert-SmokeIncident $incident @($history.events) $serviceName $submission.traceId $ExpectedThreshold
    if ([DateTimeOffset]::Parse($incident.createdAt) -lt $started.AddSeconds(-5)) {
        throw 'Incident creation time predates this simulation request.'
    }
    # Observe short-lived Redis evidence BEFORE the Kafka scan (correlation window defaults to 60s).
    $redisDeadline = [DateTimeOffset]::UtcNow.AddSeconds([Math]::Min($WaitSeconds,20))
    $redisIds = @()
    do {
        $redis = Invoke-SmokeCompose @('exec','-T','redis','redis-cli','--raw','ZRANGE',$redisKey,'0','-1') 10
        $redisIds = @($redis.Stdout -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($redisIds.Count -eq $eventIds.Count -and @(Compare-Object $eventIds $redisIds -CaseSensitive).Count -eq 0) { break }
        if ([DateTimeOffset]::UtcNow -ge $redisDeadline) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $redisDeadline)
    if ($redisIds.Count -ne $eventIds.Count -or @(Compare-Object $eventIds $redisIds -CaseSensitive).Count -ne 0) {
        throw 'Redis correlation sorted set did not contain exactly the acknowledged event IDs within the polling window; no reset attempted.'
    }
    $group = 'smoke-' + [Guid]::NewGuid().ToString('N')
    # Dedicated read-only diagnostic group; never borrow the application's consumer group.
    # Disable offset commits. Broker-side consumer timeout AND record cap bound remote work.
    $kafkaCommand = @('exec','-T','kafka','/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server','kafka:19092','--topic',$KafkaTopic,'--group',$group,'--from-beginning',
        '--consumer-property','enable.auto.commit=false','--timeout-ms',([string]($KafkaTimeoutSeconds * 1000)),
        '--max-messages',([string]$KafkaMaxMessages))
    $kafka = Invoke-SmokeCompose $kafkaCommand ($KafkaTimeoutSeconds + 30) -KafkaRead
    Assert-SmokeKafka @($kafka.Stdout -split '\r?\n') @($history.events)
    $analysisStatus = 'not requested (-IncludeAi required)'
    $evidenceHandles = @()
    if ($IncludeAi) {
        Write-Host ('Requesting ONE analysis for incidentId={0}; no automatic POST retry.' -f $incident.id)
        $analysis = Invoke-SmokeHttp 'POST' ('/api/analysis/incidents/' + $incident.id) $AiTimeoutSeconds
        Assert-SmokeAnalysis $analysis $incident @($history.events) $submission.traceId
        $analysisStatus = 'validated schema and evidence references (not semantic correctness)'
        $evidenceHandles = @($analysis.evidence | ForEach-Object { [pscustomobject]@{id=$_.id;toolName=$_.toolName} })
    }
    $lastEvent = @($history.events | Sort-Object { [DateTimeOffset]::Parse($_.processedAt) } -Descending)[0]
    return [pscustomobject][ordered]@{
        serviceName=$serviceName;traceId=$submission.traceId;incidentId=$incident.id
        acceptedCount=$eventIds.Count;persistedCount=@($history.events).Count
        kafkaMatchedCount=$eventIds.Count;redisMatchedCount=$redisIds.Count
        eventIds=$eventIds;lastPersistedEventId=$lastEvent.eventId;kafkaConsumerGroup=$group
        incidentStatus=$incident.status;incidentErrorCount=$incident.errorCount
        incidentLinkage='rootService and run creation time; baseline incidents expose no direct event FK or traceId'
        analysis=$analysisStatus;evidenceHandles=$evidenceHandles
        retainedData='Synthetic events and incident retained for audit; no cleanup or remediation performed'
    }
}

if ($MyInvocation.InvocationName -eq '.') { return }
$ErrorActionPreference = 'Stop'
try {
    Invoke-SmokeRun | ConvertTo-Json -Depth 6
} catch {
    # All adapter/contract failures above are sanitized. Do not dump error records or bodies.
    [Console]::Error.WriteLine('Docker smoke failed: ' + $_.Exception.Message)
    exit 1
}
