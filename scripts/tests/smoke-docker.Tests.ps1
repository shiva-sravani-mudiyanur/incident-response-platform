# Offline contracts only: no Docker, HTTP or provider calls.
$scriptPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'smoke-docker.ps1'
. $scriptPath
Describe 'Docker smoke acceptance contract' {
    It 'provides a side-effect-free dot-source entry point and rejects empty acknowledgements' {
        Test-Path $scriptPath | Should Be $true
        { Assert-SmokeSubmission ([pscustomobject]@{scenario='DATABASE_FAILURE';traceId='trace';eventCount=0;eventIds=@()}) } | Should Throw
    }
    It 'accepts actual SimulationResponse and rejects duplicate IDs or count mismatch' {
        $id = '11111111-1111-4111-8111-111111111111'
        $submission = [pscustomobject]@{scenario='DATABASE_FAILURE';traceId='trace';eventCount=1;eventIds=@($id)}
        { Assert-SmokeSubmission $submission } | Should Not Throw
        $submission.eventCount = 2
        { Assert-SmokeSubmission $submission } | Should Throw
        $submission.eventIds = @($id,$id)
        { Assert-SmokeSubmission $submission } | Should Throw
    }
    It 'captures native output, preserves spaces and bounds process duration' {
        @(Get-Command Invoke-SmokeProcess -ErrorAction SilentlyContinue).Count | Should Be 1
        $native = Join-Path $PSHOME 'powershell.exe'
        $result = Invoke-SmokeProcess $native @('-NoProfile','-NonInteractive','-Command','[Console]::Out.Write("hello world"); [Console]::Error.Write("private diagnostic"); exit 7') $PSScriptRoot 10
        $result.ExitCode | Should Be 7
        $result.Stdout | Should Be 'hello world'
        $result.Stderr | Should Be 'private diagnostic'
        { Invoke-SmokeProcess $native @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 10') $PSScriptRoot 1 } | Should Throw
    }
    It 'runs Compose from repository root and never exposes failed native output' {
        @(Get-Command Invoke-SmokeCompose -ErrorAction SilentlyContinue).Count | Should Be 1
        $DockerExe = 'docker.exe'
        $RepositoryRoot = 'C:\repo with spaces'
        $ComposeArguments = @('compose','--project-directory',$RepositoryRoot,'--project-name','isolated','--env-file','C:\private env\.env')
        Mock Invoke-SmokeProcess { [pscustomobject]@{ExitCode=0;Stdout='a';Stderr=''} }
        (Invoke-SmokeCompose @('exec','-T','redis','redis-cli','--raw','PING') 3).Stdout | Should Be 'a'
        Assert-MockCalled -Scope It Invoke-SmokeProcess -Times 1 -Exactly -ParameterFilter {
            $WorkingDirectory -eq 'C:\repo with spaces' -and $ArgumentList[0] -eq 'compose' -and $ArgumentList -contains '--env-file'
        }
        Mock Invoke-SmokeProcess { [pscustomobject]@{ExitCode=2;Stdout='SECRET';Stderr='SECRET'} }
        try { Invoke-SmokeCompose @('ps') 3; throw 'expected failure' }
        catch { $_.Exception.Message | Should Match 'Compose command failed'; $_.Exception.Message | Should Not Match 'SECRET' }
    }
    It 'validates baseline incident correlation without inventing an event foreign key' {
        $id = '11111111-1111-4111-8111-111111111111'
        $incident = [pscustomobject]@{id=$id;rootService='order-service';status='OPEN';errorCount=1;createdAt='2026-01-01T00:00:02Z'}
        $event = [pscustomobject]@{eventId=$id;serviceName='order-service';traceId='trace';processedAt='2026-01-01T00:00:01Z'}
        { Assert-SmokeIncident $incident @($event) 'order-service' 'trace' 1 } | Should Not Throw
        $incident.errorCount = 2
        { Assert-SmokeIncident $incident @($event) 'order-service' 'trace' 1 } | Should Throw
        $incident.errorCount = 1
        $incident.createdAt = '2025-01-01T00:00:00Z'
        { Assert-SmokeIncident $incident @($event) 'order-service' 'trace' 1 } | Should Throw
    }
    It 'makes one HTTP attempt and sanitizes failures without exposing bodies' {
        @(Get-Command Invoke-SmokeHttp -ErrorAction SilentlyContinue).Count | Should Be 1
        Mock Invoke-RestMethod { throw 'SECRET_PROVIDER_BODY' }
        try { Invoke-SmokeHttp 'POST' '/api/analysis/incidents/11111111-1111-4111-8111-111111111111' 1; throw 'Expected failure' }
        catch { $_.Exception.Message | Should Not Match 'SECRET_PROVIDER_BODY'; $_.Exception.Message | Should Match 'HTTP POST failed' }
        Assert-MockCalled -Scope It Invoke-RestMethod -Exactly -Times 1
    }
    It 'preserves an empty JSON list response for bounded preflight and polling' {
        Mock Invoke-RestMethod { return ,@() }
        $response = Invoke-SmokeHttp 'GET' '/api/incidents' 1
        ($response -is [array]) | Should Be $true
        $response.Count | Should Be 0
    }
    It 'validates captured analysis tool names and incident/service/event references' {
        @(Get-Command Assert-SmokeAnalysis -ErrorAction SilentlyContinue).Count | Should Be 1
        $id = '11111111-1111-4111-8111-111111111111'
        $incident = [pscustomobject]@{id=$id;rootService='smoke-test';errorCount=1}
        $event = [pscustomobject]@{eventId=$id;serviceName='smoke-test';traceId='trace'}
        $analysis = [pscustomobject]@{
            incidentId=$id;probableCause='A hypothesis';confidence='LOW';recommendedActions=@('Inspect database');limitations=@()
            evidence=@(
                [pscustomobject]@{id='E1';toolName='get_incident';data=$incident},
                [pscustomobject]@{id='E2';toolName='get_related_events';data=[pscustomobject]@{incidentId=$id;rootService='smoke-test';relationshipType='ROOT_SERVICE_TIME_WINDOW';events=@($event)}},
                [pscustomobject]@{id='E3';toolName='get_service_health';data=[pscustomobject]@{serviceName='smoke-test';errorsLastMinute=1;activeIncidentCount=1}}
            )
        }
        { Assert-SmokeAnalysis $analysis $incident @($event) 'trace' } | Should Not Throw
        $analysis.evidence[0].toolName = 'invented_tool'
        { Assert-SmokeAnalysis $analysis $incident @($event) 'trace' } | Should Throw
        $analysis.evidence[0].toolName = 'get_incident'
        $analysis.evidence[1].data.events[0] = [pscustomobject]@{eventId='not-known';serviceName='smoke-test';traceId='trace'}
        { Assert-SmokeAnalysis $analysis $incident @($event) 'trace' } | Should Throw
        $analysis.evidence[1].data.events = @($event)
        $analysis.incidentId = 'wrong'
        { Assert-SmokeAnalysis $analysis $incident @($event) 'trace' } | Should Throw
    }
    It 'parses Kafka JSON records and rejects absent IDs or mismatched trace' {
        @(Get-Command Assert-SmokeKafka -ErrorAction SilentlyContinue).Count | Should Be 1
        $event = [pscustomobject]@{eventId='11111111-1111-4111-8111-111111111111';serviceName='smoke-test';traceId='trace';eventType='DATABASE_ERROR';severity='ERROR';message='synthetic';timestamp='2026-01-01T00:00:00Z'}
        $lines = @('Processed a total of 1 messages', ($event | ConvertTo-Json -Compress))
        { Assert-SmokeKafka $lines @($event) } | Should Not Throw
        { Assert-SmokeKafka @('Processed a total of 0 messages') @($event) } | Should Throw
        $other = $event | ConvertTo-Json -Compress | ConvertFrom-Json
        $other.traceId = 'wrong'
        { Assert-SmokeKafka @(($other | ConvertTo-Json -Compress)) @($event) } | Should Throw
    }
    It 'compares Kafka nanoseconds at the actual PostgreSQL microsecond truncation precision' {
        $persisted = [pscustomobject]@{eventId='11111111-1111-4111-8111-111111111111';serviceName='order-service';traceId='trace';eventType='DATABASE_ERROR';severity='ERROR';message='synthetic';timestamp='2026-01-01T00:00:00.123456Z'}
        $wire = $persisted | ConvertTo-Json -Compress | ConvertFrom-Json
        $wire.timestamp = '2026-01-01T00:00:00.123456999Z'
        { Assert-SmokeKafka @(($wire | ConvertTo-Json -Compress)) @($persisted) } | Should Not Throw
        $wire.timestamp = '2026-01-01T00:00:00.123457000Z'
        { Assert-SmokeKafka @(($wire | ConvertTo-Json -Compress)) @($persisted) } | Should Throw
    }
    It 'compares persisted event identities, service and trace, not merely counts' {
        @(Get-Command Assert-SmokeHistory -ErrorAction SilentlyContinue).Count | Should Be 1
        $id = '11111111-1111-4111-8111-111111111111'
        $row = [pscustomobject]@{eventId=$id;serviceName='smoke-test';traceId='trace';eventType='DATABASE_ERROR';severity='ERROR';timestamp='2026-01-01T00:00:00Z';processedAt='2026-01-01T00:00:01Z'}
        $history = [pscustomobject]@{serviceName='smoke-test';events=@($row);page=0;totalElements=1;totalPages=1}
        { Assert-SmokeHistory $history @($id) 'smoke-test' 'trace' } | Should Not Throw
        $row.traceId = 'other'
        { Assert-SmokeHistory $history @($id) 'smoke-test' 'trace' } | Should Throw
        $row.traceId = 'trace'
        $row.eventId = '22222222-2222-4222-8222-222222222222'
        { Assert-SmokeHistory $history @($id) 'smoke-test' 'trace' } | Should Throw
    }
}
Describe 'Docker smoke orchestration (offline adapters)' {
    BeforeEach {
        $Gateway = 'http://127.0.0.1:8081'
        $ProjectName = 'offline-contract-test'
        $IncludeAi = $false
        $ExpectedEventCount = 1
        $ExpectedThreshold = 1
        $global:smokeEvent = $null
        $global:smokeIncident = $null
        $global:smokeUnrelated = $null
        $global:smokeNoIncident = $false
        $global:smokeNoRedis = $false
        $global:smokeComposeArguments = @()
        $global:smokeGatewayBinding = '127.0.0.1:8081'
        # Fail closed if an adapter mock ever escapes; offline tests must never reach a real stack.
        Mock Invoke-RestMethod { throw 'Offline test guard: unexpected real HTTP adapter call' }
        Mock Invoke-SmokeProcess { throw 'Offline test guard: unexpected real Docker process call' }
        Mock Get-Command { [pscustomobject]@{Source='docker.exe'} } -ParameterFilter { $Name -eq 'docker.exe' }
        Mock Invoke-SmokeHttp {
            param($Method,$Path,$TimeoutSeconds)
            if ($Path -eq '/simulate/database-failure') {
                # REST persistence uses microseconds, not .NET's seven tick digits.
                $time = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", [Globalization.CultureInfo]::InvariantCulture)
                $global:smokeEvent = [pscustomobject]@{
                    eventId='11111111-1111-4111-8111-111111111111';serviceName='order-service';traceId='33333333-3333-4333-8333-333333333333'
                    eventType='DATABASE_ERROR';severity='ERROR';message='synthetic';timestamp=$time;processedAt=$time
                }
                $global:smokeIncident = [pscustomobject]@{
                    id='22222222-2222-4222-8222-222222222222';rootService='order-service';errorCount=1;status='OPEN'
                    createdAt=$time
                }
                return [pscustomobject]@{scenario='DATABASE_FAILURE';traceId=$global:smokeEvent.traceId;eventCount=1;eventIds=@($global:smokeEvent.eventId)}
            }
            if ($Path -eq '/actuator/health') { return [pscustomobject]@{status='UP'} }
            if ($Path -eq '/api/services') { return ,@() }
            if ($Path -eq '/api/incidents') {
                if ($global:smokeNoIncident) { return ,@() }
                $items = @()
                if ($null -ne $global:smokeIncident) { $items += $global:smokeIncident }
                if ($null -ne $global:smokeUnrelated) { $items += $global:smokeUnrelated }
                return ,$items
            }
            if ($Path -like '/api/incidents/*') { return $global:smokeIncident }
            if ($Path -like '/api/services/*/health') {
                return [pscustomobject]@{serviceName='order-service';status='DOWN';errorsLastMinute=1;activeIncidentCount=1}
            }
            if ($Path -like '/api/services/*') {
                if ($null -eq $global:smokeEvent) { return [pscustomobject]@{serviceName='order-service';events=@();page=0;totalElements=0;totalPages=0} }
                return [pscustomobject]@{serviceName='order-service';events=@($global:smokeEvent);page=0;totalElements=1;totalPages=1}
            }
            if ($Path -like '/api/analysis/*') {
                return [pscustomobject]@{
                    incidentId=$global:smokeIncident.id;probableCause='Hypothesis';confidence='LOW';recommendedActions=@('Inspect');limitations=@()
                    evidence=@(
                        [pscustomobject]@{id='E1';toolName='get_incident';data=$global:smokeIncident},
                        [pscustomobject]@{id='E2';toolName='get_related_events';data=[pscustomobject]@{incidentId=$global:smokeIncident.id;rootService='order-service';relationshipType='ROOT_SERVICE_TIME_WINDOW';events=@($global:smokeEvent)}},
                        [pscustomobject]@{id='E3';toolName='get_service_health';data=[pscustomobject]@{serviceName='order-service';errorsLastMinute=1;activeIncidentCount=1}}
                    )
                }
            }
            throw 'Unexpected HTTP adapter call'
        }
        # Pester 3 retains parameter-filtered mocks across a Describe block.
        Mock Invoke-SmokeHttp {
            [pscustomobject]@{serviceName='order-service';status='DOWN';errorsLastMinute=1;activeIncidentCount=1}
        } -ParameterFilter { $Path -eq '/api/services/order-service/health' }
        Mock Invoke-SmokeCompose {
            param($Command,$TimeoutSeconds,$KafkaRead)
            $global:smokeComposeArguments = $ComposeArguments
            $output = ''
            if ($Command[0] -eq 'port') { $output = $global:smokeGatewayBinding }
            if ($Command -contains 'ZRANGE' -and -not $global:smokeNoRedis) { $output = $global:smokeEvent.eventId }
            if ($Command -contains 'ZCARD') { $output = '0' }
            if ($Command -contains 'PING') { $output = 'PONG' }
            if ($Command -contains '/opt/kafka/bin/kafka-console-consumer.sh') { $output = $global:smokeEvent | ConvertTo-Json -Compress }
            return [pscustomobject]@{ExitCode=0;Stdout=$output;Stderr=''}
        }
    }
    It 'rejects a gateway belonging to a different published project before any HTTP write' {
        $global:smokeGatewayBinding = '127.0.0.1:8181'
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Method -eq 'POST' }
    }
    It 'fails before writes if an active incident already exists for the fixed service' {
        @(Get-Command Invoke-SmokeRun -ErrorAction SilentlyContinue).Count | Should Be 1
        $global:smokeIncident = [pscustomobject]@{id='22222222-2222-4222-8222-222222222222';rootService='order-service';status='OPEN'}
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Method -eq 'POST' }
    }
    It 'handles unknown-service history returning 404 before the first simulation' {
        Mock Invoke-SmokeHttp { throw 'HTTP GET failed (status=404)' } -ParameterFilter {
            $Method -eq 'GET' -and $Path -like '/api/services/*/events*' -and $null -eq $global:smokeEvent
        }
        { Invoke-SmokeRun } | Should Not Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 1 -Exactly -ParameterFilter {
            $Method -eq 'GET' -and $Path -eq '/api/services'
        }
    }
    It 'checks all transport identities and makes no paid call by default' {
        @(Get-Command Invoke-SmokeRun -ErrorAction SilentlyContinue).Count | Should Be 1
        $summary = Invoke-SmokeRun
        $summary.acceptedCount | Should Be 1
        $summary.kafkaMatchedCount | Should Be 1
        $summary.redisMatchedCount | Should Be 1
        $summary.analysis | Should Be 'not requested (-IncludeAi required)'
        $summary.serviceName | Should Be 'order-service'
        $summary.serviceHealth.status | Should Be 'DOWN'
        $summary.serviceHealth.errorsLastMinute | Should Be 1
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 1 -Exactly -ParameterFilter { $Method -eq 'POST' -and $Path -eq '/simulate/database-failure' }
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Path -like '/api/analysis/*' }
        Assert-MockCalled -Scope It Invoke-SmokeCompose -Times 1 -Exactly -ParameterFilter {
            $Command -contains '/opt/kafka/bin/kafka-console-consumer.sh' -and
            $Command -contains '--from-beginning' -and $Command -contains '--max-messages' -and
            $Command -contains '--timeout-ms' -and ($Command -join ' ') -match '--group smoke-[0-9a-f]{32}'
        }
    }
    It 'rejects a healthy domain response before any opted-in analysis' {
        $IncludeAi = $true
        Mock Invoke-SmokeHttp {
            [pscustomobject]@{serviceName='order-service';status='HEALTHY';errorsLastMinute=1;activeIncidentCount=1}
        } -ParameterFilter { $Path -eq '/api/services/order-service/health' }
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Path -like '/api/analysis/*' }
    }
    It 'passes multiple explicit env files to Compose in order without reading contents' {
        $EnvFile = @('.env','.env.stage13')
        Mock Test-Path { $true } -ParameterFilter { $LiteralPath -like '*.env' -or $LiteralPath -like '*.env.stage13' }
        $null = Invoke-SmokeRun
        @($global:smokeComposeArguments | Where-Object { $_ -eq '--env-file' }).Count | Should Be 2
        ($global:smokeComposeArguments -join '|') | Should Match '--env-file\|[^|]+\\.env\|--env-file\|[^|]+\\.env.stage13$'
        $EnvFile = $null
    }
    It 'bounds incident polling and never retries simulation or attempts AI when no incident arrives' {
        $WaitSeconds = 1
        $IncludeAi = $true
        $global:smokeNoIncident = $true
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 1 -Exactly -ParameterFilter { $Method -eq 'POST' -and $Path -eq '/simulate/database-failure' }
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Path -like '/api/analysis/*' }
        # The service may still be unknown (404) until asynchronous consumption.
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Path -like '/api/services/*/events*' }
    }
    It 'fails Redis identity verification before any opted-in analysis when sorted-set evidence is empty' {
        $WaitSeconds = 1
        $IncludeAi = $true
        $global:smokeNoRedis = $true
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 0 -Exactly -ParameterFilter { $Path -like '/api/analysis/*' }
    }
    It 'selects only the new root-service incident from multi-incident list arrays' {
        $global:smokeUnrelated = [pscustomobject]@{id='44444444-4444-4444-8444-444444444444';rootService='unrelated';status='OPEN'}
        (Invoke-SmokeRun).incidentId | Should Be '22222222-2222-4222-8222-222222222222'
    }
    It 'makes exactly one opt-in analysis POST after transport checks' {
        @(Get-Command Invoke-SmokeRun -ErrorAction SilentlyContinue).Count | Should Be 1
        $IncludeAi = $true
        $summary = Invoke-SmokeRun
        $summary.analysis | Should Be 'validated schema and evidence references (not semantic correctness)'
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 1 -Exactly -ParameterFilter { $Method -eq 'POST' -and $Path -like '/api/analysis/*' }
    }
    It 'does not retry the opted-in analysis on provider failure' {
        @(Get-Command Invoke-SmokeRun -ErrorAction SilentlyContinue).Count | Should Be 1
        $IncludeAi = $true
        Mock Invoke-SmokeHttp { throw 'Sanitized HTTP failure' } -ParameterFilter { $Path -like '/api/analysis/*' }
        { Invoke-SmokeRun } | Should Throw
        Assert-MockCalled -Scope It Invoke-SmokeHttp -Times 1 -Exactly -ParameterFilter { $Method -eq 'POST' -and $Path -like '/api/analysis/*' }
    }
}