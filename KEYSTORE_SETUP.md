# Konfiguracja podpisu APK dla GitHub Actions

## Wymagane Secrets (ustaw w GitHub Settings -> Secrets -> Actions):

### 1. KEYSTORE_B64
Zakodowany keystore w formacie base64:
```bash
base64 -w 0 keystore.jks
```
Wklej wynik do wartości `KEYSTORE_B64`

### 2. KEYSTORE_PASSWORD
Hasło do keystore (np. `android`)

### 3. KEY_ALIAS
Alias klucza (np. `android` lub `release`)

### 4. KEY_PASSWORD
Hasło do klucza (może być takie samo jak KEYSTORE_PASSWORD)

## Jak wygenerować keystore:

```bash
keytool -genkey -v -keystore keystore.jks \
  -alias release \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Uruchomienie buildu z podpisem:

1. Przejdź do Actions -> Android CI
2. Kliknij "Run workflow"
3. Wybierz typ buildu: `debug`, `release` lub `both`
4. Zaznacz "Podpisz APK" jeśli masz skonfigurowane Secrets
5. Kliknij "Run workflow"

## Uwagi:
- Debug APK nie wymaga podpisu (podpisuje się automatycznie debug key)
- Release APK bez podpisu jest unsigned i nie może być instalowana na produkcji
- Ten sam keystore pozwala na aktualizację aplikacji (ważne dla użytkowników!)
