// OpenBased web UI. A plain client of the public API: it signs in with OAuth2
// Authorization Code + PKCE like any third-party app and holds no other privileges.

const CLIENT_ID = 'openbased-web';
const REDIRECT_URI = location.origin + '/callback';
const SCOPES = 'openid profile media.read media.stream library.read library.write history.read history.write upload';
const TOKEN_KEY = 'openbased.token';
const PKCE_KEY = 'openbased.pkce';
const RETURN_KEY = 'openbased.return';

const $ = (selector) => document.querySelector(selector);
const main = $('#main');

// ---------------------------------------------------------------- auth

function randomString(bytes = 32) {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return base64url(data);
}

function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function challengeFor(verifier) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64url(new Uint8Array(digest));
}

function decodeJwt(token) {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part.padEnd(part.length + (4 - part.length % 4) % 4, '=')));
}

function loadToken() {
  try {
    const token = JSON.parse(sessionStorage.getItem(TOKEN_KEY));
    return token && token.expiresAt > Date.now() + 5000 ? token : null;
  } catch {
    return null;
  }
}

async function authorizeUrl() {
  const verifier = randomString();
  const state = randomString(16);
  sessionStorage.setItem(PKCE_KEY, JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: 'code',
    scope: SCOPES,
    state,
    code_challenge: await challengeFor(verifier),
    code_challenge_method: 'S256',
  });
  return '/oauth2/authorize?' + params;
}

async function signIn() {
  sessionStorage.setItem(RETURN_KEY, location.hash || '#/');
  location.assign(await authorizeUrl());
}

async function exchangeCode(code, state) {
  const pkce = JSON.parse(sessionStorage.getItem(PKCE_KEY) || 'null');
  if (!pkce || pkce.state !== state) {
    throw new Error('Sign-in state mismatch. Please try again.');
  }
  sessionStorage.removeItem(PKCE_KEY);
  const response = await fetch('/oauth2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: REDIRECT_URI,
      client_id: CLIENT_ID,
      code_verifier: pkce.verifier,
    }),
  });
  if (!response.ok) {
    throw new Error('Sign-in failed (' + response.status + ').');
  }
  const body = await response.json();
  const claims = decodeJwt(body.access_token);
  const token = {
    accessToken: body.access_token,
    idToken: body.id_token,
    expiresAt: Date.now() + body.expires_in * 1000,
    scopes: Array.isArray(claims.scope) ? claims.scope : String(claims.scope || '').split(' '),
  };
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(token));
  return token;
}

// Access tokens are short-lived and public clients get no refresh token, so the UI
// renews silently by re-running the authorization flow in a hidden iframe. The
// server-side sign-in session makes this instant and invisible.
let renewing = null;
function renewSilently() {
  if (renewing) return renewing;
  renewing = new Promise(async (resolve) => {
    const frame = document.createElement('iframe');
    frame.hidden = true;
    const done = (token) => {
      window.removeEventListener('message', onMessage);
      clearTimeout(timer);
      frame.remove();
      renewing = null;
      resolve(token);
    };
    const onMessage = async (event) => {
      if (event.origin !== location.origin || event.data?.type !== 'openbased-callback') return;
      try {
        done(await exchangeCode(event.data.code, event.data.state));
      } catch {
        done(null);
      }
    };
    const timer = setTimeout(() => done(null), 10000);
    window.addEventListener('message', onMessage);
    frame.src = await authorizeUrl();
    document.body.append(frame);
  });
  return renewing;
}

function scheduleRenewal() {
  const token = loadToken();
  if (!token) return;
  const delay = Math.max(5000, token.expiresAt - Date.now() - 60000);
  setTimeout(async () => {
    const renewed = await renewSilently();
    if (renewed) {
      player.onTokenRenewed();
      scheduleRenewal();
    }
  }, delay);
}

let token = null;

function hasScope(scope) {
  return token?.scopes.includes(scope);
}

// ---------------------------------------------------------------- api

class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || 'Request failed (' + status + ').');
    this.status = status;
    this.error = body?.error;
  }
}

