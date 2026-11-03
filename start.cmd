@echo off
"%~dp0.venv\Scripts\python.exe" "%~dp0run_local.py" %*
exit /b %errorlevel%
