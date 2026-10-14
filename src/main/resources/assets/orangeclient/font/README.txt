Czcionka clienta: Inter (SIL Open Font License 1.1, licencja w OFL-Inter.txt).

inter.ttf / inter_bold.ttf  - pliki czcionki (statyczne TTF).
inter_s1..s6.json, inter_bold_s1..s6.json - definicje czcionki PER SKALA GUI.
  Client rysuje tekst w rozdzielczosci fizycznych pikseli ekranu: dla skali GUI = N uzywa
  definicji *_sN (rozmiar = 11 * N, oversample 1.0), a potem zmniejsza o 1/N. Dzieki temu
  litery maja ostre krawedzie przy kazdej skali GUI.

Zmiana wielkosci tekstu: w KAZDYM pliku *_sN.json ustaw "size" = baza * N (i zmien TEXT_BASE w util/UiFont.java, jesli chcesz zachowac wysrodkowanie)
  (baza teraz = 11, np. baza 12 => s1: 12.0, s2: 24.0, s3: 36.0 ...).

Wybor czcionki robi util/UiFont.java: Inter -> stary Instrument Sans (ui.ttf) -> domyslna MC.