async function api(path, { method = 'GET', body, raw = false } = {}) {
  const request = () => fetch('/api/v1' + path, {
    method,
    headers: {
      Authorization: 'Bearer ' + token.accessToken,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let response = await request();
  if (response.status === 401) {
    token = await renewSilently();
    if (!token) {
      await signIn();
      return new Promise(() => {});
    }
    response = await request();
  }
  if (raw) return response;
  if (response.status === 204) return null;
  const json = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, json);
  return json;
}

const artworkCache = new Map();
async function artworkUrl(path) {
  if (!path) return null;
  if (!artworkCache.has(path)) {
    artworkCache.set(path, api(path.replace(/^\/api\/v1/, ''), { raw: true })
      .then((r) => (r.ok ? r.blob() : null))
      .then((blob) => (blob ? URL.createObjectURL(blob) : null))
      .catch(() => null));
  }
  return artworkCache.get(path);
}

// ---------------------------------------------------------------- rendering

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'class') el.className = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined && child !== false) el.append(child);
  }
  return el;
}

function formatDuration(ms) {
  if (!ms) return null;
  const minutes = Math.round(ms / 60000);
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}

function subtitle(item) {
  if (item.type === 'EPISODE' && item.seasonNumber != null) {
    return `S${String(item.seasonNumber).padStart(2, '0')}E${String(item.episodeNumber).padStart(2, '0')}`;
  }
  return item.year ?? '';
}

function card(item, progress) {
  const poster = h('div', { class: 'poster' }, item.type === 'TRACK' ? '♪' : '▶');
  artworkUrl(item.poster).then((url) => {
    if (url) {
      poster.style.backgroundImage = `url("${url}")`;
      poster.firstChild?.remove();
    }
  });
  if (progress?.duration) {
    poster.append(h('div', { class: 'progress', style: `width:${Math.min(100, progress.position / progress.duration * 100)}%` }));
  }
  return h('button', { class: 'card', onclick: () => go(`#/media/${item.id}`) },
    poster,
    h('div', { class: 'card-title', title: item.title }, item.title),
    h('div', { class: 'card-sub' }, String(subtitle(item))));
}

function grid(items, progressById = {}) {
  return h('div', { class: 'grid' }, items.map((item) => card(item, progressById[item.id])));
}

function show(...nodes) {
  main.replaceChildren(...nodes);
}

function showError(error) {
  show(h('p', { class: 'error' }, error.message));
}

// ---------------------------------------------------------------- views

let libraries = [];

async function loadLibraries() {
  libraries = hasScope('library.read') ? (await api('/libraries')).items : [];
  const nav = $('#libraries');
  nav.replaceChildren(...libraries.map((lib) => h('a', { href: `#/library/${lib.id}`, 'data-id': lib.id }, lib.name)));
}

function markActiveLibrary(id) {
  document.querySelectorAll('#libraries a').forEach((a) => a.classList.toggle('active', a.dataset.id === id));
}

async function viewHome() {
  markActiveLibrary(null);
  const sections = [];
  if (hasScope('history.read')) {
    const cont = await api('/continue-watching?pageSize=20');
    if (cont.items.length) {
      const items = (await Promise.all(cont.items.map((p) => api(`/media/${p.mediaId}`).catch(() => null)))).filter(Boolean);
      const byId = Object.fromEntries(cont.items.map((p) => [p.mediaId, p]));
      sections.push(h('h2', {}, 'Continue watching'), grid(items, byId));
    }
  }
  for (const lib of libraries) {
    const recent = await api(`/media?library=${lib.id}&sort=-addedAt&pageSize=12`);
    if (recent.items.length) {
      sections.push(h('h2', {}, h('a', { href: `#/library/${lib.id}` }, lib.name)), grid(recent.items));
    }
  }
  if (!sections.length) {
    sections.push(h('p', { class: 'status' }, libraries.length
      ? 'Your libraries are empty. Scan them from Manage to find media.'
      : hasScope('library.write')
        ? 'No libraries yet. Open Manage to add one.'
        : 'No libraries have been shared with you yet.'));
  }
  show(...sections);
}

async function viewLibrary(id, page = 0) {
  markActiveLibrary(id);
  const lib = libraries.find((l) => l.id === id) || await api(`/libraries/${id}`);
  const result = await api(`/media?library=${id}&page=${page}&pageSize=60`);
  const pages = Math.ceil(result.total / result.pageSize);
  show(
    h('h2', {}, `${lib.name} · ${result.total}`),
    grid(result.items),
    pages > 1 ? h('div', { class: 'actions' },
      h('button', { class: 'ghost', disabled: page === 0, onclick: () => go(`#/library/${id}/${page - 1}`) }, 'Previous'),
      h('span', { class: 'status' }, `Page ${page + 1} of ${pages}`),
      h('button', { class: 'ghost', disabled: page + 1 >= pages, onclick: () => go(`#/library/${id}/${page + 1}`) }, 'Next')) : null);
}

