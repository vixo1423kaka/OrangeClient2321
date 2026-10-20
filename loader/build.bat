@echo off
setlocal
py -m pip install --upgrade pip
py -m pip install -r requirements.txt
py -m PyInstaller --noconfirm --clean --onefile --windowed --name OrangeClientLoader loader.py
echo.
echo Gotowe: dist\OrangeClientLoader.exe
pause
