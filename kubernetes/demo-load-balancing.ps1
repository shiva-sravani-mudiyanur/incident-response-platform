<#
.SYNOPSIS
Read-only HTTP balancing demonstration through the incident-service ClusterIP.
.DESCRIPTION
Uses the kubectl context selected by KUBECONFIG/current-context. Each request
executes a new curl process in the gateway pod, opening a fresh HTTP connection
to Service DNS. No pod is chosen manually, no cookies/stickiness are used, and
no simulator, Kafka write, or model request is made. Dot-sourcing loads helpers
without performing network calls. Wait for a stable rollout before running.
#>
[CmdletBinding()]
param(
    [ValidateRange(2,100)][int]$Requests = 20,
    [ValidateLength(1,63)][ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')]
    [string]$Namespace = 'incident-response'
)

function Invoke-DemoKubectl {
    param([string[]]$Arguments)
    try {
        $result = & kubectl --request-timeout=15s -n $Namespace @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'kubectl failed' }
        return ($result -join "`n")
    } catch {
        throw 'Kubernetes inspection/request failed. Check the selected context, namespace, rollouts and connectivity; no writes were performed.'
    }
}

function Assert-DemoResponse {
    param($Response, [string[]]$ExpectedHosts)
    if ($null -eq $Response -or $Response.serviceName -cne 'incident-service' -or
        $Response.hostname -isnot [string] -or $ExpectedHosts -cnotcontains $Response.hostname -or
        $Response.timestamp -isnot [string] -or [string]::IsNullOrWhiteSpace($Response.timestamp)) {
        throw 'Instance response does not match the expected service and ready pod identities.'
    }
    try { $null = [DateTimeOffset]::Parse($Response.timestamp) }
    catch { throw 'Instance response timestamp is invalid.' }
}

function Get-DemoSummary {
    param([object[]]$Responses, [string[]]$ExpectedHosts)
    foreach ($response in $Responses) { Assert-DemoResponse $response $ExpectedHosts }
    $groups = @($Responses | Group-Object hostname | Sort-Object Name)
    if ($groups.Count -lt 2) {
        throw 'Only one responding instance was observed. Distribution is not demonstrated; check ready endpoints/affinity and rerun with a larger bounded sample. No strict alternation or equal counts are guaranteed.'
    }
    return [pscustomobject]@{
        requests = $Responses.Count
        distinctInstances = $groups.Count
        service = 'incident-service:8080'
        sessionAffinity = 'None'
        instances = @($groups | ForEach-Object { [pscustomobject]@{hostname=$_.Name;requests=$_.Count} })
    }
}

function Invoke-DemoRequests {
    $service = Invoke-DemoKubectl @('get','service','incident-service','-o','json') | ConvertFrom-Json
    if ($service.spec.sessionAffinity -cne 'None') { throw 'Service sessionAffinity must be None; sticky sessions are not part of this demonstration.' }
    $pods = Invoke-DemoKubectl @('get','pods','-l','app.kubernetes.io/name=incident-service','-o','json') | ConvertFrom-Json
    $readyNames = @($pods.items | Where-Object {
        -not $_.metadata.deletionTimestamp -and
        @($_.status.conditions | Where-Object { $_.type -eq 'Ready' -and $_.status -eq 'True' }).Count -gt 0
    } | ForEach-Object { $_.metadata.name })
    if ($readyNames.Count -lt 2) { throw 'At least two non-terminating Ready incident-service pods are required.' }
    $responses = @()
    for ($request = 1; $request -le $Requests; $request++) {
        # Each exec starts a new curl process. It contacts the Service, never a pod IP.
        $response = Invoke-DemoKubectl @('exec','deployment/api-gateway','-c','api-gateway','--',
            'curl','--silent','--show-error','--fail','--noproxy','*','--max-time','5',
            'http://incident-service:8080/api/instance') | ConvertFrom-Json
        Assert-DemoResponse $response $readyNames
        $responses += $response
        Write-Host ('{0,3}: {1}  {2}' -f $request,$response.hostname,$response.timestamp)
    }
    return Get-DemoSummary $responses $readyNames
}

if ($MyInvocation.InvocationName -eq '.') { return }
$ErrorActionPreference = 'Stop'
try {
    Invoke-DemoRequests | ConvertTo-Json -Depth 5
} catch {
    [Console]::Error.WriteLine('HTTP balancing demo failed: ' + $_.Exception.Message)
    exit 1
}
