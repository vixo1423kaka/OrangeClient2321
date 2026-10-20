# Orange Client Loader — Windows

## Build
Najnowszy EXE: GitHub Actions → `build-loader.yml` → artefakt `OrangeClientLoader-Windows`.

## Funkcje
- Klucz licencji zapamiętywany w `%APPDATA%/OrangeClientLoader/settings.json` (zapis przy każdej zmianie).
- Wykrywa konta z oficjalnego launchera, Modrinth App, Prism i MultiMC (tylko nick/UUID/e-mail, nigdy tokeny) i pokazuje je na liście kont. Wiele kont do wyboru.
- Logowanie Microsoft zapamiętywane między uruchomieniami (token odświeżania chroniony DPAPI). Tryb Offline jako alternatywa.
- Brak migających okien cmd (Java i instalator Fabric działają bez konsoli).
- Osobny profil gry: `%APPDATA%/OrangeClientLoader/instances/<wersja>`.
- Na liście są tylko wersje obsługiwane przez mod (obecnie 1.21.4).

## Logowanie Microsoft — jednorazowa konfiguracja
Loader loguje przez własną aplikację Azure (nie czyta tokenów z innych launcherów).
1. https://portal.azure.com → Microsoft Entra ID → App registrations → New registration.
2. Supported account types: **Personal Microsoft accounts only**.
3. Redirect URI: platforma **Mobile and desktop applications**, wartość `http://localhost`.
4. Skopiuj *Application (client) ID* i ustaw go raz jako zmienną repozytorium `AZURE_CLIENT_ID`
   (GitHub → Settings → Secrets and variables → Actions → Variables). Build wbudowuje go w EXE,
   więc użytkownik niczego nie wpisuje, a pole w Ustawieniach się nie pokazuje.
   Przy lokalnym buildzie (`build.bat`) utwórz `loader/_build_config.py` z linią
   `AZURE_CLIENT_ID = "twoje-id"`. Bez wbudowanego ID loader pokaże pole w Ustawieniach.
5. Nowe aplikacje wymagają zgody Mojang na Minecraft API: https://aka.ms/AppRegInfo
