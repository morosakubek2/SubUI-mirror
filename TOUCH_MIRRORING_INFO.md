# Touch Mirroring - Status i Ograniczenia

## Jak to działa?

### Bez Roota (Standard Android)
- **VirtualDisplay z FLAG_AUTO_MIRROR**: Android automatycznie kopiuje dotyk z głównego ekranu na wirtualny display
- **Ograniczenie**: Ze względów bezpieczeństwa, touch events NIE są przesyłane do innych aplikacji
- **Działanie**: Touch mirroring działa tylko w obrębie własnej aplikacji lub gdy system na to pozwala

### Z Rootem (Pełna funkcjonalność)
Aplikacja może używać komend `input` do symulacji dotyku:
```bash
su -c "input tap x y"
su -c "input swipe x1 y1 x2 y2"
```

## Implementacja w SubUI Mirror

### Obecny stan:
1. ✅ VirtualDisplay utworzony z `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`
2. ✅ System próbuje automatycznie mirrorować touch
3. ⚠️ Bez roota - ograniczone przez Android Security Model
4. 🔒 Z rootem - można dodać pełną obsługę przez `Runtime.exec()`

## Jak dodać pełny Touch Mirroring z Rootem?

W `MirrorService.kt` dodaj:

```kotlin
private fun sendTouchInput(x: Float, y: Float, action: Int) {
    if (!rootModeEnabled) return
    
    try {
        val command = when(action) {
            MotionEvent.ACTION_DOWN -> "input tap $x $y"
            MotionEvent.ACTION_MOVE -> "input swipe $x $y $x $y"
            else -> return
        }
        
        Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        logDebug("Touch sent via root: $command")
    } catch (e: Exception) {
        logDebug("Failed to send touch: ${e.message}")
    }
}
```

## Testowanie Touch Mirroring:

1. **Bez roota**: Sprawdź czy dotyk działa w ramach tej samej aplikacji
2. **Z rootem**: Włącz opcję "Root Mode" w ustawieniach
3. Użyj `adb shell input tap x y` do testów

## Dlaczego to nie działa idealnie bez roota?

Android blokuje injectowanie touch events do innych aplikacji ze względów bezpieczeństwa:
- Zapobiega to malware przed przejmowaniem kontroli nad urządzeniem
- Ochrona przed keyloggerami i innymi atakami
- Tylko system lub root mogą ominąć te zabezpieczenia

## Alternatywy:
- Użyj Samsung DeX jeśli dostępny
- Rozważ ADB wireless dla zdalnego sterowania
- Czekaj na Android 15+ który może mieć lepsze API
