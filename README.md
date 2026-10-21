# Klikacz ikony Świat — projekt Android

## Co robi
- Co ok. 1,2 s sprawdza zrzut ekranu, gdy w aplikacji wybrano START.
- Dopasowuje obraz `app/src/main/assets/icon_template.png` do zrzutu ekranu.
- Po znalezieniu ikony wykonuje kliknięcie w jej środek.
- Wbudowany próg dopasowania wynosi 0,88; w razie fałszywych trafień zwiększ go.

## Budowanie
1. Otwórz folder projektu w Android Studio (aktualna wersja z obsługą Android Gradle Plugin 8.7).
2. Poczekaj na synchronizację Gradle.
3. Zbuduj APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Zainstaluj APK na urządzeniu z Androidem 11 lub nowszym.

## Użycie
1. Uruchom aplikację.
2. Naciśnij „Ustawienia dostępności” i włącz usługę „Klikacz ikony Świat”.
3. Wróć do aplikacji, naciśnij START, a następnie przejdź do ekranu z ikoną.
4. Naciśnij STOP, aby wyłączyć automatyczne klikanie.

## Uwagi
- Wzór pochodzi z dostarczonego pliku 77×69 px. Jeśli ikona na ekranie ma inne kolory, skalę lub jest zasłonięta, rozpoznawanie może nie zadziałać.
- Algorytm jest prosty i może wymagać dostrojenia progu. Przeszukuje ekran w kilku rozmiarach i używa podobieństwa pikseli, nie OCR.
- Android/OEM może ograniczać zrzuty ekranu w aplikacjach zabezpieczonych lub chronionych.
- Kod jest szkieletem projektu; sprawdź kompilację na docelowej wersji Androida i urządzeniu przed użyciem.

## Logi
Ekran główny pokazuje bieżące logi: połączenie usługi, próbę zrzutu ekranu, rozmiar obrazu, wynik podobieństwa, wykrycie ikony i wynik gestu. Jeśli nic się nie dzieje, sprawdź, czy w logach pojawia się „Usługa dostępności połączona”.

## Makra i algorytm bota
- Nagrywanie działa na nakładce usługi dostępności (Android 11+): Android nie przekazuje usłudze surowych dotknięć w cudzych aplikacjach, więc na czas nagrywania na ekranie leży półprzezroczysta warstwa przechwytująca gesty (żółty ślad = zapis).
- **Nagrywanie:** wpisz nazwę makra, naciśnij **NAGRYWAJ MAKRO** (aplikacja schowa się), wykonuj gesty w grze i zakończ czerwonym przyciskiem **■ ZAKOŃCZ I ZAPISZ** u góry ekranu (auto-stop po 10 min). Gesty są od razu przekazywane do gry (opcję można wyłączyć). Obsługiwany jeden palec: tap, przytrzymanie, przeciągnięcie; bez pinch-zoom.
- **Lista makr = algorytm bota:** każde nagranie trafia na listę z checkboxem. Zaznaczone makra wykonują się **po kolei, od góry do dołu**; ▲/▼ zmieniają kolejność, **Usuń** kasuje makro (z potwierdzeniem). Makro z poprzedniej wersji zostaje przeniesione jako „Makro (poprzednie)”.
- **Uruchomienie:** ustaw liczbę powtórzeń całego algorytmu i opóźnienia losowe, „Zapisz ustawienia makra”, potem **URUCHOM BOTA**. Aplikacja schowa się i na ekranie pojawi się zielony przycisk **▶ START BOTA** (oraz ✕, który go zamyka). Przejdź do gry i dotknij START — bot ruszy po ok. 0,8 s. Podczas pracy widoczny jest **■ ZATRZYMAJ BOTA**; po zatrzymaniu lub zakończeniu wraca przycisk START.
- Przyciski na ekranie przepuszczają dotyk tylko wtedy, gdy gest bota/nagrania przechodziłby przez ich obszar.
- **AWARYJNE ZATRZYMANIE** w panelu zatrzymuje bota i chowa nakładki. Już rozpoczęty gest Androida może dobiec do końca (maks. 10 s).
- Współrzędne są zapisywane jako ułamki ekranu, więc odtwarzanie skaluje się do bieżącej rozdzielczości i orientacji.
- Jeśli usługa dostępności nie jest włączona, przyciski NAGRYWAJ/URUCHOM BOTA zapiszą to w logach i otworzą ustawienia dostępności. Na Androidzie 13+ przy APK spoza sklepu: Informacje o aplikacji → ⋮ → „Zezwól na ustawienia z ograniczeniami”.
