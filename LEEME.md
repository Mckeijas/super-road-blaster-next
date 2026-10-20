# Super Road Blaster · Port para Android (AYN Odin 2 Portal)

Una sola APK con launcher propio, emulación SNES con MSU-1, **restauración HD**,
**audio MSU-1 Enhanced** y **Director's Mode**.

```
SuperRoadBlaster\                 ← tus archivos originales (no se tocan)
├─ SuperRoadBlaster.sfc / .msu / -0.pcm … -121.pcm
└─ Android-Port\
   ├─ android\                    ← proyecto de Android Studio (ábrelo a él)
   ├─ gamedata\
   │  ├─ states\                  ← 119 puntos de inicio de escena (selector)
   │  └─ enhanced\                ← 122 pistas MSU-1 Enhanced (FLAC)
   ├─ tools\                      ← herramientas con las que se generó todo
   └─ LEEME.md
```

La compilación toma tus archivos del juego de la carpeta padre (`srbGameDir=../..` en
`android/gradle.properties`) y los mete en la APK. **No muevas la carpeta `Android-Port`**
fuera de `SuperRoadBlaster` o, si lo haces, pon la ruta completa en `srbGameDir`.

---

## Novedades v1.2

- Nuevo nombre: **Super Road Blaster Next** (logo con «NEXT» en neón; se mantiene ロードブラスター).
- Fuente de pincel en menús y títulos: **Kaushan Script** (licencia libre OFL), parecida a
  Burgendry, cuya licencia solo permite uso personal no comercial.
- «Ver la película» se ha movido a **Director's Mode** (primera opción). Se han quitado del
  menú principal «Ver la película» y «Créditos».
- **Start durante una carrera**: en lugar de la pausa original del juego, que solo dejaba la
  pantalla en negro, aparece **«PAUSA»** con *Continuar*, *Opciones* (el menú completo) y
  *Salir al menú principal*. Start o B vuelven al juego. El botón Atrás sigue abriendo
  directamente el menú completo. En el título y en los récords Start funciona como siempre.

## Novedades v1.1 · launcher pulido

- **Intro animada** al abrir la app: sol ochentero, carretera de neón y logo cromado con
  destello. «PULSA START» para entrar. Se puede desactivar en *Controles ▸ Intro al abrir*.
- **Fondo en movimiento**: montaje de escenas del anime en bucle, con grano y scanlines.
- **Cursor deslizante**, transiciones entre secciones e interruptores animados.
- **Sonidos del menú** sintetizados para el port (se desactivan en *Audio ▸ Sonidos del menú*).
- **Vista previa en vídeo** de cada escena (119 clips) en el inicio y en el selector.
- **Selector de escenas** con mapa de ruta de los niveles (L/R), recorrido y choques por separado.
- **Pausa integrada**: el juego se desenfoca y oscurece con una transición; el panel muestra la
  escena actual, el progreso del nivel y lo que tienes activado.
- Reloj y batería en la cabecera; «Continuar» muestra en qué escena lo dejaste.

## 1. Compilar la APK (una sola vez, ~15-30 min la primera)

Necesitas **Android Studio** (cualquier versión reciente) en tu PC con Windows y unos
10 GB libres.

1. Abre Android Studio → **Open** → elige la carpeta
   `D:\PROYECTO PORT\snes-msu1\SuperRoadBlaster\Android-Port\android`.