async function viewSearch(q) {
  markActiveLibrary(null);
  const result = await api(`/search?q=${encodeURIComponent(q)}&pageSize=60`);
  show(h('h2', {}, `Results for “${q}”`),
    result.results.length ? grid(result.results) : h('p', { class: 'status' }, 'Nothing found.'));
}

async function viewMedia(id) {
  const item = await api(`/media/${id}`);
  markActiveLibrary(item.libraryId);
  let progress = null;
  if (hasScope('history.read')) {
    progress = await api(`/media/${id}/progress`).catch(() => null);
  }
  const poster = h('div', { class: 'poster' }, '▶');
  artworkUrl(item.artwork?.poster).then((url) => {
    if (url) {
      poster.style.backgroundImage = `url("${url}")`;
      poster.firstChild?.remove();
    }
  });
  const resumable = progress && !progress.completed && progress.position > 5;
  const meta = [item.year, formatDuration(item.duration), item.genres?.join(', ')].filter(Boolean).join(' · ');
  const file = item.files[0];
  show(h('div', { class: 'detail' },
    poster,
    h('div', {},
      h('h1', {}, item.title),
      item.seriesTitle ? h('div', { class: 'meta' }, item.seriesTitle) : null,
      h('div', { class: 'meta' }, meta),
      item.overview ? h('p', {}, item.overview) : null,
      h('div', { class: 'actions' },
        hasScope('media.stream') && file ? h('button', { onclick: () => player.open(item, resumable ? progress.position : 0) },
          resumable ? `Resume from ${Math.floor(progress.position / 60)}:${String(Math.floor(progress.position % 60)).padStart(2, '0')}` : 'Play') : null,
        resumable ? h('button', { class: 'ghost', onclick: () => player.open(item, 0) }, 'Play from start') : null,
        hasScope('library.write') ? h('button', { class: 'ghost', onclick: (e) => refreshMetadata(e.target, id) }, 'Refresh metadata') : null),
      file ? h('div', { class: 'files' },
        [file.container, file.videoCodec, file.audioCodec, file.width ? `${file.width}×${file.height}` : null,
          `${(file.size / 1e9).toFixed(2)} GB`].filter(Boolean).join(' · ')) : null)));
}

async function refreshMetadata(button, id) {
  button.disabled = true;
  try {
    const { jobId } = await api(`/media/${id}/metadata/refresh`, { method: 'POST' });
    const job = await waitForJob(jobId, (j) => { button.textContent = `Refreshing… ${j.progress}%`; });
    if (job.status === 'COMPLETED') route();
    else button.textContent = job.message || 'No match found';
  } catch (error) {
    button.textContent = error.message;
  }
}

async function waitForJob(jobId, onUpdate) {
  for (;;) {
    const job = await api(`/jobs/${jobId}`);
    onUpdate?.(job);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) return job;
    await new Promise((r) => setTimeout(r, 700));
  }
}

