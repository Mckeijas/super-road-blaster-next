# Super Road Blaster Next · ロードブラスター

**Super Road Blaster Next** es un port para **Android** de *Super Road Blaster*, el juego
homebrew de SNES con vídeo MSU-1 basado en el anime *Road Blaster*. Antes se llamaba
simplemente «Super Road Blaster»; ahora se llama **Super Road Blaster Next** (iniciales
**SRBN**), con el «NEXT» en neón sobre el logo y manteniendo el título japonés ロードブラスター.

Es una sola APK con launcher propio, emulación SNES con MSU-1, **restauración HD**,
**audio MSU-1 Enhanced** y **Director's Mode**. Está pensada para consolas Android con mando
y pantalla apaisada (desarrollada y probada en AYN Odin 2 Portal, ARM64) y también se puede
jugar en móviles con los controles táctiles.

**Desde la versión 1.5.0 la APK no lleva el juego**: es solo el launcher y el emulador. El
juego se carga desde la propia app con tu **.zip** de Super Road Blaster («Cargar juego
(.zip)»), y todo lo que antes venía preparado (puntos de inicio de cada escena, miniaturas,
audio Enhanced) lo genera la app en el dispositivo. Ni la APK ni este repositorio contienen
material del juego ni del anime.

```
Android-Port\                     ← repositorio git (puede estar en cualquier carpeta)
├─ android\                       ← proyecto de Android Studio (ábrelo a él)
│  └─ keystore\srbn.keystore      ← clave de firma (¡copia de seguridad! no se sube a git)
├─ tools\                         ← harness de pruebas en PC, scripts e icono
├─ README.md                      ← portada para GitHub
└─ LEEME.md
```

Para compilar **no hace falta ningún archivo del juego**.

---

## 1. Compilar la APK

Necesitas **Android Studio** y unos 10 GB libres.

1. Android Studio → **Open** → carpeta `Android-Port\android`.
2. Espera al *Gradle Sync* (descarga Gradle, el plugin de Android, el NDK y CMake).
   - Si aparece «NDK not configured» o «CMake not found»: *Tools ▸ SDK Manager ▸ SDK Tools*,
     marca **NDK (Side by side)** y **CMake**, Apply y repite el sync.
3. **Build ▸ Select Build Variant…** → **release**.
4. **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**.
5. La APK queda en `android\app\build\outputs\apk\release\SuperRoadBlasterNext-v1.8.3-release.apk`
   (unos pocos MB: el juego se carga después desde la app).

Desde terminal, en la carpeta `android`: `gradlew.bat assembleRelease`

### Versiones y nombre de la APK
La versión está en `android/gradle.properties` → `srbVersion=1.8.3`. **Súbela antes de cada
build que vayas a instalar** (1.6.0 → 1.6.1 para arreglos, 1.7.0 para novedades). La APK sale
con nombre `SuperRoadBlasterNext-v<versión>-release.apk`, y Android muestra esa versión en
*Ajustes ▸ Apps ▸ Super Road Blaster Next*. El número interno (versionCode) se calcula solo
(1.6.0 → 10600); Android no permite instalar una versión con número menor que la instalada.

### Aviso «app potencialmente insegura» (Play Protect)
Sale con cualquier APK instalada fuera de Google Play que Google no conozca; no depende del
nombre ni de la versión. Pulsa **Más detalles ▸ Instalar de todos modos**. Si te ofrece
«Enviar la app para análisis», puedes decir que no.
Mientras la firma (`srbn.keystore`) y el nombre de paquete no cambien, las actualizaciones
se instalan encima sin problemas.

### Firma fija: actualizar sin perder partidas
Todas las compilaciones se firman con `android\keystore\srbn.keystore`, así que cada APK
nueva **se instala encima de la anterior conservando partidas y ajustes**. Guarda una copia
de ese archivo fuera del PC (no se sube a git). Si se perdiera, Android rechazaría las
actualizaciones y habría que desinstalar la app, lo que borra sus datos.

## 2. Instalar

- Copia `SuperRoadBlasterNext-v….apk` al dispositivo, ábrela con el gestor de archivos y permite
  «instalar apps desconocidas», o bien `adb install -r SuperRoadBlasterNext-v1.8.3-release.apk`.
- Aparece como **Super Road Blaster Next**, con el icono SRBN.

### Primera vez: cargar el juego
1. Copia al dispositivo el **.zip** de la versión publicada de Super Road Blaster. Debe
   contener `SuperRoadBlaster.sfc`, `SuperRoadBlaster.msu` y `SuperRoadBlaster-0.pcm` …
   `SuperRoadBlaster-121.pcm` (da igual que estén dentro de una carpeta del zip).
2. Abre la app → **Cargar juego (.zip)** → elige el archivo.
3. **Paso 1 de 2**: la app copia y comprueba los archivos (hace falta ~1 GB libre).
4. **Paso 2 de 2**: la app recorre la aventura en modo automático para crear los puntos de
   inicio del selector de escenas y las miniaturas. Tarda uno o dos minutos y solo se hace una
   vez. Deja la pantalla encendida; si se interrumpe, se reanuda al volver a abrir la app.

