$demo = Join-Path (Split-Path $PSScriptRoot -Parent) 'demo-load-balancing.ps1'
if (Test-Path $demo) { . $demo }

Describe 'Kubernetes Service HTTP balancing demo' {
    It 'provides the read-only demo script' {
        Test-Path $demo | Should Be $true
    }
    It 'counts actual responding hostnames without inventing equal distribution' {
        $rows = @(
            [pscustomobject]@{serviceName='incident-service';hostname='pod-a';timestamp='2026-11-18T12:00:00Z'},
            [pscustomobject]@{serviceName='incident-service';hostname='pod-b';timestamp='2026-11-18T12:00:01Z'},
            [pscustomobject]@{serviceName='incident-service';hostname='pod-a';timestamp='2026-11-18T12:00:02Z'}
        )
        $summary = Get-DemoSummary $rows @('pod-a','pod-b')
        $summary.requests | Should Be 3
        $summary.distinctInstances | Should Be 2
        @($summary.instances | Where-Object { $_.hostname -eq 'pod-a' })[0].requests | Should Be 2
        @($summary.instances | Where-Object { $_.hostname -eq 'pod-b' })[0].requests | Should Be 1
    }
    It 'does not claim distribution if only one instance answered' {
        $row = [pscustomobject]@{serviceName='incident-service';hostname='pod-a';timestamp='2026-11-18T12:00:00Z'}
        { Get-DemoSummary @($row,$row) @('pod-a','pod-b') } | Should Throw
    }
    It 'rejects unknown hosts, wrong services, and invalid timestamps' {
        foreach ($row in @(
            [pscustomobject]@{serviceName='wrong';hostname='pod-a';timestamp='2026-11-18T12:00:00Z'},
            [pscustomobject]@{serviceName='incident-service';hostname='unknown';timestamp='2026-11-18T12:00:00Z'},
            [pscustomobject]@{serviceName='incident-service';hostname='pod-a';timestamp='invalid'}
        )) {
            { Assert-DemoResponse $row @('pod-a','pod-b') } | Should Throw
        }
    }
    It 'uses a fresh curl process via Service DNS for every request' {
        $Requests = 2
        $global:demoCalls = 0
        Mock Invoke-DemoKubectl {
            param($Arguments)
            if ($Arguments -contains 'service') { return '{"spec":{"sessionAffinity":"None"}}' }
            if ($Arguments -contains 'pods') {
                return '{"items":[{"metadata":{"name":"pod-a"},"status":{"conditions":[{"type":"Ready","status":"True"}]}},{"metadata":{"name":"pod-b"},"status":{"conditions":[{"type":"Ready","status":"True"}]}}]}'
            }
            if ($Arguments -contains 'curl') {
                $global:demoCalls++
                $name = if ($global:demoCalls -eq 1) { 'pod-a' } else { 'pod-b' }
                return (@{serviceName='incident-service';hostname=$name;timestamp='2026-11-18T12:00:00Z'} | ConvertTo-Json -Compress)
            }
            throw 'Unexpected command'
        }
        (Invoke-DemoRequests).distinctInstances | Should Be 2
        Assert-MockCalled -Scope It Invoke-DemoKubectl -Times 2 -Exactly -ParameterFilter {
            $Arguments -contains 'curl' -and $Arguments -contains 'http://incident-service:8080/api/instance' -and $Arguments -contains 'deployment/api-gateway'
        }
    }
    It 'rejects sticky sessions before sending HTTP requests' {
        Mock Invoke-DemoKubectl { return '{"spec":{"sessionAffinity":"ClientIP"}}' } -ParameterFilter { $Arguments -contains 'service' }
        { Invoke-DemoRequests } | Should Throw
        Assert-MockCalled -Scope It Invoke-DemoKubectl -Times 0 -Exactly -ParameterFilter { $Arguments -contains 'curl' }
    }
}
