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


## Weryfikacja licencji w loaderze

Zakładka Play zawiera pole klucza i przycisk **Verify license**. Po wykryciu uruchomionego Minecrafta 1.21.4 + Fabric loader wysyła klucz i odcisk JVM do istniejącego endpointu licencji, weryfikuje podpis Ed25519 tokenu oraz jego terminy i wiązanie z urządzeniem. Przycisk **LOAD** pozostaje zablokowany bez poprawnej weryfikacji i ponownie sprawdza licencję tuż przed wykonaniem akcji.

Weryfikacja używa tego samego endpointu i klucza publicznego co `LicenseManager.java`; prywatny klucz podpisujący nie znajduje się w loaderze. Endpoint musi zwracać `valid`, `token`, `signature` i roszczenia tokenu `iat`, `exp`, `license_exp`, `key_hash`, `hwid_hash`. Zegar i czas rozpoczęcia licencji pozostają odpowiedzialnością serwera.

**Ograniczenie:** to wdraża sprawdzanie licencji i blokadę przycisku, nie wstrzykiwanie kodu do już uruchomionej gry. Obecny kod Orange Client używa mixinów Fabric, które muszą być przygotowane przy starcie JVM; jeśli JAR klienta nie był załadowany przy starcie, samo kliknięcie LOAD nie może dołączyć jego mixinów do istniejącej sesji. Tę część trzeba rozwiązać osobnym bootstrapem i przetestować oddzielnie.
