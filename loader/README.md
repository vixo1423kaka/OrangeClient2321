# Orange Client Loader (Windows prototype)

This is a source prototype, not a prebuilt executable. Build on Windows with Python 3.10+:

1. Install Python and enable `py` in PATH.
2. Open this folder in Explorer and run `build.bat`.
3. The executable will be in `dist/OrangeClientLoader.exe`.

## What it does
- Scans the default Minecraft directory and lets you choose an installed version (including Fabric profiles whose version JSON exists).
- Lets you enter an offline username.
- Writes the license key to `config/orangeclient_license.json`, the config location expected by Orange Client, so the **client itself** can verify the key with the existing signed-token API.
- Starts the selected version using `minecraft-launcher-lib` in offline mode.

## Important limitations
- This prototype launches in offline mode only. It does not import passwords, session tokens, or private files from the official launcher/Modrinth/Prism. Offline mode cannot authenticate to Microsoft-authenticated online servers.
- For versions whose libraries/assets are not installed or whose launch metadata is incomplete, launch may fail. Select a fully installed Fabric profile containing Orange Client.
- License validation is performed by the Orange Client mod after startup. The loader does not pretend a key is valid locally. The current mod implementation opens its license screen on failure; forcing the process to exit requires a separate change in the mod.
- This prototype uses the default `%APPDATA%\\.minecraft` game directory. Multi-instance Modrinth/Prism paths are not auto-detected yet.

Only use a license key you are authorized to use.
