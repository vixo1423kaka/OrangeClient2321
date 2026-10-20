# Orange Client Loader — Windows

## Build
Po zbudowaniu workflow artefakt znajduje się w GitHub Actions jako `OrangeClientLoader-Windows`.

## Funkcje
- Osobna instancja gry dla każdej wersji: `%APPDATA%/OrangeClientLoader/instances/<wersja>`.
- Wybór Minecraft Java 1.21.4–1.21.11; instalator konfiguruje Fabric i pobiera pasujące Fabric API.
- Zakładka **Mody**: wyszukiwanie modów na Modrinth, instalowanie wydania zgodnego z wybraną wersją Minecraft i Fabric oraz usuwanie dodatkowych plików JAR.
- Konto Microsoft (OAuth w przeglądarce) lub tryb offline z nazwą gracza.
- Zapamiętane sesje Microsoft chronione przez Windows DPAPI.
- Klucz licencji zapamiętywany w `%APPDATA%/OrangeClientLoader/settings.json`.
- Wykrywanie profili/kont w oficjalnym launcherze, Modrinth App, Prism i MultiMC (tylko podstawowe dane profilu; tokeny z innych launcherów nie są odczytywane).

## Ważne: zgodność Orange Client
Aktualny plik źródłowy moda jest skonfigurowany dla Minecraft **1.21.4** (`gradle.properties` i `fabric.mod.json`). Loader pozwala przygotować instancje Fabric dla 1.21.4–1.21.11 i zarządzać dodatkowymi modami, ale Orange Client jest automatycznie kopiowany tylko do instancji 1.21.4. Pozostałe wersje wymagają osobnego portowania i testowania kodu moda (zwłaszcza mixinów) przed dołączeniem ich JAR-ów do EXE. Nie należy deklarować ich jako wspieranych przez Orange Client, dopóki build i test uruchomienia nie przejdą.

## Logowanie Microsoft — jednorazowa konfiguracja
Loader używa własnej aplikacji OAuth i nie pobiera tokenów z innych launcherów.
1. Otwórz https://portal.azure.com → Microsoft Entra ID → App registrations → New registration.
2. Wybierz **Personal Microsoft accounts only**.
3. Dodaj Redirect URI dla **Mobile and desktop applications**: `http://localhost`.
4. Skopiuj *Application (client) ID* i ustaw zmienną repozytorium `AZURE_CLIENT_ID` w GitHub → Settings → Secrets and variables → Actions → Variables. Workflow osadza ID w EXE.
5. Sprawdź wymagania Mojang dla Minecraft API: https://aka.ms/AppRegInfo

Tryb offline nie jest kontem premium i nie zapewnia uwierzytelniania na serwerach wymagających prawdziwego konta Microsoft/Minecraft.