async function viewManage() {
  markActiveLibrary(null);
  const all = (await api('/libraries')).items;
  const rows = all.map((lib) => {
    const status = h('span', { class: 'status' }, lib.lastScannedAt ? `Scanned ${new Date(lib.lastScannedAt).toLocaleString()}` : 'Never scanned');
    const scan = h('button', {
      class: 'ghost',
      onclick: async () => {
        scan.disabled = true;
        try {
          const { jobId } = await api(`/libraries/${lib.id}/scan`, { method: 'POST' });
          const job = await waitForJob(jobId, (j) => { status.textContent = `Scanning… ${j.progress}%`; });
          status.textContent = job.status === 'COMPLETED' ? 'Scan complete' : `Scan ${job.status.toLowerCase()}: ${job.message ?? ''}`;
        } catch (error) {
          status.textContent = error.message;
        }
        scan.disabled = false;
      },
    }, 'Scan');
    const remove = h('button', {
      class: 'ghost',
      onclick: async () => {
        if (!confirm(`Remove the library “${lib.name}”? Files on disk are kept.`)) return;
        await api(`/libraries/${lib.id}`, { method: 'DELETE' });
        await loadLibraries();
        viewManage();
      },
    }, 'Remove');
    return h('tr', {}, h('td', {}, lib.name), h('td', {}, lib.type), h('td', {}, (lib.paths || []).join(', ')),
      h('td', {}, status), h('td', {}, h('div', { class: 'actions' }, scan, remove)));
  });

  const message = h('span', { class: 'status' });
  const form = h('form', {
    onsubmit: async (event) => {
      event.preventDefault();
      const data = new FormData(form);
      try {
        await api('/libraries', {
          method: 'POST',
          body: { name: data.get('name'), type: data.get('type'), paths: [data.get('path')] },
        });
        await loadLibraries();
        viewManage();
      } catch (error) {
        message.textContent = error.message;
        message.className = 'error';
      }
    },
  },
  h('input', { name: 'name', placeholder: 'Name', required: true }),
  h('select', { name: 'type' }, ['MOVIES', 'TV', 'MUSIC', 'OTHER'].map((t) => h('option', { value: t }, t))),
  h('input', { name: 'path', placeholder: 'Absolute folder path on the server, e.g. /media/movies', required: true }),
  h('button', { type: 'submit' }, 'Add library'),
  message);

  show(
    h('h2', {}, 'Libraries'),
    h('div', { class: 'panel' }, all.length
      ? h('table', {}, h('thead', {}, h('tr', {}, ['Name', 'Type', 'Folders', 'Status', ''].map((t) => h('th', {}, t)))), h('tbody', {}, rows))
      : h('p', { class: 'status' }, 'No libraries yet.')),
    h('h2', {}, 'Add a library'),
    h('div', { class: 'panel' }, form));
}

// ---------------------------------------------------------------- api tokens

const TOKEN_LIFETIMES = [['30 days', 30], ['90 days', 90], ['1 year', 365]];