Puedes borrar el .zip después. Para cambiar de copia, vuelve a usar **Cargar juego (.zip)**.
Al actualizar la app a una versión nueva no hace falta volver a cargarlo.

## 3. El launcher

Al abrir la app sale una intro animada (sol ochentero, carretera de neón, logo cromado);
pulsa **Start** para entrar. Se puede desactivar en *Controles ▸ Intro al abrir*.

| Menú principal | Qué hace |
|---|---|
| Continuar | Retoma la última partida exactamente donde la dejaste |
| Nueva partida | Arranque normal: logo, título y juego |
| Récords | Las 5 mejores puntuaciones (el «Hall of Fame» del juego) |
| Director's Mode | Ver la película completa, QTE automático/asistido, invencibilidad, cámara lenta, marcador y **selector de escenas** |
| Gráficos | Filtro con vista previa en vivo y comparación en pantalla partida |
| Audio | MSU-1 Enhanced / Original / Alternativa, volúmenes, sonidos del menú |
| Controles | Distribución de botones, **controles táctiles**, vibración, intro |
| Pack HD | Instalar, activar o desinstalar el add-on de vídeo en alta definición |
| Cargar juego (.zip) | Importar o sustituir tu copia del juego |
| Salir | Cerrar la app |

Fondo con montaje de escenas del anime, vistas previas animadas (≈ 5 s de cada escena,
grabadas en el propio dispositivo al preparar las escenas), cursor animado y sonidos
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

**Récord con «!»**: si terminas el juego entero desde *Nueva partida* sin ninguna ayuda del
Director's Mode (QTE asistido o automático, invencibilidad, cámara lenta, ver la película) y sin
cargar partidas, usar el selector de escenas ni reiniciar escena, tus iniciales llevan una **!**
al introducirlas y en el *Hall of Fame* (por ejemplo `01.MCK!:0724000`), y también en *Récords*
del launcher. Puedes perder vidas: si llegas al game over, la partida acaba y la siguiente
empieza de cero. *Continuar* retoma la misma partida, así que no la invalida. Al terminar de
poner las iniciales aparece una imagen especial de agradecimiento (A, B, START o tocar la
pantalla para seguir).

**Puntuación justa**: algunas maniobras piden dos cosas a la vez (por ejemplo, frenar y girar).
En el juego original cada parte puntuaba al hacerla aunque la otra fallara y acabaras chocando;
aquí, si la maniobra termina en choque, los puntos de esa maniobra no cuentan.

### Gráficos · restauración HD
- **Restauración HD**: limpieza del ruido del códec MSU-1 → reescalado con reconstrucción
  de bordes **AMD FSR 1.0 EASU** → nitidez adaptativa y refuerzo del trazo del anime.
- **Restauración HD+**: más agresiva, elimina los bloques en escenas oscuras.
- **Original nítido**, **Suavizado**, **CRT clásico** y **color remasterizado**.
- Formato 4:3 original, pantalla completa o zoom.

### Pack HD (add-on opcional)
Sustituye el vídeo de la aventura por la **película en alta definición** (1440×1080, 60 fps)
**sin cambiar la jugabilidad**: aceleras, frenas y giras igual, y el juego sigue decidiendo
aciertos y choques. Se instala aparte, ocupa algo más de 1 GB y se puede borrar cuando quieras.

- La película va **sincronizada con el audio MSU-1** de cada escena: cámara lenta, avance
  rápido, pausas y saltos del selector de escenas la siguen solos.
- Cuando aparece el **salpicadero** de la versión HD, muestra **tus puntos y tus vidas** en su
  pantalla digital, se borran los iconos de botones de la versión original y la **palanca de la
  derecha sube al acelerar y baja al frenar**.
- Los **avisos** (flechas, freno ✕ y turbo) salen en la pantalla verde del salpicadero con el
  estilo de la versión HD, pero **con los tiempos del juego**: la app lee del propio juego qué
  avisos tiene en pantalla, así que se encienden y se apagan en el mismo instante que las
  flechas rojas de SNES. Los avisos que traía grabados la película (seguían otra versión) quedan
  tapados también en los planos en los que el salpicadero se desplaza. En los planos sin
  salpicadero, la pantalla verde aparece sola mientras haya un aviso.
- La película del pack se recodifica para que cualquier salto del juego (reanudar tras un
  choque, selector de escenas, cargar partida, cambio de clip) muestre el fotograma correcto al
  momento: un fotograma clave cada medio segundo y en cada inicio de capítulo. En los capítulos
  con película nunca aparece el vídeo de SNES (ni su salpicadero ni sus flechas rojas): si la
  película aún está colocándose, la imagen queda en negro ese instante. Tras un choque, además,
  la película queda preparada en el punto donde se reanuda mientras se ve la escena del choque.
  Los retoques del salpicadero siguen el fotograma que hay en pantalla. La palanca se recorta
  por las líneas del propio dibujo y se coloca con su propio seguimiento (el salpicadero de la
  película no se mueve como una pieza rígida), así que no se notan bordes al girar ni al frenar.
