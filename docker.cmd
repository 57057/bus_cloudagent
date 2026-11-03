@echo off
"%~dp0.venv\Scripts\python.exe" "%~dp0deploy\docker_cli.py" %*
exit /b %errorlevel%
