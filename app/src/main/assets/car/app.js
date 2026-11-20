/*
 * BitChord car UI. Thin client only: every search, lyric, playlist and queue
 * edit is an RPC call to the phone. Nothing here talks to an outside service.
 */
(function () {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };
  const ico = function (name) { return '<svg class="icon"><use href="#i-' + name + '"/></svg>'; };

  const S = {
    ws: null, seq: 0, pending: {}, delay: 500, connected: false,
    state: null,
    page: 'now', prevPage: 'now',
    search: { songs: [], searchId: null, query: '' },
    playlists: [], playlist: null,
    lyrics: { lines: [], videoId: '', source: '' },
    lyricNodes: [], activeLyric: -1,
    panelOpen: false, panelTab: 'lyrics',
    fullscreen: false,
    seekEditing: false, volEditing: false, dragFrom: -1,
    lastQueueSig: '', lastLyricTrack: '',
    toastTimer: null, searchTimer: null,
  };

  // ---- transport ---------------------------------------------------------
  function setStatus(text, good, bad) {
    $('status').textContent = text;
    const dot = $('dot');
    dot.classList.toggle('on', !!good);
    dot.classList.toggle('bad', !!bad);
  }

  function toast(message) {
    const el = $('toast');
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(S.toastTimer);
    S.toastTimer = setTimeout(function () { el.classList.remove('show'); }, 3000);
  }

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      if (!S.ws || S.ws.readyState !== 1) {
        reject(new Error('폰과 연결되지 않았습니다'));
        return;
      }
      const id = String(++S.seq);
      S.pending[id] = { resolve: resolve, reject: reject };
      S.ws.send(JSON.stringify({ type: 'call', id: id, method: method, params: params || {} }));
      setTimeout(function () {
        if (S.pending[id]) {
          S.pending[id].reject(new Error('응답이 없습니다'));
          delete S.pending[id];
        }
      }, method === 'lyrics.get' ? 30000 : 15000);
    });
  }

  async function act(method, params) {
    try { return await call(method, params); }
    catch (e) { toast(e && e.message ? e.message : '요청을 처리하지 못했습니다'); }
  }

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(proto + '//' + location.host + '/ws?token=' + encodeURIComponent(window.CAR_TOKEN || ''));
    S.ws = ws;

    ws.onopen = function () {
      S.connected = true;
      S.delay = 500;
      setStatus('폰에 연결됨', true, false);
      $('banner').classList.add('hidden');
      refreshPlaylists();
    };

    ws.onmessage = function (ev) {
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.type === 'state') {
        S.state = m;
        renderPlayer();
      } else if (m.type === 'result') {
        const p = S.pending[m.id];
        if (!p) return;
        delete S.pending[m.id];
        if (m.ok === false) p.reject(new Error(m.error || m.message || '오류'));
        else p.resolve(m.data || {});
      } else if (m.type === 'error') {
        toast(m.message || '오류');
      }
    };

    ws.onclose = function () {
      S.connected = false;
      setStatus('연결 끊김', false, true);
      $('banner').classList.remove('hidden');
      setTimeout(connect, S.delay);
      S.delay = Math.min(S.delay * 2, 10000);
    };

    ws.onerror = function () { try { ws.close(); } catch (e) { /* already closed */ } };
  }

  // ---- small helpers -----------------------------------------------------
  function fmt(ms) {
    if (!isFinite(ms) || ms < 0) ms = 0;
    const sec = Math.floor(ms / 1000);
    return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');
  }

  function artUrl(url) {
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return '';
    return '/art?u=' + encodeURIComponent(url);
  }

  function setArt(img, url) {
    if (!img) return;
    const src = artUrl(url);
    if (src) {
      if (img.getAttribute('src') !== src) img.setAttribute('src', src);
      img.classList.remove('noart');
    } else {
      img.removeAttribute('src');
      img.classList.add('noart');
    }
  }

  function setUse(el, name) {
    if (el) el.querySelector('use').setAttribute('href', '#i-' + name);
  }

  function duration() {
    const s = S.state;
    return s && Number(s.durationMs) > 0 ? Number(s.durationMs) : 0;
  }

  function position() {
    const s = S.state;
    if (!s) return 0;
    let p = Number(s.positionMs) || 0;
    if (s.advancing) p += Date.now() - (Number(s.positionAt) || Date.now());
    const d = duration();
    return d > 0 ? Math.min(p, d) : p;
  }

  function queueSig() {
    const s = S.state;
    if (!s) return '';
    return (s.queue || []).map(function (t) { return t.videoId + '|' + t.title; }).join('~~') + '#' + s.queueIndex;
  }

  // ---- player rendering --------------------------------------------------
  function renderPlayer() {
    const s = S.state;
    if (!s) return;
    const t = s.track || {};
    const title = t.title || '—';
    const sub = t.artist || t.album || '';

    $('heroTitle').textContent = title;
    $('heroArtist').textContent = sub;
    $('heroAlbum').textContent = t.album && t.artist ? t.album : '';
    $('pillTitle').textContent = title;
    $('pillArtist').textContent = sub;
    $('fsTitle').textContent = title;
    $('fsArtist').textContent = sub;

    setArt($('heroArt'), t.artwork);
    setArt($('pillArtImg'), t.artwork);
    setArt($('fsArt'), t.artwork);
    const bg = artUrl(t.artwork);
    $('fsBg').style.backgroundImage = bg ? 'url("' + bg + '")' : 'none';

    const iconName = s.playing ? 'pause' : 'play';
    ['play', 'pillPlay', 'fsPlay'].forEach(function (id) { setUse($(id), iconName); });

    ['shuffle', 'pillShuffle', 'fsShuffle'].forEach(function (id) {
      $(id).classList.toggle('on', !!s.shuffle);
    });
    const repeatIcon = s.repeatMode === 1 ? 'repeat-one' : 'repeat';
    ['repeat', 'pillRepeat', 'fsRepeat'].forEach(function (id) {
      setUse($(id), repeatIcon);
      $(id).classList.toggle('on', (s.repeatMode || 0) !== 0);
    });

    const pos = position();
    const dur = duration();
    const ratio = dur > 0 ? pos / dur : 0;
    if (!S.seekEditing) {
      $('seek').value = String(Math.round(ratio * 1000));
      $('fsSeek').value = String(Math.round(ratio * 1000));
    }
    $('pillBar').style.width = (ratio * 100) + '%';
    $('heroPos').textContent = fmt(pos);
    $('heroDur').textContent = fmt(dur);
    $('fsPos').textContent = fmt(pos);
    $('fsDur').textContent = fmt(dur);

    if (!S.volEditing) {
      const vol = $('volume');
      vol.max = String(s.volumeMax || 15);
      vol.value = String(s.volume || 0);
    }

    const count = (s.queue || []).length;
    $('heroQueueCount').textContent = '(' + count + ')';
    $('panelQueueCount').textContent = '(' + count + ')';

    const sig = queueSig();
    if (sig !== S.lastQueueSig) {
      S.lastQueueSig = sig;
      renderQueue();
      renderMiniQueue();
    }

    if (S.panelOpen && S.panelTab === 'lyrics') {
      const vid = (t && t.videoId) || '';
      if (vid && vid !== S.lastLyricTrack) {
        S.lastLyricTrack = vid;
        loadLyrics();
      }
      updateLyrics();
    }
  }

  // ---- track rows --------------------------------------------------------
  function makeRow(track, index, onClick, actions) {
    const row = document.createElement('div');
    row.className = 'track-row';
    row.innerHTML =
      '<div class="idx">' + (index + 1) + '</div>' +
      '<img alt="" />' +
      '<div class="t"><div class="t-name"></div><div class="t-sub"></div></div>' +
      '<div class="dur"></div>' +
      '<div class="row-actions"></div>';
    row.querySelector('.t-name').textContent = track.title || '?';
    row.querySelector('.t-sub').textContent = track.artist || '';
    row.querySelector('.dur').textContent = track.durationText || '';
    setArt(row.querySelector('img'), track.thumbnailUrl || track.artwork);
    row.onclick = onClick;

    const box = row.querySelector('.row-actions');
    (actions || []).forEach(function (a) {
      const b = document.createElement('button');
      b.title = a.title;
      b.innerHTML = ico(a.icon);
      if (a.danger) b.classList.add('danger');
      b.onclick = function (e) { e.stopPropagation(); a.run(); };
      box.appendChild(b);
    });
    return row;
  }

  function renderMiniQueue() {
    const box = $('heroQueue');
    box.textContent = '';
    const s = S.state;
    const rows = (s && s.queue) || [];
    rows.slice(0, 4).forEach(function (t, i) {
      const row = makeRow(t, i, function () { act('player.skipTo', { index: i }); }, null);
      if (i === s.queueIndex) row.classList.add('active');
      box.appendChild(row);
    });
  }

  function renderResults() {
    const box = $('results');
    box.textContent = '';
    const songs = S.search.songs;
    if (!songs.length) {
      box.innerHTML = '<div class="empty">' + (S.search.query ? '결과가 없습니다.' : '검색어를 입력하세요.') + '</div>';
      return;
    }
    songs.forEach(function (song, i) {
      box.appendChild(makeRow(song, i, function () {
        act('player.playSearch', { searchId: S.search.searchId, index: i });
      }, null));
    });
  }

  // ---- search ------------------------------------------------------------
  async function doSearch() {
    const q = $('q').value.trim();
    if (!q) return;
    S.search.query = q;
    $('results').innerHTML = '<div class="empty">폰에서 검색하는 중…</div>';
    try {
      const data = await call('search', { query: q });
      S.search.songs = (data.songs || []);
      S.search.searchId = data.searchId || null;
      renderResults();
    } catch (e) {
      $('results').innerHTML = '<div class="empty">검색에 실패했습니다.</div>';
      toast(e && e.message ? e.message : '검색 실패');
    }
  }

  // ---- playlists ---------------------------------------------------------
  function renderPlaylists() {
    const box = $('playlists');
    box.textContent = '';
    if (!S.playlists.length) {
      box.innerHTML = '<div class="empty">저장된 플레이리스트가 없습니다.</div>';
      return;
    }
    const labels = { local: '저장됨', offline: '기기', spotify: 'Spotify' };
    S.playlists.forEach(function (p) {
      const card = document.createElement('div');
      card.className = 'pl-card';
      card.innerHTML =
        '<img alt="" />' +
        '<span class="pl-badge">' + (labels[p.kind] || '목록') + '</span>' +
        '<div class="pl-name"></div><div class="pl-sub"></div>';
      card.querySelector('.pl-name').textContent = p.title || '플레이리스트';
      card.querySelector('.pl-sub').textContent = p.subtitle || '';
      setArt(card.querySelector('img'), p.artwork);
      card.onclick = function () { openPlaylist(p.id, p.title); };
      box.appendChild(card);
    });
  }

  async function refreshPlaylists() {
    try {
      const data = await call('playlist.list');
      S.playlists = data.playlists || [];
      renderPlaylists();
    } catch (e) { /* offline: the empty state already explains itself */ }
  }

  async function openPlaylist(id, title) {
    S.playlist = { id: id, title: title || '플레이리스트', songs: [] };
    goto('playlist');
    $('playlistHead').innerHTML =
      '<img alt="" /><div><div class="eyebrow">플레이리스트</div><h1></h1><div class="pl-meta"></div>' +
      '<button class="pill-btn" id="playAll">' + ico('play') + '<span>모두 재생</span></button></div>';
    $('playlistHead').querySelector('h1').textContent = S.playlist.title;
    $('playlistHead').querySelector('.pl-meta').textContent = '폰에서 불러오는 중…';
    $('playlistHead').querySelector('#playAll').onclick = function () {
      act('playlist.play', { id: S.playlist.id, index: 0 });
    };
    $('playlistTracks').innerHTML = '<div class="empty">불러오는 중…</div>';
    try {
      const data = await call('playlist.tracks', { id: id });
      S.playlist.songs = data.songs || [];
      $('playlistHead').querySelector('.pl-meta').textContent = S.playlist.songs.length + '곡';
      const box = $('playlistTracks');
      box.textContent = '';
      if (!S.playlist.songs.length) {
        box.innerHTML = '<div class="empty">곡이 없습니다.</div>';
        return;
      }
      S.playlist.songs.forEach(function (song, i) {
        box.appendChild(makeRow(song, i, function () {
          act('playlist.play', { id: S.playlist.id, index: i });
        }, null));
      });
    } catch (e) {
      $('playlistTracks').innerHTML = '<div class="empty">불러오지 못했습니다.</div>';
      toast(e && e.message ? e.message : '플레이리스트 오류');
    }
  }

  // ---- side panel: queue -------------------------------------------------
  function renderQueue() {
    const box = $('queueView');
    box.textContent = '';
    const s = S.state;
    const rows = (s && s.queue) || [];
    if (!rows.length) {
      box.innerHTML = '<div class="empty">재생목록이 비어 있습니다.</div>';
      return;
    }
    rows.forEach(function (t, i) {
      const row = document.createElement('div');
      row.className = 'q-row' + (i === s.queueIndex ? ' active' : '');
      row.innerHTML =
        '<div class="idx">' + (i + 1) + '</div><img alt="" />' +
        '<div class="t"><div class="t-name"></div><div class="t-sub"></div></div>' +
        '<div class="q-actions"></div>';
      row.querySelector('.t-name').textContent = t.title || '?';
      row.querySelector('.t-sub').textContent = t.artist || '';
      setArt(row.querySelector('img'), t.artwork);
      row.onclick = function () { act('player.skipTo', { index: i }); };

      const actions = [
        { icon: 'up', title: '위로', run: function () { if (i > 0) act('queue.move', { from: i, to: i - 1 }); } },
        { icon: 'down', title: '아래로', run: function () { if (i < rows.length - 1) act('queue.move', { from: i, to: i + 1 }); } },
        { icon: 'trash', title: '삭제', danger: true, run: function () { act('queue.remove', { index: i }); } },
      ];
      const abox = row.querySelector('.q-actions');
      actions.forEach(function (a) {
        const b = document.createElement('button');
        b.title = a.title;
        b.innerHTML = ico(a.icon);
        if (a.danger) b.classList.add('danger');
        b.onclick = function (e) { e.stopPropagation(); a.run(); };
        abox.appendChild(b);
      });

      row.draggable = true;
      row.ondragstart = function () { S.dragFrom = i; };
      row.ondragover = function (e) { e.preventDefault(); };
      row.ondrop = function (e) {
        e.preventDefault();
        if (S.dragFrom >= 0 && S.dragFrom !== i) act('queue.move', { from: S.dragFrom, to: i });
        S.dragFrom = -1;
      };
      box.appendChild(row);
    });
  }

  // ---- side panel: lyrics ------------------------------------------------
  function wordProgress(w, pos) {
    if (pos <= w.startMs) return 0;
    if (pos >= w.endMs) return 1;
    const all = Math.max(1, w.text ? w.text.length : 1);
    const syl = w.syllables || [];
    if (syl.length) {
      let done = 0;
      for (let i = 0; i < syl.length; i++) {
        const s = syl[i];
        if (pos < s.startMs) break;
        const span = Math.max(1, s.endChar - s.startChar);
        if (pos < s.endMs) done += span * ((pos - s.startMs) / Math.max(1, s.endMs - s.startMs));
        else done += span;
      }
      return Math.max(0, Math.min(1, done / all));
    }
    return Math.max(0, Math.min(1, (pos - w.startMs) / Math.max(1, w.endMs - w.startMs)));
  }

  function renderLyrics() {
    const box = $('lyricsView');
    box.textContent = '';
    S.lyricNodes = [];
    S.activeLyric = -1;
    if (!S.lyrics.lines.length) {
      box.innerHTML = '<div class="empty">이 곡의 가사를 찾지 못했습니다.</div>';
      return;
    }
    if (S.lyrics.source) {
      const src = document.createElement('div');
      src.className = 'lyric-source';
      src.textContent = S.lyrics.source.toUpperCase();
      box.appendChild(src);
    }
    S.lyrics.lines.forEach(function (line) {
      const el = document.createElement('div');
      el.className = 'lyric-line' + (line.text ? '' : ' gap');
      const spans = [];
      if (line.text && line.words && line.words.length) {
        // Walk the source text so the spaces between timed words survive.
        let offset = 0;
        (line.words || []).forEach(function (w) {
          const at = line.text.indexOf(w.text, offset);
          const start = at >= 0 ? at : offset;
          if (start > offset) el.appendChild(document.createTextNode(line.text.slice(offset, start)));
          const span = document.createElement('span');
          span.className = 'lw';
          span.textContent = w.text;
          el.appendChild(span);
          spans.push({ el: span, word: w });
          offset = start + w.text.length;
        });
        if (offset < line.text.length) el.appendChild(document.createTextNode(line.text.slice(offset)));
      } else {
        el.textContent = line.text || '♪';
      }
      box.appendChild(el);
      S.lyricNodes.push({ el: el, line: line, spans: spans });
    });
  }

  function updateLyrics() {
    if (!S.lyricNodes.length) return;
    const pos = position();
    let active = -1;
    for (let i = 0; i < S.lyrics.lines.length; i++) {
      if (S.lyrics.lines[i].timeMs <= pos) active = i;
      else break;
    }
    if (active !== S.activeLyric) {
      S.activeLyric = active;
      const node = S.lyricNodes[active];
      if (node) {
        const box = $('lyricsView');
        box.scrollTo({
          top: Math.max(0, node.el.offsetTop - box.clientHeight / 2 + node.el.offsetHeight / 2),
          behavior: 'smooth',
        });
      }
    }
    S.lyricNodes.forEach(function (node, i) {
      node.el.classList.toggle('active', i === active);
      if (i !== active) return;
      node.spans.forEach(function (item) {
        const p = wordProgress(item.word, pos);
        item.el.style.setProperty('--p', Math.round(p * 100) + '%');
      });
    });
  }

  async function loadLyrics() {
    const box = $('lyricsView');
    box.innerHTML = '<div class="empty">폰에서 가사를 불러오는 중…</div>';
    S.lyricNodes = [];
    S.activeLyric = -1;
    try {
      const data = await call('lyrics.get', {});
      S.lyrics = {
        lines: data.lines || [],
        videoId: data.videoId || '',
        source: data.source || '',
      };
      renderLyrics();
      updateLyrics();
    } catch (e) {
      box.innerHTML = '<div class="empty">가사를 불러오지 못했습니다.</div>';
    }
  }

  // ---- navigation --------------------------------------------------------
  function goto(page) {
    S.prevPage = S.page;
    S.page = page;
    document.querySelectorAll('.page').forEach(function (p) {
      p.classList.toggle('active', p.dataset.page === page);
    });
    const navPage = page === 'playlist' ? 'library' : page;
    document.querySelectorAll('.nav-item').forEach(function (b) {
      b.classList.toggle('active', b.dataset.page === navPage);
    });
    const names = { now: '지금 재생', search: '검색', library: '라이브러리', playlist: '플레이리스트' };
    $('crumb').textContent = names[page] || 'BitChord';
    $('main').scrollTop = 0;
  }

  function setPanelTab(tab) {
    S.panelTab = tab;
    document.querySelectorAll('.tab').forEach(function (b) {
      b.classList.toggle('active', b.dataset.tab === tab);
    });
    $('lyricsView').classList.toggle('hidden', tab !== 'lyrics');
    $('queueView').classList.toggle('hidden', tab !== 'queue');
    if (tab === 'lyrics') {
      const vid = S.state && S.state.track && S.state.track.videoId || '';
      if (vid !== S.lastLyricTrack || !S.lyricNodes.length) {
        S.lastLyricTrack = vid;
        loadLyrics();
      } else updateLyrics();
    } else renderQueue();
  }

  function openPanel(tab) {
    S.panelOpen = true;
    $('app').classList.add('panel-open');
    $('panel').classList.remove('hidden');
    setPanelTab(tab || S.panelTab);
  }

  function closePanel() {
    S.panelOpen = false;
    $('app').classList.remove('panel-open');
    $('panel').classList.add('hidden');
  }

  function setFullscreen(open) {
    S.fullscreen = open;
    $('fullscreen').classList.toggle('hidden', !open);
    if (open) renderPlayer();
  }

  // ---- bindings ----------------------------------------------------------
  function bind(id, method, params) {
    const el = $(id);
    if (!el) return;
    el.onclick = function () {
      if (method === 'toggleFullscreen') setFullscreen(!S.fullscreen);
      else if (method === 'closeFullscreen') setFullscreen(false);
      else if (method === 'togglePanel') S.panelOpen ? closePanel() : openPanel(params);
      else act(method, typeof params === 'function' ? params() : params);
    };
  }

  function bindSeek(id) {
    const el = $(id);
    el.addEventListener('pointerdown', function () { S.seekEditing = true; });
    el.addEventListener('pointerup', function () {
      S.seekEditing = false;
      const d = duration();
      if (d > 0) act('player.seek', { positionMs: Math.round(Number(el.value) / 1000 * d) });
    });
    el.addEventListener('change', function () {
      S.seekEditing = false;
      const d = duration();
      if (d > 0) act('player.seek', { positionMs: Math.round(Number(el.value) / 1000 * d) });
    });
  }

  function bindVolume() {
    const el = $('volume');
    const send = function () { act('player.volume', { level: Number(el.value) }); };
    el.addEventListener('pointerdown', function () { S.volEditing = true; });
    el.addEventListener('pointerup', function () { S.volEditing = false; send(); });
    el.addEventListener('change', function () { S.volEditing = false; send(); });
  }

  function bindAll() {
    ['play', 'pillPlay', 'fsPlay'].forEach(function (id) { bind(id, 'player.toggle'); });
    ['prev', 'pillPrev', 'fsPrev'].forEach(function (id) { bind(id, 'player.previous'); });
    ['next', 'pillNext', 'fsNext'].forEach(function (id) { bind(id, 'player.next'); });
    ['shuffle', 'pillShuffle', 'fsShuffle'].forEach(function (id) { bind(id, 'player.shuffle'); });
    ['repeat', 'pillRepeat', 'fsRepeat'].forEach(function (id) { bind(id, 'player.repeat'); });
    ['like', 'fsLike'].forEach(function (id) { bind(id, 'player.like'); });

    $('pillArt').onclick = function () { setFullscreen(true); };
    bind('fsClose', 'closeFullscreen');
    ['openPanel', 'openLyrics'].forEach(function (id) { $(id).onclick = function () { openPanel('lyrics'); }; });
    ['openQueue', 'fsQueue'].forEach(function (id) { $(id).onclick = function () { openPanel('queue'); }; });
    $('fsLyrics').onclick = function () { setFullscreen(false); openPanel('lyrics'); };
    $('closePanel').onclick = closePanel;

    document.querySelectorAll('.tab').forEach(function (b) {
      b.onclick = function () { setPanelTab(b.dataset.tab); };
    });
    document.querySelectorAll('.nav-item').forEach(function (b) {
      b.onclick = function () { goto(b.dataset.page); };
    });
    $('backBtn').onclick = function () {
      if (S.page === 'playlist') goto('library');
      else goto('now');
    };

    $('q').addEventListener('input', function () {
      clearTimeout(S.searchTimer);
      S.searchTimer = setTimeout(doSearch, 350);
    });
    $('q').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { clearTimeout(S.searchTimer); doSearch(); }
    });

    bindSeek('seek');
    bindSeek('fsSeek');
    bindVolume();

    document.querySelectorAll('img').forEach(function (img) {
      img.addEventListener('error', function () { img.classList.add('noart'); });
    });

    setInterval(function () {
      if (!S.state) return;
      renderPlayer();
      if (S.panelOpen && S.panelTab === 'lyrics') updateLyrics();
    }, 250);
  }

  bindAll();
  connect();
})();
