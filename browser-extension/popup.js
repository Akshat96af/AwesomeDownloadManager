const api = globalThis.browser || chrome;
const $ = id => document.getElementById(id);

const send = message => api.runtime.sendMessage(message);

function say(text, bad) {
  $('message').textContent = text || '';
  $('message').className = 'hint' + (bad ? ' bad' : '');
}

function size(bytes) {
  if (!bytes) return '';
  const mb = bytes / 1048576;
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(mb < 10 ? 1 : 0)} MB`;
}

async function run(button, message, done) {
  button.disabled = true;
  say('Sending to Absolute Download Manager…');
  const reply = await send(message).catch(error => ({ok: false, error: error.message}));
  button.disabled = false;
  if (reply?.ok) { say(done); setTimeout(() => window.close(), 900); }
  else say(reply?.error || 'Something went wrong.', true);
}

(async () => {
  const [tab] = await api.tabs.query({active: true, currentWindow: true});

  const status = await send({action: 'status'}).catch(() => null);
  $('status').textContent = status?.connected ? 'Connected' : (status?.error || 'Not connected');
  $('status').className = 'status ' + (status?.connected ? 'ok' : 'bad');
  $('capture').checked = status?.capture ?? true;
  if (status?.lastError) say(`Last problem: ${status.lastError}`, true);

  $('capture').addEventListener('change', event => send({action: 'setCapture', value: event.target.checked}));

  const pageButton = $('page-video');
  if (!/^https?:/i.test(tab?.url || '')) pageButton.disabled = true;
  pageButton.addEventListener('click', () => run(pageButton,
    {action: 'downloadPage', tabId: tab.id, pageUrl: tab.url, title: tab.title},
    'Sent. The app is finding the best version.'));

  const reply = await send({action: 'choices', tabId: tab?.id}).catch(() => null);
  const media = reply?.media || [];
  $('empty').hidden = media.length > 0;
  for (const item of media) {
    let name = item.url;
    try { name = decodeURIComponent(new URL(item.url).pathname.split('/').pop()) || item.url; } catch {}
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.innerHTML = '<span><strong></strong><small></small></span><em></em>';
    button.querySelector('strong').textContent = item.kind;
    button.querySelector('small').textContent = name;
    button.querySelector('em').textContent = size(item.size);
    button.title = item.url;
    button.addEventListener('click', () => run(button,
      {action: 'downloadMedia', tabId: tab.id, url: item.url, pageUrl: tab.url}, 'Sent.'));
    li.appendChild(button);
    $('media').appendChild(li);
  }
})();
