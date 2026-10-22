# Orange Client Loader — Windows

## Build
Budowanie nie wymaga Azure Client ID ani żadnej zmiennej repozytorium. Tryb offline działa od razu po zbudowaniu.
Po zbudowaniu workflow artefakt znajduje się w GitHub Actions jako `OrangeClientLoader-Windows`.

Aby zbudować lokalnie w Windows: uruchom `gradlew.bat build` w katalogu głównym repozytorium, a następnie `loader\\build.bat`.
Gotowy plik będzie w `loader\\dist\\OrangeClientLoader.exe`.

## Wygląd
- Autorskie menu główne Minecrafta w czarno-pomarańczowej stylistyce, z unoszącymi się drobinami, animowanymi przyciskami, pulsującą poświatą i obracającą się pomarańczą.
- Launcher Windows ma dedykowaną ikonę pomarańczy generowaną podczas budowania.

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

## Logowanie Microsoft — opcjonalna konfiguracja
Loader używa własnej aplikacji OAuth i nie pobiera tokenów z innych launcherów. Microsoft Client ID nie jest potrzebny do zbudowania EXE ani do trybu offline; jest potrzebny tylko wtedy, gdy chcesz włączyć logowanie Microsoft.

1. Otwórz https://portal.azure.com → Microsoft Entra ID → App registrations → New registration.
2. Wybierz **Personal Microsoft accounts only**.
3. Dodaj Redirect URI dla **Mobile and desktop applications**: `http://localhost`.
4. Skopiuj *Application (client) ID* i wpisz go w loaderze: **Ustawienia → Azure Client ID → Zapisz**. Alternatywnie ustaw zmienną środowiskową `ORANGE_AZURE_CLIENT_ID` przed uruchomieniem. Nie musisz konfigurować zmiennej repozytorium GitHub Actions.
5. Sprawdź wymagania Mojang dla Minecraft API: https://aka.ms/AppRegInfo

Bez Client ID przycisk logowania Microsoft poprosi o konfigurację, ale zakładki Gra/Mody i tryb offline pozostają dostępne.

Tryb offline nie jest kontem premium i nie zapewnia uwierzytelniania na serwerach wymagających prawdziwego konta Microsoft/Minecraft.



## Aktywacja licencji i bootstrap

Loader nie uruchamia gry. Wykrywa Minecraft 1.21.4 z Fabric i Fabric API. Przy pierwszym użyciu **LOAD** instaluje pojedynczy plik bootstrap do profilu Fabric; poprzedni plik Orange Client, jeśli istnieje, przenosi do `config/orangeclient-backup`. Fabric ładuje mody tylko podczas startu, więc po tej jednorazowej instalacji trzeba całkowicie zrestartować grę.

Bootstrap zawiera właściwy Orange Client jako zagnieżdżony mod Fabric, dlatego nie trzeba osobno umieszczać JAR-a klienta w folderze `mods`. Po restarcie mod tworzy losowo autoryzowany endpoint HTTP ograniczony do `127.0.0.1`. Naciśnięcie **LOAD** wysyła klucz do tego endpointu; właściwy klient sprawdza go na serwerze licencji, weryfikuje podpisany token Ed25519 i dopiero po poprawnej odpowiedzi odblokowuje funkcje. Samo zainstalowanie bootstrapu nie aktywuje ani nie rozpoczyna liczenia okresu licencji.

Serwer licencji musi obsługiwać istniejący endpoint `https://Vixo.pythonanywhere.com/validate` w formacie zgodnym z `LicenseManager.java`. Serwer odpowiada za aktywację niewykorzystanego klucza, datę wygaśnięcia i blokowanie licencji. Loader nie ma prywatnego klucza podpisującego.

**Uwaga:** budowanie teraz tworzy `build/libs/orange-client-0.1.0.jar` jako pakiet bootstrapu, a właściwy mod znajduje się wewnątrz niego jako `META-INF/jars/orange-client-0.1.0.jar`. Standardowe zadanie `gradlew build` tworzy oba pliki.
