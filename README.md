# EVE – aplikacja na Androida

Słucha w tle (także przy wygaszonym ekranie) i reaguje na **„Iwi, …”**.
Wykrywanie słowa działa offline (Vosk, polski model), całe polecenie trafia do rdzenia EVE,
który odpowiada głosem.

## Instalacja na telefonie
1. Na telefonie otwórz: **Releases → latest → EVE.apk** (w tym repozytorium) i pobierz.
2. Zainstaluj (Android zapyta o zgodę na instalację z przeglądarki – zezwól).
3. W aplikacji wpisz **adres EVE** (np. `http://192.168.1.107:8000`) i **kod dostępu** → „Zapisz i sprawdź”.
4. „Włącz nasłuch „Iwi”” → zezwól na mikrofon i powiadomienia.

Nowe wersje instalują się na starą (stały klucz podpisu w `app/eve-debug.keystore` – to klucz
testowy, nie służy do niczego poza tą aplikacją).

## Budowanie
Każdy push na `main` buduje APK na GitHub Actions i podmienia wydanie **latest**.