- Escenas de choque, logos y pantallas propias del juego usan el vídeo original.

**Crear el pack** (en el PC, una sola vez, con tu propia copia de la película HD; hacen falta
Python 3 con numpy y opencv-python, y ffmpeg con libx264; recodificar la película lleva unos
20-40 minutos):

```
python tools\hdpack\make_hdpack.py all <pelicula_hd.mp4> <carpeta_juego_con_pcm> <trabajo> SRBN-PackHD.zip
```

Copia `SRBN-PackHD.zip` al dispositivo y elige *Pack HD ▸ Instalar el pack HD*. Es el único
archivo del pack: si más adelante lo regeneras con datos nuevos, vuelve a elegirlo en *Reinstalar
el pack HD*; si la película es la misma que ya tienes instalada no se copia otra vez y la
actualización tarda unos segundos. El
interruptor *Usar el vídeo HD* lo activa o desactiva al entrar en la partida.

### Audio · MSU-1 Enhanced
El audio de las 122 pistas se mejora **en tiempo real** sobre tus archivos originales:
volumen igualado entre escenas (-14 LUFS; antes había hasta 20 dB de diferencia),
ecualización ligera y un limitador de picos que evita la saturación. No se guarda ningún
audio aparte. La sincronía con el vídeo es exacta. Salida a 48 kHz.

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
| Atrás | Menú completo: escenas, Director's Mode, gráficos, audio |
| L2 / R2 (mantener) | Cámara lenta / avance rápido |
| R3 | Mostrar u ocultar marcador |

### Controles táctiles (móviles)
En *Controles ▸ Controles táctiles*: **Automático** (aparecen al tocar la pantalla y se ocultan
al usar un botón físico), **Siempre** o **Desactivados**. Son botones flotantes muy
transparentes y con la misma disposición que la Odin 2 Portal: **◀ ▶** (girar) a la
izquierda; a la derecha **B** (posición derecha, acelerar) y **A** (posición inferior,
frenar); **START** abajo y **❚❚** (menú de pausa) arriba a la derecha. La transparencia se ajusta en
*Transparencia de los botones*. Todos los menús funcionan también tocando la pantalla.

En la pantalla de título y en la de récords, Start funciona como en el juego original.
En *Controles* puedes cambiar a «Por etiqueta» si prefieres que el botón con la letra A sea
la A de SNES. La partida se guarda sola al salir o al pausar el dispositivo.

**Fallo del juego original corregido**: tras un choque, si al reanudar llegaba enseguida una
maniobra doble (por ejemplo frenar y girar a la derecha ante el camión de la fase 3), el juego
se quedaba sin paletas de color para los iconos y se detenía con la pantalla en negro. La app lo
corrige en memoria (tu archivo del juego no se toca). Además nunca guarda una partida con el
juego detenido, y si una partida antigua quedó así, *Continuar* empieza desde el inicio de ese
tramo.

## 5. Si algo falla

- **La app se cierra al empezar**: con el dispositivo por ADB,
  `adb logcat -s SRBPort SRBGame SRBRenderer SRBEmu`.
- **«Al .zip le falta…»** al cargar el juego: el zip no tiene la ROM, el `.msu` o alguna
  pista `.pcm`. Usa el paquete completo de la versión publicada.
- **El selector de escenas sale vacío**: el paso 2 no terminó. Abre la app de nuevo y deja
  que acabe.
- **Falta de memoria al compilar**: sube `org.gradle.jvmargs=-Xmx4g` a `-Xmx6g`.
- **Android pide desinstalar para actualizar**: la APK no se firmó con `srbn.keystore`;
  no aceptes y revisa que el archivo esté en `android\keystore\`.

## 6. Repositorio git

Esta carpeta es un repositorio git **apto para publicarse**: no contiene la ROM, el `.msu`,
los `.pcm`, vídeo, imágenes ni audio del anime, ni las APK, la clave de firma o los archivos
de compilación. Los clips y miniaturas de versiones anteriores se han borrado también del
historial. La portada de GitHub es `README.md`.

## Licencias y uso

- Juego Super Road Blaster © snesdev0815 (código MIT), basado en *Road Blaster* (Data East,
  1985; animación de Toei Animation). El vídeo y el audio del juego tienen derechos de
  terceros y **no se incluyen**: cada usuario carga su propia copia.
- Snes9x (licencia Snes9x, no comercial) · AMD FSR 1.0 (MIT) · Sonic (Apache 2.0) ·
  dr_flac (dominio público / MIT-0) · fuentes Chakra Petch, Russo One, Share Tech Mono y
  Kaushan Script (OFL). Sonidos del menú e icono SRBN creados para este port.
