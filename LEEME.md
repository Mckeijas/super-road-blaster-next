# Super Road Blaster Next · ロードブラスター

**Super Road Blaster Next** es un port para **Android** de *Super Road Blaster*, el juego
homebrew de SNES con vídeo MSU-1 basado en el anime *Road Blaster*. Antes se llamaba
simplemente «Super Road Blaster»; ahora se llama **Super Road Blaster Next** (iniciales
**SRBN**), con el «NEXT» en neón sobre el logo y manteniendo el título japonés ロードブラスター.

Es una sola APK con launcher propio, emulación SNES con MSU-1, **restauración HD**,
**audio MSU-1 Enhanced** y **Director's Mode**. Está pensada para consolas Android con mando
y pantalla apaisada (desarrollada y probada en AYN Odin 2 Portal, ARM64).

```
SuperRoadBlaster\                 ← tus archivos originales (no se tocan)
├─ SuperRoadBlaster.sfc / .msu / -0.pcm … -121.pcm
└─ Android-Port\                  ← repositorio git
   ├─ android\                    ← proyecto de Android Studio (ábrelo a él)
   │  └─ keystore\srbn.keystore   ← clave de firma (¡haz copia de seguridad!)
   ├─ gamedata\
   │  ├─ states\                  ← 119 puntos de inicio de escena (selector)
   │  └─ enhanced\                ← 122 pistas MSU-1 Enhanced (FLAC)
   ├─ tools\                      ← herramientas con las que se generó todo (icono incluido)
   └─ LEEME.md
```

La compilación toma tus archivos del juego de la carpeta padre (`srbGameDir=../..` en
`android/gradle.properties`) y los mete en la APK. **No muevas la carpeta `Android-Port`**
fuera de `SuperRoadBlaster` o, si lo haces, pon la ruta completa en `srbGameDir`.

---

## 1. Compilar la APK

Necesitas **Android Studio** y unos 10 GB libres.

1. Android Studio → **Open** → carpeta `Android-Port\android`.
2. Espera al *Gradle Sync* (descarga Gradle, el plugin de Android, el NDK y CMake).
   - Si aparece «NDK not configured» o «CMake not found»: *Tools ▸ SDK Manager ▸ SDK Tools*,
     marca **NDK (Side by side)** y **CMake**, Apply y repite el sync.
3. **Build ▸ Select Build Variant…** → **release**.
4. **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
5. La APK queda en `android\app\build\outputs\apk\release\SuperRoadBlasterNext-v1.4.0-release.apk`
   (≈ 1 GB: lleva dentro el vídeo).

Desde terminal, en la carpeta `android`: `gradlew.bat assembleRelease`

### Versiones y nombre de la APK
La versión está en `android/gradle.properties` → `srbVersion=1.4.0`. **Súbela antes de cada
build que vayas a instalar** (1.4.0 → 1.4.1 para arreglos, 1.5.0 para novedades). La APK sale
con nombre `SuperRoadBlasterNext-v<versión>-release.apk`, y Android muestra esa versión en
*Ajustes ▸ Apps ▸ Super Road Blaster Next*. El número interno (versionCode) se calcula solo
(1.4.0 → 10400); Android no permite instalar una versión con número menor que la instalada.

### Aviso «app potencialmente insegura» (Play Protect)
Sale con cualquier APK instalada fuera de Google Play que Google no conozca; no depende del
nombre ni de la versión. Pulsa **Más detalles ▸ Instalar de todos modos**. Si te ofrece
«Enviar la app para análisis», puedes decir que no (la APK lleva el vídeo del juego).
Mientras la firma (`srbn.keystore`) y el nombre de paquete no cambien, las actualizaciones
se instalan encima sin problemas.

### Firma fija: actualizar sin perder partidas
Todas las compilaciones se firman con `android\keystore\srbn.keystore`, así que cada APK
nueva **se instala encima de la anterior conservando partidas y ajustes**. Guarda una copia
de ese archivo fuera del PC (no se sube a git). Si se perdiera, Android rechazaría las
actualizaciones y habría que desinstalar la app, lo que borra sus datos.

## 2. Instalar

- Copia `SuperRoadBlasterNext-v….apk` al dispositivo, ábrela con el gestor de archivos y permite
  «instalar apps desconocidas», o bien `adb install -r SuperRoadBlasterNext-v1.4.0-release.apk`.
- Aparece como **Super Road Blaster Next**, con el icono SRBN.

## 3. El launcher

Al abrir la app sale una intro animada (sol ochentero, carretera de neón, logo cromado);
pulsa **Start** para entrar. Se puede desactivar en *Controles ▸ Intro al abrir*.

| Menú principal | Qué hace |
|---|---|
| Continuar | Retoma la última partida exactamente donde la dejaste |
| Nueva partida | Arranque normal: logo, título y juego |
| Director's Mode | Ver la película completa, QTE automático/asistido, invencibilidad, cámara lenta, marcador y **selector de escenas** |
| Gráficos | Filtro con vista previa en vivo y comparación en pantalla partida |
| Audio | MSU-1 Enhanced / Original / Alternativa, volúmenes, sonidos del menú |
| Controles | Distribución de botones, **controles táctiles**, vibración, intro |
| Salir | Cerrar la app |