2. Si pregunta, pulsa **Trust Project**. Espera a que termine el *Gradle Sync*: descarga
   Gradle 8.10.2, el plugin de Android, el **NDK** y **CMake 3.22.1** automáticamente.
   - Si aparece «NDK not configured» o «CMake not found»: *Tools ▸ SDK Manager ▸ SDK Tools*,
     marca **NDK (Side by side)** y **CMake 3.22.1**, Apply y repite el sync.
   - Si sugiere actualizar el *Android Gradle Plugin*, puedes ignorarlo (Don't remind me).
3. Menú **Build ▸ Select Build Variant…** → elige **release** (va más fino; ya está firmada
   con la clave de depuración, no necesitas crear ninguna).
4. Menú **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
5. Al terminar, pulsa *locate*. La APK está en
   `android\app\build\outputs\apk\release\app-release.apk` (≈ 1 GB: lleva dentro el vídeo).

> También puedes compilar desde una terminal en la carpeta `android`:
> `gradlew.bat assembleRelease`

## 2. Instalar en el Odin 2 Portal

- **Por cable**: copia `app-release.apk` a la consola, ábrela con el gestor de archivos y
  permite «instalar apps desconocidas» cuando lo pida.
- **Con ADB**: `adb install -r app-release.apk`

Aparecerá como **Super Road Blaster** con su icono. Al ser ~1 GB la instalación tarda
un poco.

## 3. El launcher

| Sección | Qué hace |
|---|---|
| Continuar | Retoma la última partida exactamente donde la dejaste |
| Nueva partida | Arranque normal: logo, título y juego |
| Ver la película | La aventura completa sin tocar nada (≈19 min) |
| Selector de escenas | Los 119 capítulos jugables, por niveles, incluidos los choques |
| Director's Mode | QTE automático/asistido, invencibilidad, cámara lenta, HUD |
| Gráficos | Filtro con vista previa en vivo y comparación en pantalla partida |
| Audio | MSU-1 Enhanced / Original / Alternativa, volúmenes |
| Controles | Distribución de botones y vibración |

### Gráficos · restauración HD
- **Restauración HD**: limpieza del ruido del códec MSU-1 (filtro bilateral) → reescalado
  con reconstrucción de bordes **AMD FSR 1.0 EASU** → nitidez adaptativa y refuerzo del
  trazo del anime.
- **Restauración HD+**: más agresiva, elimina los bloques en escenas oscuras.
- **Original nítido**, **Suavizado**, **CRT clásico**.
- **Color remasterizado**: negros limpios, más contraste y saturación.
- Formato 4:3 original, pantalla completa o zoom.

### Audio · MSU-1 Enhanced
Las 122 pistas se han remasterizado: sin saturación (42 pistas llegaban a 0 dBFS),
reducción de ruido suave, ecualización ligera y volumen igualado entre escenas (antes
había hasta 20 dB de diferencia entre pistas; ahora la desviación es de 1,6 dB). Duración idéntica al
sample, así que la sincronía con el vídeo es exacta. Además se emite a 48 kHz (la
emulación estándar baja el MSU-1 a 32 kHz).

**Banda sonora alternativa**: elige «Alternativa (carpeta)» y copia tus archivos en
`Android/data/com.srbport.roadblaster/files/banda_sonora/` de la consola, con el nombre
`SuperRoadBlaster-N.pcm` (formato MSU-1) o `SuperRoadBlaster-N.flac` (44,1 kHz estéreo).
Los que falten se toman del original. **La duración debe coincidir con la original** o
el vídeo se desincronizará.

### Director's Mode
- **QTE automático**: *Asistido* te salva en el último instante; *Automático* conduce solo.
  Se ha comprobado jugando la aventura entera de principio a fin sin un solo choque.
- **Invencibilidad**: los choques nunca restan vidas.
- **Cámara lenta**: permanente desde el menú, o **mantén L2**. El audio se estira sin
  cambiar el tono. **R2** = avance rápido ×2.
- **Marcador**: ocultar puntos y flechas (también con **R3**).
- **Selector de escenas** y **modo película**.

## 4. Controles en partida

| Odin 2 Portal | Acción |
|---|---|
| Cruceta / stick izquierdo | Girar |
| Botón derecho (B) | Acelerar / turbo (A de SNES) |
| Botón inferior (A) | Frenar (B de SNES) |
| Start | Empezar / confirmar |
| L2 mantener / R2 mantener | Cámara lenta / avance rápido |
| R3 | Mostrar u ocultar marcador |
| Atrás | Menú de pausa: guardar o cargar (3 ranuras), escenas, opciones en vivo |

En *Controles* puedes cambiar a «Por etiqueta» si prefieres que el botón con la letra A
sea la A de SNES. La partida se guarda sola al salir o al pausar la consola.

## 5. Si algo falla

- **Compila pero la app se cierra al empezar**: conecta la consola por ADB y ejecuta
  `adb logcat -s SRBPort SRBGame SRBRenderer SRBEmu` y envíame la salida.
- **«No encuentro SuperRoadBlaster.msu»** al compilar: revisa `srbGameDir` en
  `android/gradle.properties`.
- **Falta de memoria al compilar**: sube `org.gradle.jvmargs=-Xmx4g` a `-Xmx6g`.

## Qué está probado y qué no

- **Probado con tu ROM**, en Linux, con el mismo código C++ que va en la APK: el arranque, el
  MSU-1, el QTE automático y asistido en toda la aventura, la invencibilidad, los 119 estados
  de escena, la cámara lenta, el modo película y el audio Enhanced sincronizado.
- **Probado en navegador** (Chromium, WebGL2): el launcher, el menú de pausa y todos los
  shaders, que son el mismo GLSL ES 3.00 que usa la app.
- **Compilado**: el código Java contra la API 34 de Android, y el puente JNI con Clang.
- **Sin probar todavía**: la APK funcionando en el propio Odin. No he podido compilarla
  aquí porque los servidores de Google no son accesibles desde mi entorno. Es el primer
  paso que tienes que hacer tú.

## Licencias y uso

- Juego Super Road Blaster © snesdev0815 (código MIT). El vídeo y el audio proceden del
  anime Road Blaster y tienen derechos de terceros: **la APK es para uso personal, no la
  distribuyas.**
- Snes9x (licencia Snes9x, no comercial) · AMD FSR 1.0 (MIT) · Sonic (Apache 2.0) ·
  dr_flac (dominio público / MIT-0) · fuentes Chakra Petch, Russo One y Share Tech Mono (OFL).
