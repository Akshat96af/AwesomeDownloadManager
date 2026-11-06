// Absolute Download Manager browser integration (Manifest V3 service worker).
// Download hand-off and media detection are adapted from Awesome Downloader (MIT), see NOTICE.
const api = globalThis.browser || chrome;
const HOST = 'com.absolutedownloadmanager';
const MEDIA_KEY = 'mediaByTab';

// ---------- talking to the app ----------

/** Sends one request to the native host and resolves with its decoded payload. */
function native(action, payload) {
  const message = {id: 'B_' + crypto.randomUUID(), content: {action, isError: false, payload: JSON.stringify(payload ?? null)}};
  return new Promise((resolve, reject) => {
    const done = reply => {
      if (!reply?.content) return reject(new Error('Absolute Download Manager did not answer.'));
      if (reply.content.isError) {
        let reason;
        try { reason = JSON.parse(reply.content.payload)?.message; } catch {}
        return reject(new Error(reason || 'Absolute Download Manager refused the request.'));
      }
      resolve(reply.content.payload);
    };
    if (globalThis.browser) {
      browser.runtime.sendNativeMessage(HOST, message).then(done, error => reject(friendly(error)));
    } else {
      chrome.runtime.sendNativeMessage(HOST, message, reply => {
        if (chrome.runtime.lastError) reject(friendly(chrome.runtime.lastError));
        else done(reply);
      });
    }
  });
}

function friendly(error) {
  const text = error?.message || String(error);
  if (/native messaging host not found|specified native messaging host/i.test(text)) {
    return new Error('Absolute Download Manager is not installed or has not been started once yet.');
  }
  return new Error(text);
}

function addToApp(items) {
  return native('add', {items, options: {silentAdd: false, silentStart: false}});
}

// ---------- what we send ----------

