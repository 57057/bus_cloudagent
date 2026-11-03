$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & "$PSScriptRoot\.venv\Scripts\python.exe" "$PSScriptRoot\run_local.py" @args
    if ($LASTEXITCODE -ne 0) { throw "Cloud Agent startup failed. See runtime/logs." }
} finally {
    Pop-Location
}