async function viewTokens(created) {
  markActiveLibrary(null);
  const list = (await api('/tokens')).items;
  const order = SCOPES.split(' ');
  const grantable = token.scopes.filter((s) => s !== 'openid')
    .sort((a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99));

  const rows = list.map((t) => h('tr', {},
    h('td', {}, t.name),
    h('td', {}, t.scopes.join(' ')),
    h('td', {}, new Date(t.createdAt).toLocaleDateString()),
    h('td', {}, t.expiresAt ? new Date(t.expiresAt).toLocaleDateString() : 'never'),
    h('td', {}, t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'),
    h('td', {}, h('button', {
      class: 'ghost',
      onclick: async () => {
        if (!confirm(`Revoke “${t.name}”? Scripts using it stop working immediately.`)) return;
        await api(`/tokens/${t.id}`, { method: 'DELETE' });
        viewTokens();
      },
    }, 'Revoke'))));

  const message = h('span', { class: 'status' });
  const form = h('form', {
    onsubmit: async (event) => {
      event.preventDefault();
      const data = new FormData(form);
      const scopes = data.getAll('scope');
      if (!scopes.length) {
        message.textContent = 'Pick at least one scope.';
        message.className = 'error';
        return;
      }
      try {
        const result = await api('/tokens', {
          method: 'POST',
          body: { name: data.get('name'), scopes, expiresIn: Number(data.get('days')) * 86400 },
        });
        viewTokens(result);
      } catch (error) {
        message.textContent = error.message;
        message.className = 'error';
      }
    },
  },
  h('input', { name: 'name', placeholder: 'Name, e.g. Backup script', required: true, maxlength: 100 }),
  h('select', { name: 'days', 'aria-label': 'Expires after' },
    TOKEN_LIFETIMES.map(([label, days]) => h('option', { value: days, selected: days === 90 }, `Expires in ${label}`))),
  h('div', { class: 'scopes' }, grantable.map((scope) => h('label', {},
    h('input', { type: 'checkbox', name: 'scope', value: scope, checked: scope === 'media.read' }), ' ', scope))),
  h('button', { type: 'submit' }, 'Create token'),
  message);

  let reveal = null;
  if (created) {
    const value = h('code', { class: 'secret' }, created.token);
    const copy = h('button', {
      onclick: async () => {
        await navigator.clipboard.writeText(created.token);
        copy.textContent = 'Copied';
      },
    }, 'Copy');
    reveal = h('div', { class: 'panel notice' },
      h('strong', {}, `Token “${created.name}” created. Copy it now — it will not be shown again.`),
      h('div', { class: 'secret-row' }, value, copy),
      h('div', { class: 'status' }, 'Use it as: Authorization: Bearer <token>'));
  }

  show(
    h('h2', {}, 'API tokens'),
    h('p', { class: 'status' }, 'Personal access tokens let scripts and other tools use the API as you. '
      + 'A token can only have permissions you have yourself.'),
    reveal,
    h('div', { class: 'panel' }, list.length
      ? h('table', {}, h('thead', {}, h('tr', {}, ['Name', 'Scopes', 'Created', 'Expires', 'Last used', ''].map((t) => h('th', {}, t)))), h('tbody', {}, rows))
      : h('p', { class: 'status' }, 'You have no tokens.')),
    h('h2', {}, 'Create a token'),
    h('div', { class: 'panel' }, form));
}

// ---------------------------------------------------------------- playback

function capabilities() {
  const video = document.createElement('video');
  const can = (type) => video.canPlayType(type) !== '';
  const containers = ['mp4', 'webm'].filter((c) => can(`video/${c}`));
  const videoCodecs = [];
  if (can('video/mp4; codecs="avc1.42E01E"')) videoCodecs.push('h264');
  if (can('video/mp4; codecs="hvc1.1.6.L93.B0"')) videoCodecs.push('hevc');
  if (can('video/webm; codecs="vp9"')) videoCodecs.push('vp9');
  if (can('video/webm; codecs="vp8"')) videoCodecs.push('vp8');
  if (can('video/mp4; codecs="av01.0.05M.08"')) videoCodecs.push('av1');
  const audioCodecs = [];
  if (can('audio/mp4; codecs="mp4a.40.2"')) audioCodecs.push('aac');
  if (can('audio/mpeg')) audioCodecs.push('mp3');
  if (can('audio/webm; codecs="opus"')) audioCodecs.push('opus');
  if (can('audio/webm; codecs="vorbis"')) audioCodecs.push('vorbis');
  if (can('audio/flac')) audioCodecs.push('flac');
  return { containers, videoCodecs, audioCodecs };
}

const player = {
  dialog: $('#player-dialog'),
  video: $('#player'),
  session: null,
  item: null,
  offset: 0,
  timer: null,

  async open(item, startAt) {
    this.item = item;
    try {
      this.session = await api('/playback/sessions', {
        method: 'POST',
        body: { mediaId: item.id, device: { name: navigator.userAgent.split(' ').pop(), platform: 'WEB' }, capabilities: capabilities() },
      });
    } catch (error) {
      alert(error.message);
      return;
    }
    $('#player-title').textContent = item.title;
    $('#player-mode').textContent = this.session.mode.replace('_', ' ').toLowerCase();
    this.load(startAt);
    this.dialog.showModal();
    this.video.play().catch(() => {});
    this.timer = setInterval(() => this.saveProgress(), 10000);
  },

  // Direct play supports byte ranges, so seeking is native. Remuxed and transcoded
  // streams start at an offset chosen by the server instead.
  load(startAt) {
    const direct = this.session.mode === 'DIRECT_PLAY';
    this.offset = direct ? 0 : startAt;
    const params = new URLSearchParams({ access_token: token.accessToken });
    if (!direct && startAt > 0) params.set('start', String(startAt));
    this.video.src = `${this.session.streamUrl}?${params}`;
    if (direct && startAt > 0) {
      this.video.addEventListener('loadedmetadata', () => { this.video.currentTime = startAt; }, { once: true });
    }
  },

  position() {
    return this.offset + (this.video.currentTime || 0);
  },

  async saveProgress(completed) {
    if (!this.item || !hasScope('history.write')) return;
    const duration = this.item.duration ? this.item.duration / 1000 : (Number.isFinite(this.video.duration) ? this.video.duration : null);
    const position = duration ? Math.min(this.position(), duration) : this.position();
    if (position < 1 && !completed) return;
    await api(`/media/${this.item.id}/progress`, {
      method: 'PUT',
      body: { position, duration, ...(completed ? { completed: true } : {}) },
    }).catch(() => {});
  },

  // Direct-play range requests carry the token in the URL, so swap in the renewed one.
  onTokenRenewed() {
    if (!this.session || this.session.mode !== 'DIRECT_PLAY') return;
    const time = this.video.currentTime;
    const paused = this.video.paused;
    this.load(0);
    this.video.addEventListener('loadedmetadata', () => {
      this.video.currentTime = time;
      if (!paused) this.video.play().catch(() => {});
    }, { once: true });
  },

  async close() {
    clearInterval(this.timer);
    await this.saveProgress(this.video.ended);
    this.video.removeAttribute('src');
    this.video.load();
    if (this.session) {
      api(`/playback/${this.session.sessionId}`, { method: 'DELETE' }).catch(() => {});
    }
    this.session = null;
    this.item = null;
    if (this.dialog.open) this.dialog.close();
    route();
  },
};

player.video.addEventListener('pause', () => player.saveProgress());
player.video.addEventListener('ended', () => player.close());
$('#player-close').addEventListener('click', () => player.close());
player.dialog.addEventListener('cancel', (event) => {
  event.preventDefault();
  player.close();
});

// ---------------------------------------------------------------- routing

function go(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

async function route() {
  const [, view, id, extra] = (location.hash || '#/').split('/');
  try {
    if (view === 'library' && id) await viewLibrary(id, Number(extra) || 0);
    else if (view === 'media' && id) await viewMedia(id);
    else if (view === 'search') await viewSearch(decodeURIComponent(id || ''));
    else if (view === 'manage' && hasScope('library.write')) await viewManage();
    else if (view === 'tokens' && hasScope('profile')) await viewTokens();
    else await viewHome();
  } catch (error) {
    showError(error);
  }
}

// ---------------------------------------------------------------- startup

// Sign-in only works at the configured issuer address, because that is where the server
// allows the OAuth2 redirect back to. Explain a mismatch rather than failing mid-flow.
async function checkAddress() {
  let issuer;
  try {
    issuer = (await (await fetch('/.well-known/openid-configuration')).json()).issuer;
  } catch {
    return true;
  }
  const expected = new URL(issuer).origin;
  if (expected === location.origin) return true;
  $('.account').hidden = true;
  show(h('div', { class: 'panel' },
    h('h2', {}, 'Wrong address for this server'),
    h('p', {}, 'You opened OpenBased at ', h('strong', {}, location.origin), ', but it is configured for ',
      h('strong', {}, expected), '. Signing in only works at the configured address.'),
    h('p', {}, h('a', { href: expected + '/' }, `Open ${expected}`)),
    h('p', { class: 'status' }, 'To use this address instead, set ', h('code', {}, `issuer: ${location.origin}`),
      ' under openbased: in the server configuration (/etc/openbased/application.yml for the Linux service) '
      + 'and restart the server (sudo systemctl restart openbased).')));
  return false;
}

async function start() {
  const params = new URLSearchParams(location.search);

  if (location.pathname === '/callback') {
    // Inside the silent-renewal iframe: hand the code to the parent window.
    if (window.parent !== window) {
      window.parent.postMessage({ type: 'openbased-callback', code: params.get('code'), state: params.get('state') }, location.origin);
      return;
    }
    if (params.get('error')) {
      show(h('p', { class: 'error' }, `Sign-in failed: ${params.get('error_description') || params.get('error')}`));
      return;
    }
    try {
      await exchangeCode(params.get('code'), params.get('state'));
    } catch (error) {
      show(h('p', { class: 'error' }, error.message), h('button', { onclick: signIn }, 'Sign in again'));
      return;
    }
    const target = sessionStorage.getItem(RETURN_KEY) || '#/';
    sessionStorage.removeItem(RETURN_KEY);
    history.replaceState(null, '', '/' + target);
  }

  token = loadToken();
  if (!token) {
    if (!(await checkAddress())) return;
    await signIn();
    return;
  }
  scheduleRenewal();

  try {
    const me = await api('/users/me');
    $('#user').textContent = me.displayName;
  } catch {
    // The profile scope is optional for browsing.
  }
  $('#tokens').hidden = !hasScope('profile');
  $('#tokens').addEventListener('click', () => go('#/tokens'));
  $('#manage').hidden = !hasScope('library.write');
  $('#manage').addEventListener('click', () => go('#/manage'));
  $('#logout').addEventListener('click', () => {
    const idToken = token.idToken;
    sessionStorage.removeItem(TOKEN_KEY);
    const params = new URLSearchParams({ id_token_hint: idToken, post_logout_redirect_uri: location.origin + '/' });
    location.assign('/connect/logout?' + params);
  });
  $('#search').addEventListener('submit', (event) => {
    event.preventDefault();
    const q = new FormData(event.target).get('q').trim();
    if (q) go(`#/search/${encodeURIComponent(q)}`);
  });
  window.addEventListener('hashchange', route);

  await loadLibraries();
  await route();
}

start();
