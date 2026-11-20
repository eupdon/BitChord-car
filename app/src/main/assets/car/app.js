(function () {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };
  const statusEl = $('status');

  let ws = null;
  let state = null;
  let results = [];
  let searchId = null;
  let delay = 500;
  let counter = 0;

  function uid() { return String(++counter); }

  function setStatus(text, bad) {
    statusEl.textContent = text;
    statusEl.classList.toggle('bad', !!bad);
  }

  function send(obj) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  }

  function cmd(name, extra) {
    const payload = { type: 'command', id: uid(), name: name };
    if (extra) Object.keys(extra).forEach(function (k) { payload[k] = extra[k]; });
    send(payload);
  }

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = proto + '//' + location.host + '/ws?token=' + encodeURIComponent(window.CAR_TOKEN);
    ws = new WebSocket(url);

    ws.onopen = function () {
      setStatus('connected');
      delay = 500;
      cmd('queue');
    };

    ws.onmessage = function (ev) {
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }

      if (m.type === 'state') {
        state = m;
        render();
      } else if (m.type === 'result') {
        if (!m.ok) {
          setStatus(m.message || 'error', true);
        } else if (m.songs) {
          results = m.songs;
          searchId = m.searchId;
          setStatus('results: ' + results.length);
          renderResults();
        }
      } else if (m.type === 'error') {
        setStatus(m.message || 'error', true);
      }
    };

    ws.onclose = function () {
      setStatus('reconnecting…', true);
      setTimeout(connect, delay);
      delay = Math.min(delay * 2, 10000);
    };

    ws.onerror = function () {
      try { ws.close(); } catch (e) { /* already closing */ }
    };
  }

  function fmt(ms) {
    if (!isFinite(ms) || ms < 0) ms = 0;
    const s = Math.floor(ms / 1000);
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }

  function render() {
    if (!state) return;

    const t = state.track;
    $('title').textContent = t && t.title ? t.title : '—';
    $('artist').textContent = t ? (t.artist || t.album || '') : '';

    const art = $('art');
    if (t && t.artwork) {
      if (art.getAttribute('src') !== t.artwork) art.setAttribute('src', t.artwork);
    } else {
      art.removeAttribute('src');
    }

    const dur = state.durationMs > 0 ? state.durationMs : 0;
    let pos = state.positionMs || 0;
    if (state.advancing) pos += Date.now() - (state.positionAt || Date.now());
    if (dur) pos = Math.min(pos, dur);

    $('bar').style.width = (dur ? (pos / dur) * 100 : 0) + '%';
    $('pos').textContent = fmt(pos);
    $('dur').textContent = fmt(dur);

    $('play').textContent = state.playing ? '❚❚' : '▶';
    $('shuffle').classList.toggle('on', !!state.shuffle);
    $('repeat').textContent = state.repeatMode === 1 ? '🔂' : (state.repeatMode === 2 ? '🔁' : '↔');
    $('qcount').textContent = '(' + ((state.queue && state.queue.length) || 0) + ')';

    renderQueue();
  }

  function rowButton(label, onClick, active) {
    const b = document.createElement('button');
    b.className = 'row' + (active ? ' active' : '');
    b.textContent = label;
    b.onclick = onClick;
    return b;
  }

  function renderQueue() {
    const box = $('queue');
    box.textContent = '';
    (state.queue || []).forEach(function (t, i) {
      const label = (i + 1) + '. ' + (t.title || '?') + ' — ' + (t.artist || '');
      box.appendChild(rowButton(label, function () {
        cmd('skipTo', { index: i });
      }, i === state.queueIndex));
    });
  }

  function renderResults() {
    const box = $('results');
    box.textContent = '';
    results.forEach(function (s, i) {
      const label = (i + 1) + '. ' + s.title + ' — ' + (s.artist || '') +
        (s.durationText ? ' [' + s.durationText + ']' : '');
      box.appendChild(rowButton(label, function () {
        cmd('playSearch', { searchId: searchId, index: i });
      }, false));
    });
  }

  function doSearch() {
    const q = $('q').value.trim();
    if (!q) return;
    setStatus('searching…');
    cmd('search', { query: q });
  }

  $('play').onclick = function () { cmd('toggle'); };
  $('prev').onclick = function () { cmd('previous'); };
  $('next').onclick = function () { cmd('next'); };
  $('shuffle').onclick = function () { cmd('shuffle'); };
  $('repeat').onclick = function () { cmd('repeat'); };
  $('like').onclick = function () { cmd('like'); };
  $('voldown').onclick = function () { cmd('volume', { delta: -1 }); };
  $('volup').onclick = function () { cmd('volume', { delta: 1 }); };
  $('search').onclick = doSearch;
  $('q').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') doSearch();
  });

  setInterval(function () { if (state) render(); }, 1000);
  connect();
})();
