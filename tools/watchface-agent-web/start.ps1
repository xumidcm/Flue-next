param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8765,
    [string]$PythonPath = ''
)
$ErrorActionPreference = 'Stop'
$bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
if ($PythonPath) {
    $runtimePython = $PythonPath
} elseif (Test-Path -LiteralPath $bundledPython) {
    $runtimePython = $bundledPython
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    $runtimePython = (Get-Command python).Source
} else {
    throw 'Python was not found. Pass -PythonPath with a Python executable that has Pillow and NumPy.'
}
& $runtimePython -c 'import sys, PIL, numpy; assert sys.version_info >= (3, 10); print("Python", sys.version.split()[0], "Pillow", PIL.__version__, "NumPy", numpy.__version__)'
if ($LASTEXITCODE -ne 0) {
    throw 'Python 3.10+, Pillow and NumPy are required. This launcher does not install dependencies.'
}
Write-Host "Open http://127.0.0.1:$Port in your browser. Ctrl+C stops the server."
& $runtimePython (Join-Path $PSScriptRoot 'server.py') --port $Port
exit $LASTEXITCODE
