// Super Road Blaster Next - launcher & pause menu (controller driven).
// window.SRB_MODE = 'launcher' | 'pause'. Talks to Java through window.Android.
(function () {
  'use strict';
  const MODE = window.SRB_MODE || 'launcher';
  const PAUSE = MODE === 'pause';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const pad3 = (n) => String(n).padStart(3, '0');

  // ================================================================ bridge
  const Bridge = window.Android || makeMockBridge();
  function makeMockBridge() {
    // Browser preview: settings in localStorage, data from relative files.
    const defaults = { filter: 'hd', color: '1', aspect: '43', audio: 'enhanced', msuVol: '100', sfxVol: '100',
      qte: '0', lives: '0', slowSpeed: '0.5', slowmo: '0', hud: '1', layout: 'snes', rumble: '1', uiSound: '1', intro: '1' };
    let chapters = [];
    const x = new XMLHttpRequest();
    x.open('GET', '../game/chapters.json', false);
    try { x.send(); chapters = JSON.parse(x.responseText); } catch (e) { chapters = []; }
    const get = () => { try { return Object.assign({}, defaults, JSON.parse(localStorage.getItem('srb') || '{}')); } catch (e) { return defaults; } };
    return {
      getState: () => JSON.stringify({ settings: get(), chapters, hasResume: true, resumeChapter: '38', gameComplete: true,
        battery: 76, charging: false,
        soundtrackDir: '/storage/emulated/0/Android/data/com.srbport.roadblaster/files/banda_sonora',
        slots: [{ slot: 1, exists: true, date: '20/10/2026 21:04', chapter: '37' }, { slot: 2, exists: true, date: '20/10/2026 22:10', chapter: '94' }, { slot: 3, exists: false }],
        extra: PAUSE ? { inGame: true, chapter: 37 } : undefined }),
      setSetting: (k, v) => { const s = get(); s[k] = v; try { localStorage.setItem('srb', JSON.stringify(s)); } catch (e) {} },
      action: (a, arg) => { console.log('action', a, arg); },
      vibrate: () => {},
      resetSettings: () => { try { localStorage.removeItem('srb'); } catch (e) {} },
    };
  }

  let S = {};
  function loadState() {
    try { S = JSON.parse(Bridge.getState()); } catch (e) { S = {}; }
    S.settings = S.settings || {};
    S.chapters = S.chapters || [];
    S.byId = {};
    S.chapters.forEach((c) => { S.byId[c.id] = c; });
  }
  const setting = (k) => S.settings[k];
  function setSetting(k, v) {
    S.settings[k] = String(v);
    Bridge.setSetting(k, String(v));
  }
  const thumb = (id) => 'thumbs/' + pad3(id) + '.jpg';
  const clip = (id) => 'clips/' + pad3(id) + '.mp4';
  const hasClip = (id) => S.byId[id] && S.byId[id].hasState;

  // ================================================================ sounds
  const Sfx = {
    ctx: null, bufs: {}, ready: false,
    async init() {
      try {
        this.ctx = new (window.AudioContext || window.webkitAudioContext)();
        for (const n of ['move', 'tab', 'toggle', 'confirm', 'back', 'error', 'start', 'open']) {
          const url = Bridge.readAssetDataUrl ? Bridge.readAssetDataUrl('ui/sfx/' + n + '.wav') : 'sfx/' + n + '.wav';
          const ab = await (await fetch(url)).arrayBuffer();
          this.bufs[n] = await this.ctx.decodeAudioData(ab);
        }
        this.ready = true;
      } catch (e) { console.warn('sfx', e); }
    },
    play(n, vol) {
      if (!this.ready || setting('uiSound') === '0' || !this.bufs[n]) return;
      try {
        if (this.ctx.state !== 'running') this.ctx.resume();
        const s = this.ctx.createBufferSource();
        const g = this.ctx.createGain();
        g.gain.value = vol === undefined ? 0.7 : vol;
        s.buffer = this.bufs[n];
        s.connect(g).connect(this.ctx.destination);
        s.start();
      } catch (e) {}
    },
  };

  // ================================================================ icons
  const ICON = {
    play: '<path d="M7 4l13 8-13 8z"/>',
    spark: '<path d="M12 2v6M12 16v6M2 12h6M16 12h6M5 5l3.5 3.5M15.5 15.5L19 19M19 5l-3.5 3.5M8.5 15.5L5 19"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    clap: '<path d="M4 9h16v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"/><path d="M4 9l2-5 14 3-1 2M9 4.8l1.5 3.7M14 5.9l1.5 3.1"/>',
    screen: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/><path d="M6 13l3-4 3 3 2-2 3 3"/>',
    wave: '<path d="M2 12h2M6 8v8M10 4v16M14 7v10M18 10v4M22 12h0"/>',
    pad: '<path d="M6 8h12a4 4 0 0 1 4 4v2a3 3 0 0 1-5.5 1.7L15 14H9l-1.5 1.7A3 3 0 0 1 2 14v-2a4 4 0 0 1 4-4z"/><path d="M7 11v3M5.5 12.5h3M16 11.5h.01M18 13.5h.01"/>',
    disk: '<path d="M5 3h11l3 3v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M7 3v5h8V3M7 21v-7h10v7"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/>',
    power: '<path d="M12 3v9"/><path d="M6.3 7a8 8 0 1 0 11.4 0"/>',
    restart: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
    down: '<path d="M12 3v12M7 10l5 5 5-5M4 21h16"/>',
    up: '<path d="M12 21V9M7 14l5-5 5 5M4 3h16"/>',
    home: '<path d="M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
  };
  const svg = (k) => '<svg viewBox="0 0 24 24">' + (ICON[k] || '') + '</svg>';

  // ================================================================ options
  const OPTS = {
    qte: { label: 'QTE automático', desc: 'Asistido: te rescata en el último instante. Automático: el coche se conduce solo.',
      values: [['0', 'Desactivado'], ['1', 'Asistido'], ['2', 'Automático']] },
    lives: { label: 'Invencibilidad', desc: 'Vidas infinitas: los choques nunca terminan la partida.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
    slowmo: { label: 'Cámara lenta permanente', desc: 'También puedes mantener L2 durante la partida. R2: avance rápido ×2.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
    slowSpeed: { label: 'Velocidad de cámara lenta', desc: 'El audio se estira sin cambiar el tono.', values: [['0.75', '75 %'], ['0.5', '50 %'], ['0.35', '35 %'], ['0.25', '25 %']] },
    hud: { label: 'Marcador e indicaciones', desc: 'Puntos y flechas en pantalla. R3 lo alterna durante la partida.', values: [['0', 'Ocultos'], ['1', 'Visibles']], sw: true },
    filter: { label: 'Filtro de imagen', desc: '', values: [['original', 'Original nítido'], ['smooth', 'Suavizado'], ['hd', 'Restauración HD'], ['hd_strong', 'Restauración HD+'], ['crt', 'CRT clásico']] },
    color: { label: 'Color remasterizado', desc: 'Negros más limpios, más contraste y saturación.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
    aspect: { label: 'Formato de pantalla', desc: '4:3 respeta la imagen original; Zoom llena el ancho recortando arriba y abajo.', values: [['43', '4:3 original'], ['fill', 'Pantalla completa'], ['zoom', 'Zoom']] },
    audio: { label: 'Banda sonora', desc: '', values: [['enhanced', 'MSU-1 Enhanced'], ['original', 'Original'], ['custom', 'Alternativa']] },
    msuVol: { label: 'Volumen de vídeo y música', desc: 'Pista MSU-1 (diálogos, música y efectos del anime).', slider: [0, 150, 10] },
    sfxVol: { label: 'Volumen de efectos', desc: 'Sonidos de la consola (aciertos, giros, alarmas).', slider: [0, 150, 10] },
    uiSound: { label: 'Sonidos del menú', desc: 'Efectos al navegar por el launcher y la pausa.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
    intro: { label: 'Intro al abrir', desc: 'Pantalla de título animada al arrancar la app.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
    layout: { label: 'Distribución de botones', desc: '', values: [['snes', 'Por posición (SNES)'], ['xbox', 'Por etiqueta']] },
    rumble: { label: 'Vibración', desc: 'Vibra con los impactos del anime.', values: [['0', 'No'], ['1', 'Sí']], sw: true },
  };
  const FILTER_DESC = {
    original: 'Píxeles nítidos y limpios, tal como los dibuja la SNES, sin parpadeo al escalar.',
    smooth: 'Escalado bilineal suave.',
    hd: 'Limpia el ruido del códec MSU-1, reconstruye bordes con AMD FSR y afina el trazo del anime.',
    hd_strong: 'Más agresiva: elimina los bloques de compresión en escenas oscuras y marca más la línea.',
    crt: 'Líneas de barrido y máscara de fósforo de un televisor de tubo.',
  };
  const AUDIO_DESC = {
    enhanced: 'Pistas remasterizadas: sin saturación, ruido reducido y volumen equilibrado entre escenas.',
    original: 'El audio MSU-1 tal como se publicó.',
    custom: 'Tus propios SuperRoadBlaster-N.pcm o .flac de la carpeta indicada abajo.',
  };

  function optRow(key) {
    const o = OPTS[key];
    const v = setting(key);
    let val;
    if (o.slider) {
      const [mn, mx, st] = o.slider;
      const n = Math.round((+v - mn) / st), tot = Math.round((mx - mn) / st);
      let pips = '';
      for (let i = 1; i <= tot; i++) pips += '<i class="' + (i <= n ? 'on' : '') + '"></i>';
      val = '<span class="arr">◀</span><span class="pips">' + pips + '</span><span class="txt">' + v + ' %</span><span class="arr">▶</span>';
    } else if (o.sw) {
      const on = v === '1';
      val = '<span class="txt">' + (on ? 'Sí' : 'No') + '</span><span class="switch' + (on ? ' on' : '') + '"></span>';
    } else {
      const f = o.values.find((x) => x[0] === v) || o.values[0];
      val = '<span class="arr">◀</span><span class="txt">' + esc(f[1]) + '</span><span class="arr">▶</span>';
    }
    let desc = o.desc;
    if (key === 'filter') desc = FILTER_DESC[v] || '';
    if (key === 'audio') desc = AUDIO_DESC[v] || '';
    return '<div class="opt" data-f data-opt="' + key + '"><div class="lbl">' + esc(o.label) +
      '<small>' + esc(desc) + '</small></div><div class="val">' + val + '</div></div>';
  }
  function actionRow(id, label, desc, value) {
    return '<div class="opt action" data-f data-act="' + id + '"><div class="lbl">' + esc(label) +
      '<small>' + esc(desc || '') + '</small></div><div class="val">' + esc(value || 'Ⓐ') + '</div></div>';
  }
  function cycle(key, dir) {
    const o = OPTS[key];
    const v = setting(key);
    let nv;
    if (o.slider) {
      const [mn, mx, st] = o.slider;
      nv = Math.max(mn, Math.min(mx, (+v || 0) + dir * st));
      if (String(nv) === v) { Sfx.play('error', 0.3); return; }
    } else {
      const i = o.values.findIndex((x) => x[0] === v);
      nv = o.values[(Math.max(0, i) + dir + o.values.length) % o.values.length][0];
    }
    setSetting(key, nv);
    Sfx.play('toggle', 0.5);
    Bridge.vibrate && Bridge.vibrate(8);
    // update the row in place (keeps focus, plays a small slide)
    const el = $('main.panel .opt[data-opt="' + key + '"]');
    if (el) {
      const tmp = document.createElement('div');
      tmp.innerHTML = optRow(key);
      const nr = tmp.firstChild;
      nr.classList.add('focus');
      el.replaceWith(nr);
      const t = $('.txt', nr);
      if (t) { t.style.setProperty('--d', (dir > 0 ? 0.6 : -0.6) + 'em'); t.classList.add('bump'); }
    }
    if (key === 'filter' || key === 'color' || key === 'aspect') drawPreview();
  }

  // ================================================================ sections
  const SECTIONS = [];
  function buildSections() {
    SECTIONS.length = 0;
    const add = (o) => SECTIONS.push(o);
    if (PAUSE) {
      add({ id: 'resume', ico: 'play', label: 'Continuar', sub: 'Volver a la carretera', act: () => Bridge.action('resume', '') });
      add({ id: 'restart', ico: 'restart', label: 'Reiniciar escena', sub: 'Desde el inicio del capítulo', act: () => Bridge.action('restart', '') });
      add({ id: 'save', ico: 'down', label: 'Guardar partida', panel: 'slots', slotMode: 'save' });
      add({ id: 'load', ico: 'up', label: 'Cargar partida', panel: 'slots', slotMode: 'load' });
      add({ id: 'scenes', ico: 'grid', label: 'Selector de escenas', panel: 'scenes' });
      add({ id: 'director', ico: 'clap', label: "Director's Mode", panel: 'director' });
      add({ id: 'graphics', ico: 'screen', label: 'Gráficos', panel: 'graphics' });
      add({ id: 'audio', ico: 'wave', label: 'Audio', panel: 'audio' });
      add({ id: 'menu', ico: 'home', label: 'Salir al launcher', sub: 'Vuelve al menú principal · la partida queda guardada', act: () => Bridge.action('menu', '') });
    } else {
      if (S.hasResume) add({ id: 'resume', ico: 'play', label: 'Continuar', sub: 'Donde lo dejaste', act: () => launch('resume'), hero: 'resume' });
      add({ id: 'new', ico: 'spark', label: 'Nueva partida', sub: 'Desde el encendido de la consola', act: () => launch('new'), hero: 'new' });
      add({ id: 'scenes', ico: 'grid', label: 'Selector de escenas', panel: 'scenes' });
      add({ id: 'director', ico: 'clap', label: "Director's Mode", panel: 'director' });
      add({ id: 'graphics', ico: 'screen', label: 'Gráficos', panel: 'graphics' });
      add({ id: 'audio', ico: 'wave', label: 'Audio', panel: 'audio' });
      add({ id: 'controls', ico: 'pad', label: 'Controles', panel: 'controls' });
      add({ id: 'load', ico: 'disk', label: 'Partidas guardadas', panel: 'slots', slotMode: 'load' });
      add({ id: 'quit', ico: 'power', label: 'Salir', act: () => Bridge.action('quit', '') });
    }
  }

  function launch(arg) {
    Sfx.play('start', 0.8);
    const f = $('.flash');
    if (f) { f.classList.remove('go'); void f.offsetWidth; f.classList.add('go'); }
    setTimeout(() => Bridge.action('play', arg), 650);
  }

  // ================================================================ UI state
  const ui = { sec: 0, zone: 'menu', pidx: 0, tab: 1, compare: false, pimg: 0, intro: false };
  let fx = null, fxImgs = [];
  let panelKey = '';

  function sceneTabs() {
    const tabs = [{ name: 'Prólogo', short: 'P', filter: (c) => c.level === 0 && c.kind !== 'crash' }];
    for (let l = 1; l <= 9; l++) tabs.push({ name: 'Nivel ' + l, short: String(l), filter: (c) => c.level === l });
    return tabs;
  }
  function sceneList() {
    const t = sceneTabs()[ui.tab];
    const all = S.chapters.filter((c) => t.filter(c));
    return { story: all.filter((c) => c.kind !== 'crash'), crash: all.filter((c) => c.kind === 'crash') };
  }

  // ---------------------------------------------------------------- render
  function renderMenu() {
    let m = '<div class="section-title">' + (PAUSE ? 'Pausa' : 'Menú') + '</div><div class="cursor"></div>';
    SECTIONS.forEach((s, i) => {
      m += '<div class="item" data-i="' + i + '">' + svg(s.ico) + '<div>' + esc(s.label) +
        (s.sub ? '<small>' + esc(s.sub) + '</small>' : '') + '</div></div>';
    });
    $('nav.menu').innerHTML = m;
    $$('nav.menu .item').forEach((el) => {
      el.onclick = () => { const i = +el.dataset.i; if (i !== ui.sec) { ui.sec = i; ui.zone = 'menu'; sync(true); } press('A'); };
    });
  }

  function renderPanel(animate) {
    const sec = SECTIONS[ui.sec];
    const p = $('main.panel');
    p.innerHTML = panelHtml(sec);
    if (animate) { p.classList.remove('enter'); void p.offsetWidth; p.classList.add('enter'); }
    panelKey = sec.id + ':' + ui.tab + ':' + ui.compare;
    focusables().forEach((el, i) => { el.onclick = () => { ui.zone = 'panel'; ui.pidx = i; updateFocus(); press('A'); }; });
    $$('video', p).forEach(wireVideo);
    if (sec.panel === 'graphics' && !PAUSE) drawPreview();
  }

  // full sync: rebuild the panel if its content changed, then move the focus
  function sync(animate) {
    const sec = SECTIONS[ui.sec];
    const key = sec.id + ':' + ui.tab + ':' + ui.compare;
    if (key !== panelKey) renderPanel(animate);
    updateFocus();
  }

  function updateFocus() {
    // side menu + sliding cursor
    const items = $$('nav.menu .item');
    items.forEach((el, i) => {
      el.classList.toggle('focus', ui.zone === 'menu' && i === ui.sec);
      el.classList.toggle('sel', ui.zone !== 'menu' && i === ui.sec);
    });
    const cur = $('nav.menu .cursor');
    const it = items[ui.sec];
    if (cur && it) {
      const place = () => {
        cur.style.transform = 'translateY(' + it.offsetTop + 'px)';
        cur.style.height = it.offsetHeight + 'px';
      };
      requestAnimationFrame(place);
      clearTimeout(cur._t);
      cur._t = setTimeout(place, 280); // after the subtitle finished expanding
      items.forEach((x) => { x.ontransitionend = (x === it) ? place : null; });
      cur.classList.toggle('dim', ui.zone !== 'menu');
      it.scrollIntoView({ block: 'nearest' });
    }
    // panel
    const fs = focusables();
    fs.forEach((el) => el.classList.remove('focus'));
    if (ui.zone === 'panel') {
      if (!fs.length) ui.zone = 'menu';
      else {
        ui.pidx = Math.max(0, Math.min(ui.pidx, fs.length - 1));
        fs[ui.pidx].classList.add('focus');
        fs[ui.pidx].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    }
    onFocusChanged();
    updateFooter();
  }

  function focusables() { return $$('main.panel [data-f]'); }

  function wireVideo(v) {
    v.muted = true;
    v.loop = true;
    v.playsInline = true;
    v.addEventListener('canplay', () => { v.style.opacity = 1; }, { once: true });
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
  }

  // ---------------------------------------------------------------- panels
  function panelHtml(sec) {
    if (!PAUSE && !S.gameComplete) {
      return '<div class="error-box"><h2>Faltan los datos del juego</h2><p>Esta APK se compiló sin los archivos de ' +
        'Super Road Blaster (ROM, .msu y .pcm). Indica su carpeta en <b>gradle.properties</b> (srbGameDir) y vuelve a compilar.</p></div>';
    }
    switch (sec.panel) {
      case 'director': return panelDirector();
      case 'graphics': return panelGraphics();
      case 'audio': return panelAudio();
      case 'controls': return panelControls();
      case 'scenes': return panelScenes();
      case 'slots': return panelSlots(sec.slotMode);
      case 'credits': return panelCredits();
      default: return PAUSE ? panelPause(sec) : panelHero(sec);
    }
  }

  function heroShot(id, kicker, title, text, chips) {
    const media = hasClip(id) ? '<video src="' + clip(id) + '" poster="' + thumb(id) + '" style="opacity:0;transition:opacity .5s"></video>'
      : '<img src="' + thumb(id) + '">';
    return '<div class="shot">' + (hasClip(id) ? '<img src="' + thumb(id) + '" style="position:absolute;inset:0">' : '') + media +
      '<div class="cap"><div class="kicker">' + esc(kicker) + '</div><h2>' + esc(title) + '</h2>' +
      (text ? '<p>' + esc(text) + '</p>' : '') + (chips ? '<div class="meta">' + chips + '</div>' : '') + '</div></div>';
  }

  function directorChips() {
    const c = [];
    const q = setting('qte');
    if (q === '1') c.push('<span class="chip hot">QTE asistido</span>');
    if (q === '2') c.push('<span class="chip hot">QTE automático</span>');
    if (setting('lives') === '1') c.push('<span class="chip hot">Invencible</span>');
    if (setting('slowmo') === '1') c.push('<span class="chip hot">Cámara lenta</span>');
    c.push('<span class="chip">' + esc((OPTS.filter.values.find((x) => x[0] === setting('filter')) || ['', ''])[1]) + '</span>');
    c.push('<span class="chip">' + (setting('audio') === 'enhanced' ? 'MSU-1 Enhanced' : setting('audio') === 'custom' ? 'Banda sonora alternativa' : 'Audio original') + '</span>');
    return c.join('');
  }

  function panelHero(sec) {
    if (sec.hero === 'resume') {
      const ch = S.byId[+S.resumeChapter];
      return '<div class="hero">' + heroShot(ch ? ch.id : 37, 'Continuar', ch ? ch.title : 'Tu partida',
        'Retoma la persecución exactamente donde la dejaste, con tus puntos y tus vidas.', directorChips()) + '</div>';
    }
    if (sec.hero === 'new') return '<div class="hero">' + heroShot(3, 'Nueva partida', 'Arranca el motor',
      'Desde el encendido de la consola: logo, pantalla de título y nueve niveles de persecución.', directorChips()) + '</div>';
    if (sec.hero === 'movie') return '<div class="hero">' + heroShot(112, 'Modo película', 'La aventura completa',
      'De principio a fin sin tocar nada: QTE automático, sin marcador y sin choques. Unos 19 minutos de anime.',
      '<span class="chip hot">≈ 19 min</span><span class="chip">Sin manos</span><span class="chip">' + (setting('audio') === 'enhanced' ? 'MSU-1 Enhanced' : 'Audio original') + '</span>') + '</div>';
    return '<div class="hero">' + heroShot(2, sec.label, sec.label, '') + '</div>';
  }

  function panelPause() {
    const ch = S.extra && S.byId[S.extra.chapter];
    let prog = '', kicker = 'En pausa';
    if (ch) {
      kicker = ch.level ? 'Nivel ' + ch.level : 'Prólogo';
      const story = S.chapters.filter((c) => c.level === ch.level && c.kind === 'story');
      const idx = story.findIndex((c) => c.id === ch.id);
      if (story.length > 1) {
        prog = '<div class="progress">' + story.map((c, i) => '<i class="' + (i < idx ? 'done' : i === idx ? 'now' : '') + '"></i>').join('') + '</div>';
      }
      if (ch.kind === 'crash') kicker += ' · escena de choque';
    }
    return '<div class="pauseinfo"><div class="kicker">' + esc(kicker) + '</div><h2>' + esc(ch ? ch.title.replace(/^Nivel \d · /, '') : 'Pausa') + '</h2>' +
      prog + '<div class="chips">' + directorChips() + '</div></div>';
  }

  function panelDirector() {
    let h = '<div class="section-title">Director\'s Mode</div><div class="opts stagger">';
    if (!PAUSE) h += actionRow('movie', 'Ver la película completa', 'La aventura entera desde el prólogo, sin manos: QTE automático y sin marcador (≈ 19 min).', 'Ⓐ Ver');
    ['qte', 'lives', 'slowmo', 'slowSpeed', 'hud'].forEach((k) => { h += optRow(k); });
    h += actionRow('scenes', 'Selector de escenas', 'Salta a cualquier capítulo, incluidos los choques.', 'Ⓐ Abrir');
    return h + '</div>';
  }

  function panelGraphics() {
    const opts = '<div class="opts stagger">' + optRow('filter') + optRow('color') + optRow('aspect') + '</div>';
    if (PAUSE) return '<div class="section-title">Gráficos</div><div style="width:58%">' + opts +
      '<p class="note">Los cambios se aplican al instante; cierra la pausa para verlos en movimiento.</p></div>';
    return '<div class="section-title">Gráficos</div><div class="gfx">' + opts +
      '<div class="hero"><div class="shot plain" id="shot">' +
      (ui.compare ? '<div class="split-line"></div><span class="tag">Original</span><span class="tag r">Seleccionado</span>'
        : '<span class="tag">Vista previa en tiempo real</span>') + '</div></div></div>';
  }

  function panelAudio() {
    let h = '<div class="section-title">Audio · MSU-1</div><div class="opts stagger">' + optRow('audio') + optRow('msuVol') + optRow('sfxVol') +
      (PAUSE ? '' : optRow('uiSound')) + '</div>';
    h += '<p class="note">Banda sonora alternativa: copia <b>SuperRoadBlaster-N.pcm</b> (MSU-1) o <b>SuperRoadBlaster-N.flac</b> (44,1 kHz) en<br><code>' +
      esc(S.soundtrackDir || '') + '</code>' +
      (PAUSE ? '<br>«Alternativa» se aplica al volver a entrar en la partida; Original y Enhanced, en la siguiente escena.' : '') + '</p>';
    return h;
  }

  function panelControls() {
    const lay = setting('layout') === 'xbox';
    const acc = lay ? 'A' : 'B (derecha)', brk = lay ? 'B' : 'A (abajo)';
    return '<div class="section-title">Controles · AYN Odin 2 Portal</div><div class="opts stagger">' + optRow('layout') + optRow('rumble') + optRow('intro') + '</div>' +
      '<div class="keys">' +
      '<span class="k">Cruceta / stick</span><span>Girar a izquierda o derecha</span>' +
      '<span class="k">Botón ' + acc + '</span><span>Acelerar · turbo (A de SNES)</span>' +
      '<span class="k">Botón ' + brk + '</span><span>Frenar (B de SNES)</span>' +
      '<span class="k">L2 · R2</span><span>Mantener: cámara lenta · avance rápido ×2</span>' +
      '<span class="k">R3</span><span>Mostrar u ocultar el marcador</span>' +
      '<span class="k">Atrás</span><span>Menú de pausa</span></div>';
  }

  function panelScenes() {
    const tabs = sceneTabs();
    const pct = (ui.tab / (tabs.length - 1)) * 100;
    let h = '<div class="route"><span class="lr l">L</span><span class="lr r">R</span>' +
      '<div class="fill" style="width:calc((100% - var(--u) * 5) * ' + (pct / 100) + ')"></div>' +
      tabs.map((t, i) => '<div class="node' + (i === ui.tab ? ' on' : i < ui.tab ? ' past' : '') + '" data-tab="' + i + '"><i>' + t.short + '</i><span>' + esc(t.name) + '</span></div>').join('') +
      '</div><div class="scenes"><div class="hero" id="sprev"></div><div class="slist" id="slist">';
    const { story, crash } = sceneList();
    const card = (c, n) => '<div class="card' + (c.kind === 'crash' ? ' crash' : '') + (c.hasState ? '' : ' locked') + '" data-f data-scene="' + c.id + '">' +
      '<img src="' + (c.hasState ? thumb(c.id) : '') + '"><span class="n">' + n + '</span>' +
      '<div class="cap">' + esc(c.title.replace(/^Nivel \d · /, '')) + '</div></div>';
    h += '<h4>Recorrido</h4><div class="cards">' + story.map((c, i) => card(c, String(i + 1).padStart(2, '0'))).join('') + '</div>';
    if (crash.length) h += '<h4>Choques <b>' + crash.length + '</b></h4><div class="cards">' + crash.map((c, i) => card(c, '×' + (i + 1))).join('') + '</div>';
    return h + '</div></div>';
  }
  const fmtDur = (ms) => { const s = Math.round(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

  let prevTimer = 0, prevId = -1;
  function updateScenePreview() {
    const box = $('#sprev');
    if (!box) return;
    const fs = focusables();
    const el = ui.zone === 'panel' ? fs[ui.pidx] : fs[0];
    if (!el) { box.innerHTML = ''; return; }
    const id = +el.dataset.scene;
    if (id === prevId && box.firstChild) return;
    prevId = id;
    const c = S.byId[id];
    clearTimeout(prevTimer);
    const chips = '<span class="chip' + (c.kind === 'crash' ? ' crash' : ' hot') + '">' + (c.kind === 'crash' ? 'Choque' : c.kind === 'extra' ? 'Extra' : 'Recorrido') + '</span>' +
      '<span class="chip">' + fmtDur(c.durationMs) + '</span><span class="chip">Capítulo ' + c.id + '</span>';
    box.innerHTML = '<div class="shot"><img src="' + thumb(id) + '" style="position:absolute;inset:0"><div class="cap"><div class="kicker">' +
      esc(c.level ? 'Nivel ' + c.level : 'Prólogo') + '</div><h2>' + esc(c.title.replace(/^Nivel \d · /, '')) + '</h2><div class="meta">' + chips +
      '</div><p style="margin-top:.8em">' + (c.hasState ? 'Ⓐ Jugar desde aquí' : 'Escena no disponible') + '</p></div></div>';
    // load the moving preview a moment later, so fast scrolling stays smooth
    prevTimer = setTimeout(() => {
      const shot = $('#sprev .shot');
      if (!shot || prevId !== id || !c.hasState) return;
      const v = document.createElement('video');
      v.src = clip(id);
      v.style.cssText = 'position:absolute;inset:0;opacity:0;transition:opacity .4s';
      shot.insertBefore(v, shot.querySelector('.cap'));
      wireVideo(v);
    }, 180);
  }

  function panelSlots(mode) {
    let h = '<div class="section-title">' + (mode === 'save' ? 'Guardar en…' : 'Cargar partida') + '</div><div class="slots stagger">';
    (S.slots || []).forEach((s) => {
      const ch = s.exists ? S.byId[+s.chapter] : null;
      h += '<div class="slot" data-f data-slot="' + s.slot + '">' +
        '<div class="img' + (s.exists && ch ? '' : ' empty') + '"' + (s.exists && ch ? ' style="background-image:url(' + thumb(ch.id) + ')"' : '') + '></div>' +
        '<div class="meta"><b>Ranura ' + s.slot + '</b><span>' + (s.exists ? esc((ch ? ch.title : 'Partida') + ' · ' + s.date) : (mode === 'save' ? 'Libre' : 'Vacía')) + '</span></div></div>';
    });
    return h + '</div>';
  }

  function panelCredits() {
    return '<div class="section-title">Créditos</div><div class="credits enter">' +
      '<h3>Super Road Blaster</h3>Juego homebrew para SNES por <b>snesdev0815</b> (código bajo licencia MIT), basado en el anime Road Blaster / Road Avenger. Datos de vídeo y audio MSU-1 de la versión publicada.' +
      '<h3>Port Android</h3>Launcher, Director\'s Mode, restauración HD y MSU-1 Enhanced creados para AYN Odin 2 Portal. Uso personal.' +
      '<h3>Emulación</h3>Snes9x (licencia Snes9x, uso no comercial) con soporte MSU-1.' +
      '<h3>Vídeo</h3>AMD FidelityFX Super Resolution 1.0 «EASU» (MIT) vía libretro slang-shaders (Unlicense). Nitidez adaptativa estilo CAS.' +
      '<h3>Audio</h3>Sonic de Bill Cox (Apache 2.0) para la cámara lenta sin cambio de tono; dr_flac de David Reid (dominio público / MIT-0). Sonidos del menú sintetizados para este port.' +
      '<h3>Tipografías</h3>Chakra Petch, Russo One y Share Tech Mono (SIL Open Font License).' +
      '</div>';
  }

  function onFocusChanged() {
    const sec = SECTIONS[ui.sec];
    if (sec.panel === 'scenes') updateScenePreview();
  }

  function updateFooter() {
    const sec = SECTIONS[ui.sec];
    let k = '<span><span class="key a">A</span>Aceptar</span><span><span class="key b">B</span>' + (ui.zone === 'menu' && PAUSE ? 'Volver al juego' : 'Atrás') + '</span>';
    if (ui.zone === 'panel' && $('main.panel .opt.focus[data-opt]')) k += '<span><span class="key">◀ ▶</span>Cambiar</span>';
    if (sec.panel === 'scenes') k += '<span><span class="key">L</span><span class="key">R</span>Nivel</span>';
    if (sec.panel === 'graphics' && !PAUSE) k += '<span><span class="key y">Y</span>Comparar</span><span><span class="key x">X</span>Otra imagen</span>';
    const html = k + '<span class="ver">v1.2 · Snes9x MSU-1 · ARM64</span>';
    const f = $('footer');
    if (f.dataset.h !== html) { f.innerHTML = html; f.dataset.h = html; }
  }

  // ---------------------------------------------------------------- spatial navigation
  function spatial(dir, els, cur) {
    const r0 = els[cur].getBoundingClientRect();
    const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
    let best = -1, bestScore = Infinity;
    els.forEach((el, i) => {
      if (i === cur) return;
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      const dx = x - cx, dy = y - cy;
      let main, side;
      if (dir === 'LEFT') { main = -dx; side = Math.abs(dy); }
      else if (dir === 'RIGHT') { main = dx; side = Math.abs(dy); }
      else if (dir === 'UP') { main = -dy; side = Math.abs(dx); }
      else { main = dy; side = Math.abs(dx); }
      if (main <= 4) return;
      const score = main + side * 2.2;
      if (score < bestScore) { bestScore = score; best = i; }
    });
    return best;
  }

  // ---------------------------------------------------------------- input
  // ---------------------------------------------------------------- in-game pause (START)
  const MINI = [
    { label: 'Continuar', act: () => Bridge.action('resume', '') },
    { label: 'Opciones', act: () => { window.srbOpen(); } },
    { label: 'Salir al menú principal', act: () => Bridge.action('menu', '') },
  ];
  let mini = false, miniIdx = 0;
  function renderMini() {
    const box = $('.mini');
    if (!box) return;
    const ch = S.extra && S.byId[S.extra.chapter];
    box.innerHTML = '<div class="mtitle">PAUSA</div>' +
      (ch ? '<div class="msub">' + esc(ch.title) + '</div>' : '') +
      '<div class="mlist">' + MINI.map((m, i) => '<div class="mitem' + (i === miniIdx ? ' on' : '') + '" data-i="' + i + '">' + esc(m.label) + '</div>').join('') + '</div>' +
      '<div class="mhint"><span class="key a">A</span>Elegir &nbsp;&nbsp; <span class="key">START</span>Continuar</div>';
    $$('.mitem', box).forEach((el) => { el.onclick = () => { miniIdx = +el.dataset.i; pressMini('A'); }; });
  }
  function pressMini(tok) {
    if (tok === 'UP' || tok === 'DOWN') {
      miniIdx = (miniIdx + (tok === 'UP' ? -1 : 1) + MINI.length) % MINI.length;
      Sfx.play('move', 0.45);
      renderMini();
    } else if (tok === 'A') {
      Sfx.play('confirm', 0.6);
      MINI[miniIdx].act();
    } else if (tok === 'B' || tok === 'START') {
      Sfx.play('back', 0.5);
      Bridge.action('resume', '');
    }
  }

  function press(tok) {
    if (mini) { pressMini(tok); return; }
    if (ui.intro) { closeIntro(); return; }
    const sec = SECTIONS[ui.sec];
    if (ui.zone === 'menu') {
      if (tok === 'UP' || tok === 'DOWN') {
        ui.sec = (ui.sec + (tok === 'UP' ? -1 : 1) + SECTIONS.length) % SECTIONS.length;
        Sfx.play('move', 0.45);
        sync(true);
        return;
      }
      if (tok === 'A' || tok === 'RIGHT' || tok === 'START') {
        if (sec.act) { if (tok !== 'RIGHT') { if (PAUSE) Sfx.play('confirm'); sec.act(); } return; }
        if (sec.panel && sec.panel !== 'credits') { ui.zone = 'panel'; ui.pidx = 0; Sfx.play('confirm', 0.5); updateFocus(); }
        return;
      }
      if (tok === 'B') { if (PAUSE) { Sfx.play('back'); Bridge.action('resume', ''); } return; }
      if ((tok === 'L' || tok === 'R') && sec.panel === 'scenes') { switchTab(tok === 'L' ? -1 : 1); return; }
      if (tok === 'X' && sec.panel === 'graphics') { ui.pimg++; Sfx.play('tab'); drawPreview(); return; }
      if (tok === 'Y' && sec.panel === 'graphics') { ui.compare = !ui.compare; Sfx.play('tab'); sync(false); return; }
      return;
    }
    // ---- panel zone
    const fs = focusables();
    const el = fs[ui.pidx];
    if (tok === 'B') { ui.zone = 'menu'; Sfx.play('back', 0.5); updateFocus(); return; }
    if (sec.panel === 'scenes' || sec.panel === 'slots') {
      if (['LEFT', 'RIGHT', 'UP', 'DOWN'].includes(tok)) {
        const n = spatial(tok, fs, ui.pidx);
        if (n < 0) { if (tok === 'LEFT') { ui.zone = 'menu'; Sfx.play('back', 0.4); updateFocus(); } return; }
        ui.pidx = n;
        Sfx.play('move', 0.45);
        updateFocus();
        return;
      }
      if ((tok === 'L' || tok === 'R') && sec.panel === 'scenes') { switchTab(tok === 'L' ? -1 : 1); ui.pidx = 0; updateFocus(); return; }
      if (tok === 'A' && el && sec.panel === 'scenes') {
        const id = +el.dataset.scene;
        if (!(S.byId[id] && S.byId[id].hasState)) { Sfx.play('error'); toast('Escena no disponible'); return; }
        if (PAUSE) { Sfx.play('confirm'); Bridge.action('scene', String(id)); } else launch('scene:' + id);
        return;
      }
      if (tok === 'A' && el && sec.panel === 'slots') {
        const slot = el.dataset.slot;
        const info = (S.slots || []).find((s) => String(s.slot) === slot);
        if (sec.slotMode === 'save') { Sfx.play('confirm'); Bridge.action('save', slot); setTimeout(refresh, 450); return; }
        if (!info || !info.exists) { Sfx.play('error'); toast('Ranura vacía'); return; }
        if (PAUSE) { Sfx.play('confirm'); Bridge.action('load', slot); } else launch('slot:' + slot);
      }
      return;
    }
    // ---- option lists
    if (tok === 'UP' || tok === 'DOWN') {
      const n = Math.max(0, Math.min(fs.length - 1, ui.pidx + (tok === 'UP' ? -1 : 1)));
      if (n !== ui.pidx) { ui.pidx = n; Sfx.play('move', 0.45); updateFocus(); }
      return;
    }
    if ((tok === 'LEFT' || tok === 'RIGHT') && el && el.dataset.opt) { cycle(el.dataset.opt, tok === 'LEFT' ? -1 : 1); updateFooter(); return; }
    if (tok === 'LEFT') { ui.zone = 'menu'; Sfx.play('back', 0.4); updateFocus(); return; }
    if (tok === 'A' && el && el.dataset.opt) { cycle(el.dataset.opt, 1); return; }
    if (tok === 'A' && el && el.dataset.act) {
      if (el.dataset.act === 'movie') { launch('movie'); return; }
      if (el.dataset.act === 'scenes') {
        Sfx.play('confirm');
        ui.sec = SECTIONS.findIndex((s) => s.id === 'scenes'); ui.zone = 'panel'; ui.pidx = 0;
        sync(true);
      }
      return;
    }
    if (tok === 'X' && sec.panel === 'graphics') { ui.pimg++; Sfx.play('tab'); drawPreview(); return; }
    if (tok === 'Y' && sec.panel === 'graphics') { ui.compare = !ui.compare; Sfx.play('tab'); const keep = ui.pidx; sync(false); ui.pidx = keep; updateFocus(); }
  }

  function switchTab(d) {
    const n = sceneTabs().length;
    ui.tab = (ui.tab + d + n) % n;
    Sfx.play('tab', 0.55);
    prevId = -1;
    sync(false);
    const g = $('#slist');
    if (g) { g.classList.remove('enter'); void g.offsetWidth; g.classList.add('enter'); }
  }

  // ---------------------------------------------------------------- graphics preview
  let fxCanvas = null, fxInit = null;
  async function drawPreview() {
    const shot = document.getElementById('shot');
    if (!shot || !window.SrbFx) return;
    if (!fxCanvas) { fxCanvas = document.createElement('canvas'); fxCanvas.id = 'fx'; }
    if (fxCanvas.parentNode !== shot) shot.insertBefore(fxCanvas, shot.firstChild);
    const canvas = fxCanvas;
    const box = shot.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const W = Math.max(2, Math.round(box.width * dpr)), H = Math.max(2, Math.round(box.height * dpr));
    if (canvas.width !== W || canvas.height !== H) { canvas.width = W; canvas.height = H; }
    try {
      if (!fxInit) { fx = new SrbFx(canvas); fxInit = fx.init(); }
      await fxInit;
      const idx = ui.pimg % 4;
      if (!fxImgs[idx]) {
        const img = new Image();
        img.src = Bridge.readAssetDataUrl ? Bridge.readAssetDataUrl('ui/preview/p' + idx + '.png') : 'preview/p' + idx + '.png';
        await img.decode();
        fxImgs[idx] = img;
      }
      if (!document.getElementById('shot')) return;
      fx.setImage(fxImgs[idx]);
      const gl = fx.gl;
      gl.disable(gl.SCISSOR_TEST);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      const vp = viewportFor(canvas.width, canvas.height, setting('aspect'));
      fx.render(setting('filter'), +setting('color'), vp);
      if (ui.compare) fx.render('original', 0, vp, [0, 0, Math.floor(canvas.width / 2), canvas.height]);
    } catch (e) {
      console.error(e);
    }
  }
  function viewportFor(W, H, aspect) {
    if (aspect === 'fill') return [0, 0, W, H];
    const ar = 256 * (8 / 7) / 213;
    let w, h;
    if (aspect === 'zoom') { w = W; h = Math.round(W / ar); }
    else if (W / H > ar) { h = H; w = Math.round(H * ar); }
    else { w = W; h = Math.round(W / ar); }
    return [Math.round((W - w) / 2), Math.round((H - h) / 2), w, h];
  }

  // ---------------------------------------------------------------- chrome: status, background, intro
  let toastTimer;
  function toast(msg) {
    let t = $('.toast');
    if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 1800);
  }

  function updateStatus() {
    const st = $('.status');
    if (!st) return;
    const d = new Date();
    const time = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    let bat = '';
    if (typeof S.battery === 'number' && S.battery >= 0) {
      bat = '<span class="pill"><span class="bat' + (S.battery <= 20 && !S.charging ? ' low' : '') + '"><b style="width:' + S.battery + '%"></b></span>' +
        S.battery + '%' + (S.charging ? ' ⚡' : '') + '</span>';
    }
    st.innerHTML = bat + '<span class="pill">' + time + '</span>';
  }

  function buildBackground() {
    if (PAUSE) return;
    const bg = $('.bg');
    if (!bg) return;
    bg.innerHTML = '<video src="bg.mp4" muted loop playsinline></video>';
    const v = $('video', bg);
    v.addEventListener('canplay', () => v.classList.add('on'), { once: true });
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
  }

  function showIntro() {
    if (PAUSE || setting('intro') === '0' || sessionStorage.getItem('srbIntro')) return;
    try { sessionStorage.setItem('srbIntro', '1'); } catch (e) {}
    ui.intro = true;
    const d = document.createElement('div');
    d.className = 'intro';
    d.innerHTML = '<div class="sky"></div><div class="stars"></div><div class="glow"></div><div class="sunclip"><div class="sun"></div></div><div class="floor"><i></i></div>' +
      '<div class="title"><div class="l1">SUPER</div><div class="l2" data-t="Road Blaster">Road Blaster</div><div class="next">NEXT</div><div class="kana">ロードブラスター</div></div>' +
      '<div class="press"><b>PULSA START</b></div>';
    d.onclick = () => closeIntro();
    document.body.appendChild(d);
  }
  function closeIntro() {
    const d = $('.intro');
    ui.intro = false;
    if (!d) return;
    Sfx.play('open', 0.8);
    d.classList.add('out');
    const nav = $('nav.menu');
    nav.classList.remove('stagger'); void nav.offsetWidth; nav.classList.add('stagger');
    setTimeout(() => d.remove(), 700);
  }

  function refresh() {
    loadState();
    const cur = SECTIONS[ui.sec] && SECTIONS[ui.sec].id;
    buildSections();
    const i = SECTIONS.findIndex((s) => s.id === cur);
    ui.sec = i >= 0 ? i : 0;
    const f = $('.flash');
    if (f) f.classList.remove('go');
    renderMenu();
    panelKey = '';
    sync(false);
    updateStatus();
  }

  // ---------------------------------------------------------------- entry points
  window.srbPad = (tok) => press(tok);
  window.srbRefresh = refresh;
  function defaultTab() {
    const id = PAUSE ? (S.extra && S.extra.chapter) : +S.resumeChapter;
    const c = S.byId[id];
    return c ? c.level : 1;
  }
  window.srbOpenMini = () => {
    loadState();
    mini = true; miniIdx = 0;
    document.body.classList.add('minimode');
    renderMini();
    Sfx.play('open', 0.5);
  };
  window.srbOpen = () => {
    mini = false;
    document.body.classList.remove('minimode');
    ui.sec = 0; ui.zone = 'menu';
    loadState();
    ui.tab = defaultTab();
    document.body.classList.remove('show'); void document.body.offsetWidth; document.body.classList.add('show');
    refresh();
    Sfx.play('open', 0.6);
  };
  window.srbClose = () => { mini = false; document.body.classList.remove('minimode'); $$('video').forEach((v) => v.pause()); };
  window.srbToast = toast;
  document.addEventListener('keydown', (e) => {
    if (window.Android) return; // on device keys come from the activity
    const map = { ArrowUp: 'UP', ArrowDown: 'DOWN', ArrowLeft: 'LEFT', ArrowRight: 'RIGHT', Enter: 'A', Escape: 'B',
      Backspace: 'B', x: 'X', y: 'Y', q: 'L', e: 'R' };
    if (map[e.key]) { e.preventDefault(); press(map[e.key]); }
  });
  window.addEventListener('resize', () => { updateFocus(); });

  loadState();
  ui.tab = defaultTab();
  buildSections();
  buildBackground();
  renderMenu();
  sync(false);
  updateStatus();
  setInterval(updateStatus, 20000);
  Sfx.init();
  showIntro();
})();