Fondo con montaje de escenas del anime, vistas previas en vídeo, cursor animado y sonidos
de menú sintetizados para el port. Fuente de menús: Kaushan Script.

### Director's Mode
- **Ver la película completa**: la aventura entera desde el prólogo, sin manos (≈ 19 min).
- **QTE automático**: *Asistido* te salva en el último instante; *Automático* conduce solo.
- **Invencibilidad**: los choques nunca restan vidas.
- **Cámara lenta**: permanente, o **mantén L2** durante la partida. El audio se estira sin
  cambiar el tono. **R2** = avance rápido ×2.
- **Marcador**: ocultar puntos y flechas (también con **R3**).
- **Selector de escenas**: los tramos de recorrido de cada nivel, con mapa de niveles (L/R)
  y vista previa en vídeo.

### Gráficos · restauración HD
- **Restauración HD**: limpieza del ruido del códec MSU-1 → reescalado con reconstrucción
  de bordes **AMD FSR 1.0 EASU** → nitidez adaptativa y refuerzo del trazo del anime.
- **Restauración HD+**: más agresiva, elimina los bloques en escenas oscuras.
- **Original nítido**, **Suavizado**, **CRT clásico** y **color remasterizado**.
- Formato 4:3 original, pantalla completa o zoom.

### Audio · MSU-1 Enhanced
Las 122 pistas están remasterizadas: sin saturación (42 pistas llegaban a 0 dBFS), ruido
reducido, ecualización ligera y volumen igualado entre escenas (de hasta 20 dB de diferencia
a 1,6 dB de desviación). Duración idéntica al sample, así que la sincronía con el vídeo es
exacta. Salida a 48 kHz.

**Banda sonora alternativa**: elige «Alternativa» y copia tus archivos en
`Android/data/com.srbport.roadblaster/files/banda_sonora/` del dispositivo, con el nombre
`SuperRoadBlaster-N.pcm` (formato MSU-1) o `SuperRoadBlaster-N.flac` (44,1 kHz estéreo).
Los que falten se toman del original. **La duración debe coincidir con la original.**

## 4. Controles en partida

| Mando | Acción |
|---|---|
| Cruceta / stick izquierdo | Girar |
| Botón derecho (B) | Acelerar / turbo (A de SNES) |
| Botón inferior (A) | Frenar (B de SNES) |
| **Start** | **Pausa**: «PAUSA» con *Continuar* y *Salir al menú principal* |
| Atrás | Menú completo: guardar o cargar (3 ranuras), escenas, Director's Mode, gráficos, audio |
| L2 / R2 (mantener) | Cámara lenta / avance rápido |
| R3 | Mostrar u ocultar marcador |

### Controles táctiles (móviles)
En *Controles ▸ Controles táctiles*: **Automático** (aparecen al tocar la pantalla y se ocultan
al usar un botón físico), **Siempre** o **Desactivados**. Son botones flotantes muy
transparentes: cruceta a la izquierda, **A** (acelerar) y **B** (frenar) a la derecha,
**START** abajo y **❚❚** (menú de pausa) arriba a la derecha. La transparencia se ajusta en
*Transparencia de los botones*. Todos los menús funcionan también tocando la pantalla.

En la pantalla de título y en la de récords, Start funciona como en el juego original.
En *Controles* puedes cambiar a «Por etiqueta» si prefieres que el botón con la letra A sea
la A de SNES. La partida se guarda sola al salir o al pausar el dispositivo.

## 5. Si algo falla

- **La app se cierra al empezar**: con el dispositivo por ADB,
  `adb logcat -s SRBPort SRBGame SRBRenderer SRBEmu`.
- **«No encuentro SuperRoadBlaster.msu»** al compilar: revisa `srbGameDir` en
  `android/gradle.properties`.
- **Falta de memoria al compilar**: sube `org.gradle.jvmargs=-Xmx4g` a `-Xmx6g`.
- **Android pide desinstalar para actualizar**: la APK no se firmó con `srbn.keystore`;
  no aceptes y revisa que el archivo esté en `android\keystore\`.

## 6. Repositorio git

Esta carpeta es un repositorio git. **No incluye** la ROM, el `.msu`, los `.pcm`, la
carpeta `gamedata`, las APK, la clave de firma ni los archivos de compilación. Sí incluye
los clips y miniaturas del launcher, que salen del anime: **mantén el repositorio privado**.

## Licencias y uso

- Juego Super Road Blaster © snesdev0815 (código MIT). El vídeo y el audio proceden del
  anime Road Blaster y tienen derechos de terceros: **la APK es para uso personal, no la
  distribuyas.**
- Snes9x (licencia Snes9x, no comercial) · AMD FSR 1.0 (MIT) · Sonic (Apache 2.0) ·
  dr_flac (dominio público / MIT-0) · fuentes Chakra Petch, Russo One, Share Tech Mono y
  Kaushan Script (OFL). Sonidos del menú e icono SRBN creados para este port.
