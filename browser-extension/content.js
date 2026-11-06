// "Download video" button over page videos. Placement idea adapted from Awesome Downloader (MIT), see NOTICE.
(() => {
  const api = globalThis.browser || chrome;
  if (window.top !== window) return;

  const STYLE = `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: "Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif; }
    .wrap { position: fixed; z-index: 2147483647; opacity: 0; transform: translateY(-4px);
      transition: opacity .18s cubic-bezier(.2,0,0,1), transform .18s cubic-bezier(.2,0,0,1); pointer-events: none; }
    .wrap.shown { opacity: 1; transform: none; pointer-events: auto; }
    .pill { display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 14px 0 10px; border-radius: 999px;
      border: 1px solid rgba(255,255,255,.14); background: rgba(16,16,22,.82); color: #fff;
      backdrop-filter: blur(14px) saturate(160%); -webkit-backdrop-filter: blur(14px) saturate(160%);
      box-shadow: 0 10px 28px rgba(0,0,0,.38), inset 0 1px 0 rgba(255,255,255,.08);
      font-size: 12.5px; font-weight: 600; letter-spacing: .1px; cursor: pointer; }
    .pill:hover { background: rgba(28,28,38,.9); }
    .pill:focus-visible, .row:focus-visible { outline: 2px solid #8B7BFF; outline-offset: 2px; }
    .dot { width: 18px; height: 18px; border-radius: 50%; display: grid; place-items: center;
      background: linear-gradient(135deg,#6E5BFF,#22D3EE); font-size: 11px; line-height: 1; }
    .close { margin-left: 2px; width: 18px; height: 18px; border-radius: 50%; display: grid; place-items: center;
      color: rgba(255,255,255,.6); font-size: 14px; }
    .close:hover { color: #fff; background: rgba(255,255,255,.12); }
    .menu { position: absolute; right: 0; top: 40px; width: min(360px, calc(100vw - 16px)); padding: 6px;
      border-radius: 14px; border: 1px solid rgba(255,255,255,.12); background: rgba(16,16,22,.9); color: #fff;
      backdrop-filter: blur(18px) saturate(160%); -webkit-backdrop-filter: blur(18px) saturate(160%);
      box-shadow: 0 18px 48px rgba(0,0,0,.45); max-height: 60vh; overflow: auto;
      opacity: 0; transform: translateY(-6px) scale(.98); transform-origin: top right;
      transition: opacity .2s cubic-bezier(.05,.7,.1,1), transform .2s cubic-bezier(.05,.7,.1,1); }
    .menu.open { opacity: 1; transform: none; }
    .row { display: grid; grid-template-columns: 1fr auto; gap: 2px 12px; width: 100%; text-align: left;
      background: transparent; border: 0; border-radius: 10px; padding: 9px 10px; color: #fff; cursor: pointer; }
    .row:hover { background: rgba(255,255,255,.08); }
    .row b { font-size: 13px; font-weight: 600; }
    .row span { font-size: 11.5px; color: rgba(255,255,255,.66); grid-column: 1 / 2; }
    .row em { font-style: normal; font-size: 11px; color: rgba(255,255,255,.66); grid-row: 1; grid-column: 2; align-self: center; }
    .row.best b::after { content: "HD"; margin-left: 8px; padding: 1px 6px; border-radius: 6px; font-size: 10px;
      background: linear-gradient(135deg,#6E5BFF,#22D3EE); }
    .note { padding: 8px 10px; font-size: 12px; color: rgba(255,255,255,.75); line-height: 1.4; }
    .note.error { color: #FFB4A8; }
    @media (prefers-reduced-motion: reduce) { .wrap, .menu { transition: none; } }
  `;

  const host = document.createElement('div');
  const shadow = host.attachShadow({mode: 'closed'});
  shadow.innerHTML = `<style>${STYLE}</style>
    <div class="wrap" role="region" aria-label="Absolute Download Manager">
      <button class="pill" type="button" aria-haspopup="menu" aria-expanded="false">
        <span class="dot" aria-hidden="true">↓</span>Download video
        <span class="close" role="button" aria-label="Hide for this page" title="Hide for this page">×</span>
      </button>
      <div class="menu" role="menu" hidden></div>
    </div>`;
  const wrap = shadow.querySelector('.wrap');
  const pill = shadow.querySelector('.pill');
  const menu = shadow.querySelector('.menu');
  let target = null, hideTimer, dismissed = false, mounted = false;

  function mount() {
    if (!mounted) { document.documentElement.appendChild(host); mounted = true; }
  }

  function place() {
    if (!target?.isConnected) return hide(true);
    const r = target.getBoundingClientRect();
    if (r.width < 200 || r.height < 110 || r.bottom < 40 || r.top > innerHeight - 40) return hide(true);
    const fullscreen = document.fullscreenElement;
    const parent = fullscreen && fullscreen.contains(target) ? fullscreen : document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
    wrap.style.top = `${Math.max(8, r.top + 10)}px`;
    wrap.style.left = `${Math.min(innerWidth - 8, r.right - 10) - wrap.offsetWidth}px`;
  }

  function show(video) {
    if (dismissed) return;
    mount();
    target = video;
    clearTimeout(hideTimer);
    place();
    wrap.classList.add('shown');
  }

  function hide(now) {
    clearTimeout(hideTimer);
    const go = () => { if (!menu.classList.contains('open')) wrap.classList.remove('shown'); };
    now ? go() : (hideTimer = setTimeout(go, 1600));
  }

  // only videos big enough to be "the" video on the page get the button
  document.addEventListener('pointerover', event => {
    const video = event.target?.closest?.('video');
    if (video) show(video);
  }, true);
  document.addEventListener('pointerout', event => {
    if (event.target?.closest?.('video') && !host.contains(event.relatedTarget)) hide(false);
  }, true);
  host.addEventListener('pointerenter', () => clearTimeout(hideTimer));
  host.addEventListener('pointerleave', () => hide(false));
  addEventListener('scroll', () => { if (wrap.classList.contains('shown')) place(); }, {capture: true, passive: true});
  addEventListener('resize', () => { if (wrap.classList.contains('shown')) place(); });
  document.addEventListener('fullscreenchange', () => target && place());
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); }, true);
  document.addEventListener('pointerdown', event => { if (!event.composedPath().includes(host)) closeMenu(); }, true);

  shadow.querySelector('.close').addEventListener('click', event => {
    event.stopPropagation();
    dismissed = true;
    closeMenu();
    hide(true);
  });

  pill.addEventListener('click', () => menu.classList.contains('open') ? closeMenu() : openMenu());

  function closeMenu() {
    menu.classList.remove('open');
    pill.setAttribute('aria-expanded', 'false');
    setTimeout(() => { if (!menu.classList.contains('open')) menu.hidden = true; }, 200);
  }

  function row({title, detail, side, best, onPick}) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'row' + (best ? ' best' : '');
    button.setAttribute('role', 'menuitem');
    button.innerHTML = '<b></b><em></em><span></span>';
    button.querySelector('b').textContent = title;
    button.querySelector('span').textContent = detail;
    button.querySelector('em').textContent = side || '';
    button.addEventListener('click', async () => {
      button.disabled = true;
      note('Sending to Absolute Download Manager…');
      try {
        const reply = await api.runtime.sendMessage(onPick());
        if (!reply?.ok) throw new Error(reply?.error || 'Something went wrong.');
        note('Sent. Check Absolute Download Manager.');
        setTimeout(closeMenu, 900);
      } catch (error) {
        note(error.message, true);
      } finally {
        button.disabled = false;
      }
    });
    return button;
  }

  function note(text, isError) {
    let el = menu.querySelector('.note');
    if (!el) { el = document.createElement('div'); menu.appendChild(el); }
    el.className = 'note' + (isError ? ' error' : '');
    el.textContent = text;
  }

  function size(bytes) {
    if (!bytes) return '';
    const mb = bytes / 1048576;
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(mb < 10 ? 1 : 0)} MB`;
  }

  async function openMenu() {
    menu.replaceChildren();
    menu.hidden = false;
    requestAnimationFrame(() => menu.classList.add('open'));
    pill.setAttribute('aria-expanded', 'true');
    try {
      if (isYouTube()) return await youtubeMenu();
      const reply = await api.runtime.sendMessage({action: 'choices'});
      for (const media of reply?.media || []) {
        let name = '';
        try { name = decodeURIComponent(new URL(media.url).pathname.split('/').pop()); } catch {}
        menu.appendChild(row({
          title: media.kind,
          detail: name || media.url,
          side: size(media.size),
          onPick: () => ({action: 'downloadMedia', url: media.url, pageUrl: location.href}),
        }));
      }
      if (!menu.querySelector('.row')) note('No video found yet. Play it, then open this menu again.');
    } catch (error) {
      if (/context invalidated/i.test(error?.message || '')) note('The extension was updated. Reload this page.', true);
      else note(error?.message || 'Something went wrong.', true);
    }
  }

  // ---------- YouTube: one row per quality, like a download manager's "Download this video" list ----------

  function isYouTube() {
    return /(^|\.)youtube\.com$/.test(location.hostname) && (location.pathname === '/watch' || location.pathname.startsWith('/shorts/'));
  }

  /** Asks youtube-page.js (running in the page's own context) for the player's format list. */
  function playerData() {
    return new Promise(resolve => {
      const nonce = crypto.randomUUID();
      const done = data => { removeEventListener('message', onMessage); clearTimeout(timer); resolve(data); };
      const onMessage = event => {
        if (event.source === window && event.data?.admYouTube === 'player' && event.data.nonce === nonce) done(event.data.data);
      };
      const timer = setTimeout(() => done(null), 1500);
      addEventListener('message', onMessage);
      window.postMessage({admYouTube: 'request', nonce}, location.origin);
    });
  }

  /**
   * One choice per quality (height + frame rate). H.264 is preferred because every player opens it as MP4;
   * VP9 needs MKV; AV1 comes last. The audio is the original-language AAC track (Opus if there is none).
   */
  function youtubeChoices(formats) {
    const usable = formats.filter(f => !f.hasDrm && f.contentLength > 0);
    const byBitrate = (a, b) => b.bitrate - a.bitrate;
    const audios = usable.filter(f => f.mimeType.startsWith('audio/') && !f.isDrc && f.audioDefault);
    const audio = audios.filter(f => f.mimeType.startsWith('audio/mp4')).sort(byBitrate)[0] || audios.sort(byBitrate)[0];
    if (!audio) return [];
    const audioIsMp4 = audio.mimeType.startsWith('audio/mp4');
    const codecRank = f => /avc1/.test(f.mimeType) ? 3 : /vp0?9/.test(f.mimeType) ? 2 : 1;
    const best = new Map();
    for (const f of usable) {
      const height = parseInt(f.qualityLabel, 10);
      if (!f.mimeType.startsWith('video/') || !height) continue;
      const key = `${height}/${f.fps > 30 ? 60 : 30}`;
      const old = best.get(key);
      if (!old || codecRank(f) > codecRank(old) || (codecRank(f) === codecRank(old) && f.bitrate > old.bitrate)) best.set(key, f);
    }
    const videos = [...best.values()].sort((a, b) => parseInt(b.qualityLabel, 10) - parseInt(a.qualityLabel, 10) || b.fps - a.fps);
    const choices = videos.map(video => {
      const height = parseInt(video.qualityLabel, 10);
      const container = video.mimeType.startsWith('video/mp4') && audioIsMp4 ? 'mp4' : 'mkv';
      const label = `${container.toUpperCase()} file, quality ${height}p${height >= 720 ? ' HD' : ''}${video.fps > 30 ? ` ${video.fps} fps` : ''}`;
      return {label, video, audio, container, size: video.contentLength + audio.contentLength};
    });
    const audioExt = audioIsMp4 ? 'm4a' : 'webm';
    choices.push({label: `${audioExt.toUpperCase()} audio file, ${Math.round(audio.bitrate / 1000)} kbps`, video: null, audio, container: audioExt, size: audio.contentLength});
    return choices;
  }

  async function youtubeMenu() {
    const player = await playerData();
    if (!player) return note('Start playing the video, then open this menu again.');
    if (player.isLive) return note('Live streams cannot be downloaded yet.');
    const choices = youtubeChoices(player.formats);
    if (!choices.length) return note('This video has no downloadable formats (it may be protected).');
    const send = picks => ({action: 'downloadYouTube', player, picks: picks.map(({video, audio, container}) => ({video, audio, container}))});
    menu.appendChild(row({
      title: 'Download all',
      detail: `${choices.length} files`,
      side: size(choices.reduce((sum, c) => sum + c.size, 0)),
      onPick: () => send(choices),
    }));
    choices.forEach((choice, index) => menu.appendChild(row({
      title: `${index + 1}. ${player.title}`,
      detail: choice.label,
      side: size(choice.size),
      onPick: () => send([choice]),
    })));
  }

  // "Download all links on this page" from the context menu
  api.runtime.onMessage.addListener((message, sender, respond) => {
    if (sender.id !== api.runtime.id || message.action !== 'collectLinks') return false;
    const selection = getSelection();
    const anchors = message.selectionOnly && selection?.rangeCount
      ? [...document.querySelectorAll('a[href]')].filter(a => selection.containsNode(a, true))
      : [...document.querySelectorAll('a[href]')];
    const links = [...new Set(anchors.map(a => a.href).filter(href => /^https?:/i.test(href)))];
    respond(links);
    return false;
  });
})();
