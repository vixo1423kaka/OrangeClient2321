# Orange Client Loader — Windows prototype

## Build
Download the latest successful artifact from GitHub Actions:
https://github.com/vixo1423kaka/OrangeClient2321/actions/workflows/build-loader.yml

The build workflow compiles Orange Client, bundles the mod jar into the EXE, and uploads `OrangeClientLoader-Windows`.

## Features
- Dark modern UI.
- Automatically searches common install locations for Minecraft, Modrinth, Prism Launcher, MultiMC, ATLauncher and CurseForge.
- Version list is loaded from Mojang's live version manifest and starts at Minecraft 1.21.4.
- Uses a dedicated loader-managed game directory under `%APPDATA%/OrangeClientLoader/instances/<version>` so it does not overwrite another launcher's mods/configs.
- For Minecraft 1.21.4, installs Minecraft + Fabric, places the bundled Orange Client jar and downloads Fabric API.
- Offline mode supported.
- Microsoft mode opens the official launcher if detected; it does not yet perform Microsoft OAuth in this app.

## Compatibility warning
The mod's current `fabric.mod.json` declares Minecraft 1.21.4. The loader lists newer releases, but the mod itself has not been ported to them; selecting a newer version will be rejected by the current launcher code rather than pretending the mod works. Microsoft account authentication is not implemented yet.
