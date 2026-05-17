# SubUI Mirror - Energy Efficient Screen Mirroring for Samsung Galaxy Z Flip 3

Aplikacja do mirrorowania ekranu na cover display z optymalizacją pod kątem oszczędności baterii, specyficznie dla Samsung Galaxy Z Flip 3.

## 📱 Specyfikacja Samsung Galaxy Z Flip 3

- **Main Display (duży ekran)**: 2268x1080 (19.5:9), 6.7" FHD+ Dynamic AMOLED 2X
- **Cover Display (mały ekran)**: 512x260 (~2:1), 1.9" Super AMOLED
- **Android 16** (One UI 6+)

## 🚀 Optymalizacje wydajnościowe

### Zmniejszone zużycie baterii:
- **SENSOR_DELAY_UI** (200ms) zamiast SENSOR_DELAY_NORMAL (50ms) - rzadsze aktualizacje sensora orientacji
- **Debounce rotation changes** (300ms) - aktualizacja tylko przy rzeczywistej zmianie rotacji
- **Właściwe czyszczenie zasobów** - VirtualDisplay zwalniany przed MediaProjection
- **Minifikacja i shrinkowanie** w buildzie release - mniejszy APK i mniej kodu do wykonania
- **Wyłączenie niepotrzebnych zasobów** w packagingOptions
- **VIRTUAL_DISPLAY_FLAG_TRUSTED_ONLY** - lepsza wydajność
- **Callbacki dla VirtualDisplay** - lepsze zarządzanie stanem

### Poprawiona rozdzielczość przy crop:
- Tryb crop: 768x390 (wyższa jakość - ~50% więcej pikseli)
- Tryb normalny: 512x260 (natywna rozdzielczość Cover Display)
- Dynamiczne DPI matching
- **Improved resolution when cropping is on** - wyższa rozdzielczość bazowa przed skalowaniem

### Obsługa obrotów:
- **Poprawione wykrywanie rotacji** - użycie `currentRotation` zamiast ponownego pobierania
- **Logowanie wszystkich zmian orientacji** - łatwiejsze debugowanie
- **4 tryby orientacji** - pełna obsługa ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270
- **Konfigurowalne ustawienia** - wybór orientacji dla portrait/landscape

## 🔧 GitHub Actions

Automatyczne budowanie APK przy push/pull request:
- Debug APK z logami debugowania
- Release APK z minifikacją i optymalizacjami
- Uruchamianie testów jednostkowych

## 🏗️ Build lokalnie

```bash
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK
./gradlew test             # Testy
```

## ⚙️ Funkcje

- **Touch mirroring** - pełne odbicie dotyku (lokalnie, nie przez sieć)
- **Crop mode** - przycięcie strumienia dla lepszej widoczności (kamera, itp.)
- **Orientation settings** - konfiguracja orientacji dla portrait/landscape
- **Energy efficient** - zoptymalizowane pod kątem baterii
- **Debug logging** - szczegółowe logi w LogCat (tag: "SubUIMirror")
- **Samsung Z Flip 3 optimized** - natywne rozdzielczości ekranów

## 📝 Uwagi

- Mirroring ma wpływ na baterię - wyłączaj gdy nie używasz
- Cover screen nie działa gdy telefon jest zamknięty jeśli mirroring jest aktywny
- Zmiana ustawień crop wymaga restartu usługi
- Wymaga Android 11+ (API 30), testowane na Android 16
- **Nie używa sieci** - całe mirrorowanie odbywa się lokalnie przez VirtualDisplay API

## 🐛 Debugowanie

Aby sprawdzić co się dzieje:
```bash
adb logcat -s SubUIMirror
```

Logi pokazują:
- Wykryte ekrany i ich parametry
- Zmiany orientacji
- Ustawienia skalowania
- Stan VirtualDisplay
- Błędy i ostrzeżenia

## 📄 Licencja

Open Source
