@echo off
setlocal
cd /d "%~dp0"
py -m pip install --upgrade pip
py -m pip install -r requirements.txt
if not exist "..\build\libs\orange-client-0.1.0.jar" (
  echo Brak JAR moda. Uruchom najpierw ..\gradlew.bat build
  pause
  exit /b 1
)
py -m PyInstaller --noconfirm --clean --onefile --windowed --collect-all customtkinter --add-data "..\build\libs\orange-client-0.1.0.jar;client-mods" --name OrangeClientLoader loader.py
echo.
echo Gotowe: dist\OrangeClientLoader.exe
pause
