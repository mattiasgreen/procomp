Set-StrictMode -Version Latest

function Assert-Eq {
    param($Actual, $Expected, [string]$Message = "")
    if ($Actual -ne $Expected) {
        throw "Assert-Eq failed${Message}: expected '$Expected' but got '$Actual'"
    }
}

function Assert-Neq {
    param($Actual, $NotExpected, [string]$Message = "")
    if ($Actual -eq $NotExpected) {
        throw "Assert-Neq failed${Message}: did not expect '$NotExpected'"
    }
}

function Assert-Match {
    param([string]$Actual, [string]$Pattern)
    if ($Actual -notmatch $Pattern) {
        throw "Assert-Match failed: '$Actual' did not match '$Pattern'"
    }
}

function Assert-Gt {
    param($Actual, $Threshold)
    if (-not ($Actual -gt $Threshold)) {
        throw "Assert-Gt failed: expected '$Actual' > '$Threshold'"
    }
}

function Assert-NotNull {
    param($Actual)
    if ($null -eq $Actual) {
        throw "Assert-NotNull failed: value was null"
    }
}

function Assert-Throws {
    param([scriptblock]$ScriptBlock)
    $threw = $false
    try {
        & $ScriptBlock
    } catch {
        $threw = $true
    }
    if (-not $threw) {
        throw "Assert-Throws failed: expected an exception to be thrown"
    }
}
