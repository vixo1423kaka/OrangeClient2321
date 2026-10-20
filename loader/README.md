# Orange Client Loader — modern UI prototype

## Build on Windows
1. Install Python 3.10+ and enable `py` in PATH.
2. Run `build.bat` from this folder.
3. The EXE is created at `dist/OrangeClientLoader.exe`.

GitHub Actions also builds an artifact: open the Actions workflow, open the successful run, and download `OrangeClientLoader-Windows` from Artifacts.

## Included
- Dark, modern CustomTkinter interface.
- Scans the default Minecraft folder plus common Modrinth, Prism Launcher and MultiMC instance locations where detectable.
- Allows selecting a custom game directory containing `versions`.
- Offline mode with username entry.
- Microsoft option opens the official launcher if found; it does **not** extract or steal a session token and does not yet launch a Microsoft-authenticated profile directly from this UI.
- Writes the license key into the selected game directory's `config/orangeclient_license.json`, where the mod can validate it.

## Important
This is a prototype. The selected instance needs to be fully installed and contain the Fabric profile and Orange Client. Alternate launcher layouts differ, so automatic detection may not find every instance. Microsoft OAuth login is not implemented yet; the Microsoft button is currently a safe hand-off to the official launcher, not a full integrated login. Offline mode cannot authenticate to online servers that require Microsoft accounts.