function httpUrl(value) {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

/** The app re-requests the file itself, so it needs the browser's cookies and referrer to be let in. */
async function headersFor(url, referrer) {
  const headers = {'User-Agent': navigator.userAgent};
  try {
    const cookies = await api.cookies.getAll({url});
    if (cookies.length) headers.Cookie = cookies.map(c => `${c.name}=${c.value}`).join('; ');
  } catch {}
  const ref = httpUrl(referrer);
  if (ref) headers.Referer = ref;
  return headers;
}

async function fileItem(url, {referrer, name} = {}) {
  const isHls = /\.m3u8(?:[?#]|$)/i.test(url);
  return {type: isHls ? 'hls' : 'http', link: url, headers: await headersFor(url, referrer), downloadPage: httpUrl(referrer) || undefined, suggestedName: name || undefined};
}

function pageItem(pageUrl, title) {
  return {type: 'page', link: pageUrl, downloadPage: pageUrl, suggestedName: title || undefined};
}

// ---------- settings ----------

async function captureEnabled() {
  const {capture} = await api.storage.local.get({capture: true});
  return capture;
}

// ---------- browser downloads hand-off ----------

/** Offers a browser download to the app. Resolves true when the app took it. */
async function handOff(item) {
  const url = httpUrl(item.finalUrl || item.url);
  if (!url || !(await captureEnabled())) return false;
  const name = item.filename ? item.filename.split(/[\\/]/).pop() : undefined;
  try {
    await addToApp([await fileItem(url, {referrer: item.referrer, name})]);
    return true;
  } catch (error) {
    await remember('lastError', error.message);
    return false;
  }
}

async function dropBrowserCopy(id) {
  await api.downloads.cancel(id).catch(() => {});
  await api.downloads.erase({id}).catch(() => {});
}

if (api.downloads.onDeterminingFilename) {
  // Chromium cannot finish a download until this listener answers, so small files can't slip
  // through while the app is being asked (measured: a 351-byte zip completed before onCreated could pause it).
  // A refused or failed hand-off just lets the browser carry on.
  api.downloads.onDeterminingFilename.addListener((item, suggest) => {
    handOff(item).then(taken => taken ? dropBrowserCopy(item.id) : suggest());
    return true;
  });
} else {
  // Firefox has no such hook: pause while asking, resume if the app says no.
  api.downloads.onCreated.addListener(async item => {
    if (item.state === 'complete') return;
    const paused = await api.downloads.pause(item.id).then(() => true, () => false);
    if (await handOff(item)) await dropBrowserCopy(item.id);
    else if (paused) await api.downloads.resume(item.id).catch(() => {});
  });
}

// ---------- media detection ----------

const FRAGMENT = /\.(m4s|ts|aac|vtt)(?:[?#]|$)|\/init[^/]*\.mp4(?:[?#]|$)/i;
// YouTube serves media in ranged fragments; the page item (yt-dlp in the app) covers it properly.
const FRAGMENT_HOSTS = /(^|\.)googlevideo\.com$/i;

let sessionWrite = Promise.resolve();
function updateMedia(change) {
  sessionWrite = sessionWrite.then(async () => {
    const data = await api.storage.session.get(MEDIA_KEY);
    const map = data[MEDIA_KEY] || {};
    change(map);
    const tabs = Object.keys(map);
    tabs.slice(0, Math.max(0, tabs.length - 40)).forEach(tab => delete map[tab]);
    await api.storage.session.set({[MEDIA_KEY]: map});
  }).catch(() => {});
  return sessionWrite;
}

async function mediaFor(tabId) {
  await sessionWrite;
  const data = await api.storage.session.get(MEDIA_KEY);
  return data[MEDIA_KEY]?.[tabId] || [];
}

api.webRequest.onHeadersReceived.addListener(details => {
  if (details.tabId < 0 || ![200, 206].includes(details.statusCode)) return;
  const url = httpUrl(details.url);
  if (!url || FRAGMENT.test(url)) return;
  try { if (FRAGMENT_HOSTS.test(new URL(url).hostname)) return; } catch { return; }
  const header = name => details.responseHeaders?.find(h => h.name.toLowerCase() === name)?.value || '';
  const mime = header('content-type').split(';')[0].trim().toLowerCase();
  const hls = /\.m3u8(?:[?#]|$)/i.test(url) || /mpegurl/.test(mime);
  if (!hls && !/^(video|audio)\//.test(mime)) return;
  const size = Number(header('content-range').split('/')[1] || header('content-length')) || 0;
  // tiny clips are previews and ads, not what anyone wants to keep
  if (!hls && size && size < 512 * 1024) return;
  updateMedia(map => {
    const list = map[details.tabId] || [];
    if (list.some(entry => entry.url === url)) return;
    list.push({url, mime, size, kind: hls ? 'HLS stream' : mime.startsWith('audio/') ? 'Audio' : 'Video'});
    map[details.tabId] = list.slice(-30);
  }).then(() => refreshBadge(details.tabId));
}, {urls: ['http://*/*', 'https://*/*']}, ['responseHeaders']);

async function refreshBadge(tabId) {
  const count = (await mediaFor(tabId)).length;
  api.action.setBadgeBackgroundColor({tabId, color: '#6E5BFF'}).catch(() => {});
  api.action.setBadgeText({tabId, text: count ? String(count) : ''}).catch(() => {});
}

function forgetTab(tabId) {
  updateMedia(map => { delete map[tabId]; }).then(() => refreshBadge(tabId));
}
api.tabs.onRemoved.addListener(forgetTab);
api.tabs.onUpdated.addListener((tabId, change) => { if (change.status === 'loading' && change.url) forgetTab(tabId); });

// ---------- context menus ----------

api.runtime.onInstalled.addListener(() => {
  api.contextMenus.removeAll(() => {
    api.contextMenus.create({id: 'adm-link', title: 'Download with Absolute DM', contexts: ['link', 'video', 'audio', 'image']});
    api.contextMenus.create({id: 'adm-page-video', title: 'Download video from this page', contexts: ['page', 'video']});
    api.contextMenus.create({id: 'adm-all-links', title: 'Download all links on this page', contexts: ['page', 'selection']});
  });
});

api.contextMenus.onClicked.addListener(async (info, tab) => {
  try {
    if (info.menuItemId === 'adm-link') {
      const url = httpUrl(info.linkUrl || info.srcUrl);
      if (!url) throw new Error('This link cannot be downloaded.');
      await addToApp([await fileItem(url, {referrer: tab?.url})]);
    } else if (info.menuItemId === 'adm-page-video') {
      await addToApp([pageItem(tab.url, tab.title)]);
    } else if (info.menuItemId === 'adm-all-links') {
      const links = await api.tabs.sendMessage(tab.id, {action: 'collectLinks', selectionOnly: !!info.selectionText});
      if (!links?.length) throw new Error('No links found.');
      await addToApp(await Promise.all(links.slice(0, 500).map(url => fileItem(url, {referrer: tab.url}))));
    }
  } catch (error) {
    await remember('lastError', error.message);
  }
});

// ---------- messages from the page button and the popup ----------

api.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== api.runtime.id) return false;
  const task = async () => {
    const tabId = sender.tab?.id ?? message.tabId;
    switch (message.action) {
      case 'choices': return {ok: true, media: await mediaFor(tabId)};
      case 'downloadPage': {
        const pageUrl = httpUrl(message.pageUrl || sender.tab?.url);
        if (!pageUrl) throw new Error('This page cannot be downloaded.');
        await addToApp([pageItem(pageUrl, message.title || sender.tab?.title)]);
        return {ok: true};
      }
      case 'downloadMedia': {
        const url = httpUrl(message.url);
        if (!url) throw new Error('This media cannot be downloaded.');
        await addToApp([await fileItem(url, {referrer: message.pageUrl || sender.tab?.url, name: message.name})]);
        return {ok: true};
      }
      case 'status': {
        let connected = true, error;
        try { await native('ping', null); } catch (e) { connected = false; error = e.message; }
        const {lastError} = await api.storage.local.get('lastError');
        return {ok: true, connected, error, lastError, capture: await captureEnabled()};
      }
      case 'setCapture': await api.storage.local.set({capture: !!message.value}); return {ok: true};
      default: throw new Error('Unknown request.');
    }
  };
  task().then(respond, error => respond({ok: false, error: error.message}));
  return true;
});

function remember(key, value) {
  return api.storage.local.set({[key]: value}).catch(() => {});
}
