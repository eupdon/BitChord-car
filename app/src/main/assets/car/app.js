/*
 * BitChord car UI. Thin client only: every search, lyric, playlist, queue edit
 * and home-feed read is an RPC call to the phone. Nothing here talks to an
 * outside service or holds playback state of its own.
 */
(function () {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };
  const ico = function (name) { return '<svg class="icon"><use href="#i-' + name + '"/></svg>'; };

  const S = {
    ws: null, seq: 0, pending: {}, delay: 500, connected: false,
    state: null, stateAt: 0,
    page: 'home', prevPage: 'home',
    home: [], homeLoading: false,
    search: {
      songs: [], searchId: null, query: '', filter: 'ALL',
      top: null, sections: [], recents: [],
    },
    library: [], libraryLoading: false, playlist: null,
    lyrics: { lines: [], videoId: '', source: '' },
    lyricNodes: [], activeLyric: -1,
    sheetOpen: false, sheetTab: 'lyrics',
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
      }, ['lyrics.get', 'home', 'library', 'search.detailed', 'play.item'].indexOf(method) >= 0
        ? 30000 : 15000);
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
      loadHome();
      loadSearchRecents();
      loadLibrary();
    };

    ws.onmessage = function (ev) {
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.type === 'state') {
        // The phone and the car do not share a clock: sampling the playhead
        // here, on arrival, avoids its offset leaking into the seek bar.
        S.state = m;
        S.stateAt = Date.now();
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
    if (s.advancing) {
      // Extrapolate from when this frame arrived in the browser, never from
      // the phone's `positionAt`: the two clocks can be tens of seconds apart.
      const elapsed = Date.now() - (S.stateAt || Date.now());
      if (elapsed > 0 && elapsed < 15000) p += elapsed;
    }
    const d = duration();
    return d > 0 ? Math.min(p, d) : p;
  }

  function queueSig() {
    const s = S.state;
    if (!s) return '';
    return (s.queue || []).map(function (t) { return t.videoId + '|' + t.title; }).join('~~') + '#' + s.queueIndex;
  }

  // ---- home --------------------------------------------------------------
  async function loadHome() {
    if (S.homeLoading) return;
    S.homeLoading = true;
    try {
      const data = await call('home');
      S.home = data.shelves || [];
      renderHome();
    } catch (e) {
      $('homeShelves').innerHTML =
        '<div class="empty">홈 피드를 불러오지 못했습니다.<br>폰의 YouTube Music 연결을 확인해 주세요.</div>';
    } finally {
      S.homeLoading = false;
    }
  }

  function renderHome() {
    const box = $('homeShelves');
    box.textContent = '';
    if (!S.home.length) {
      box.innerHTML = '<div class="empty">표시할 홈 피드가 없습니다.</div>';
      return;
    }
    S.home.forEach(function (shelf, si) {
      const sec = document.createElement('div');
      sec.className = 'shelf';
      const title = document.createElement('div');
      title.className = 'shelf-title';
      title.textContent = shelf.title || '추천';
      sec.appendChild(title);
      if (shelf.subtitle) {
        const sub = document.createElement('div');
        sub.className = 'shelf-sub';
        sub.textContent = shelf.subtitle;
        sec.appendChild(sub);
      }
      const row = document.createElement('div');
      const layout = shelf.layout || 'CARDS';
      row.className = 'shelf-row' + (layout === 'LIST' ? ' list' : layout === 'VIDEO' ? ' video' : '');
      (shelf.items || []).forEach(function (item, ii) {
        const card = document.createElement('div');
        const playable = !!item.videoId;
        card.className = 'shelf-card' + (playable ? '' : ' disabled');
        const img = document.createElement('img');
        img.alt = '';
        setArt(img, item.thumbnailUrl);
        card.appendChild(img);
        const name = document.createElement('div');
        name.className = 'sc-name';
        name.textContent = item.title || '';
        card.appendChild(name);
        const subEl = document.createElement('div');
        subEl.className = 'sc-sub';
        subEl.textContent = item.subtitle || '';
        card.appendChild(subEl);
        if (playable) {
          card.onclick = function () { act('home.play', { shelf: si, item: ii }); };
        }
        row.appendChild(card);
      });
      sec.appendChild(row);
      box.appendChild(sec);
    });
  }

  // ---- player rendering --------------------------------------------------
  function renderPlayer() {
    const s = S.state;
    if (!s) return;
    const t = s.track || {};
    const title = t.title || '—';
    const sub = t.artist || t.album || '';

    $('pillTitle').textContent = title;
    $('pillArtist').textContent = sub;
    $('psTitle').textContent = title;
    $('psArtist').textContent = sub;

    setArt($('pillArtImg'), t.artwork);
    setArt($('psArt'), t.artwork);
    const bg = artUrl(t.artwork);
    $('psBg').style.backgroundImage = bg ? 'url("' + bg + '")' : 'none';

    const iconName = s.playing ? 'pause' : 'play';
    ['pillPlay', 'psPlay'].forEach(function (id) { setUse($(id), iconName); });

    ['pillShuffle', 'psShuffle'].forEach(function (id) { $(id).classList.toggle('on', !!s.shuffle); });
    const repeatIcon = s.repeatMode === 1 ? 'repeat-one' : 'repeat';
    ['pillRepeat', 'psRepeat'].forEach(function (id) {
      setUse($(id), repeatIcon);
      $(id).classList.toggle('on', (s.repeatMode || 0) !== 0);
    });

    const pos = position();
    const dur = duration();
    const ratio = dur > 0 ? pos / dur : 0;
    if (!S.seekEditing) $('psSeek').value = String(Math.round(ratio * 1000));
    $('pillBar').style.width = (ratio * 100) + '%';
    $('psPos').textContent = fmt(pos);
    $('psDur').textContent = fmt(dur);

    if (!S.volEditing) {
      const vol = $('volume');
      vol.max = String(s.volumeMax || 15);
      vol.value = String(s.volume || 0);
    }

    const count = (s.queue || []).length;
    $('psQueueCount').textContent = '(' + count + ')';

    const sig = queueSig();
    if (sig !== S.lastQueueSig) {
      S.lastQueueSig = sig;
      renderQueue();
    }

    if (S.sheetOpen && S.sheetTab === 'lyrics') {
      const vid = (t && t.videoId) || '';
      if (vid && vid !== S.lastLyricTrack) {
        S.lastLyricTrack = vid;
        loadLyrics();
      }
      updateLyrics();
    }
  }

  // ---- track rows --------------------------------------------------------
  function makeRow(track, index, onClick) {
    const row = document.createElement('div');
    row.className = 'track-row';
    row.innerHTML =
      '<div class="idx">' + (index + 1) + '</div>' +
      '<img alt="" />' +
      '<div class="t"><div class="t-name"></div><div class="t-sub"></div></div>' +
      '<div class="dur"></div>';
    row.querySelector('.t-name').textContent = track.title || '?';
    row.querySelector('.t-sub').textContent = track.artist || track.subtitle || '';
    row.querySelector('.dur').textContent = track.durationText || '';
    setArt(row.querySelector('img'), track.thumbnailUrl || track.artwork);
    row.onclick = onClick;
    return row;
  }

  function makeCard(item, onClick) {
    const card = document.createElement('div');
    card.className = 'result-card';
    card.innerHTML = '<img alt="" /><div class="rc-name"></div><div class="rc-sub"></div>';
    card.querySelector('.rc-name').textContent = item.title || '';
    card.querySelector('.rc-sub').textContent = item.subtitle || '';
    setArt(card.querySelector('img'), item.thumbnailUrl);
    card.onclick = onClick;
    return card;
  }

  /** A search/library item is either played from the cached result set or browsed on the phone. */
  function playItem(item) {
    if (!item || !item.id) return;
    if (item.kind === 'local') {
      openPlaylist(item.id, item.title);
      return;
    }
    if (item.playIndex >= 0 && S.search.searchId && S.search.query) {
      act('player.playSearch', { searchId: S.search.searchId, index: item.playIndex });
      return;
    }
    act('play.item', {
      kind: item.kind, id: item.id,
      title: item.title, artist: item.subtitle,
      thumbnailUrl: item.thumbnailUrl,
    });
  }

  function renderResults() {
    const box = $('results');
    box.textContent = '';
    const s = S.search;

    if (!s.query) {
      if (s.recents.length) {
        const sec = document.createElement('div');
        sec.className = 'search-section';
        sec.innerHTML = '<h2>최근 검색</h2>';
        const grid = document.createElement('div');
        grid.className = 'result-grid';
        s.recents.forEach(function (item) {
          grid.appendChild(makeCard(item, function () {
            $('q').value = item.title;
            doSearch();
          }));
        });
        sec.appendChild(grid);
        box.appendChild(sec);
      } else {
        box.innerHTML = '<div class="empty">검색어를 입력하세요.</div>';
      }
      return;
    }

    if (!s.songs.length && !s.sections.length) {
      box.innerHTML = '<div class="empty">결과가 없습니다.</div>';
      return;
    }

    if (s.top) {
      const top = document.createElement('div');
      top.className = 'top-result';
      top.innerHTML =
        '<img alt="" /><div><div class="tr-kind">Top Result</div>' +
        '<div class="tr-title"></div><div class="tr-sub"></div></div>';
      top.querySelector('.tr-title').textContent = s.top.title || '';
      top.querySelector('.tr-sub').textContent = s.top.subtitle || '';
      setArt(top.querySelector('img'), s.top.thumbnailUrl);
      top.onclick = function () { playItem(s.top); };
      box.appendChild(top);
    }

    s.sections.forEach(function (section) {
      const sec = document.createElement('div');
      sec.className = 'search-section';
      const h = document.createElement('h2');
      h.textContent = section.title || '';
      sec.appendChild(h);
      const rows = (section.items || []).filter(function (item) {
        return item.playIndex >= 0 || item.kind === 'song' || item.kind === 'video';
      });
      const cards = (section.items || []).filter(function (item) {
        return item.playIndex < 0 && item.kind !== 'song' && item.kind !== 'video';
      });
      if (rows.length) {
        const list = document.createElement('div');
        list.className = 'track-list';
        rows.forEach(function (item, i) {
          list.appendChild(makeRow(item, i, function () { playItem(item); }));
        });
        sec.appendChild(list);
      }
      if (cards.length) {
        const grid = document.createElement('div');
        grid.className = 'result-grid';
        cards.forEach(function (item) {
          grid.appendChild(makeCard(item, function () { playItem(item); }));
        });
        sec.appendChild(grid);
      }
      box.appendChild(sec);
    });
  }

  // ---- search ------------------------------------------------------------
  async function doSearch() {
    const q = $('q').value.trim();
    if (!q) {
      S.search.query = '';
      S.search.songs = [];
      S.search.searchId = null;
      S.search.top = null;
      S.search.sections = [];
      renderResults();
      return;
    }
    S.search.query = q;
    $('results').innerHTML = '<div class="empty">폰에서 검색하는 중…</div>';
    try {
      const data = await call('search.detailed', { query: q, filter: S.search.filter });
      S.search.songs = data.songs || [];
      S.search.searchId = data.searchId || null;
      S.search.top = data.top || null;
      S.search.sections = data.sections || [];
      renderResults();
    } catch (e) {
      $('results').innerHTML = '<div class="empty">검색에 실패했습니다.</div>';
      toast(e && e.message ? e.message : '검색 실패');
    }
  }

  async function loadSearchRecents() {
    try {
      const data = await call('search.recents');
      S.search.recents = data.items || [];
      if (!S.search.query) renderResults();
    } catch (e) { /* the empty search state still renders without recents */ }
  }

  // ---- library -----------------------------------------------------------
  async function loadLibrary() {
    if (S.libraryLoading) return;
    S.libraryLoading = true;
    try {
      const data = await call('library');
      S.library = data.sections || [];
      renderLibrary();
    } catch (e) {
      $('librarySections').innerHTML =
        '<div class="empty">라이브러리를 불러오지 못했습니다.<br>폰의 YouTube Music 연결을 확인해 주세요.</div>';
    } finally {
      S.libraryLoading = false;
    }
  }

  function renderLibrary() {
    const box = $('librarySections');
    box.textContent = '';
    if (!S.library.length) {
      box.innerHTML = '<div class="empty">라이브러리가 비어 있습니다.</div>';
      return;
    }
    S.library.forEach(function (section) {
      const sec = document.createElement('div');
      sec.className = 'library-section';
      const h = document.createElement('h2');
      h.textContent = section.title || '라이브러리';
      sec.appendChild(h);
      if (section.kind === 'tracks') {
        const list = document.createElement('div');
        list.className = 'track-list';
        section.items.forEach(function (item, i) {
          list.appendChild(makeRow(item, i, function () { playItem(item); }));
        });
        sec.appendChild(list);
      } else {
        const grid = document.createElement('div');
        grid.className = 'library-grid';
        section.items.forEach(function (item) {
          grid.appendChild(makeCard(item, function () { playItem(item); }));
        });
        sec.appendChild(grid);
      }
      box.appendChild(sec);
    });
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
        }));
      });
    } catch (e) {
      $('playlistTracks').innerHTML = '<div class="empty">불러오지 못했습니다.</div>';
      toast(e && e.message ? e.message : '플레이리스트 오류');
    }
  }

  // ---- raised player: queue tab -------------------------------------------
  function renderQueue() {
    const box = $('psQueue');
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

  // ---- raised player: lyrics tab ------------------------------------------
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
    const box = $('psLyrics');
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
        const box = $('psLyrics');
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
    const box = $('psLyrics');
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

  // ---- navigation & raised sheet -----------------------------------------
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
    const names = { home: '홈', search: '검색', library: '라이브러리', playlist: '플레이리스트' };
    $('crumb').textContent = names[page] || 'BitChord';
    $('main').scrollTop = 0;
    if (page === 'home' && !S.home.length && !S.homeLoading) loadHome();
    if (page === 'search' && !S.search.recents.length) loadSearchRecents();
    if (page === 'library' && !S.library.length && !S.libraryLoading) loadLibrary();
  }

  function setSheetTab(tab) {
    S.sheetTab = tab;
    document.querySelectorAll('#psTabs .tab').forEach(function (b) {
      b.classList.toggle('active', b.dataset.tab === tab);
    });
    $('psLyrics').classList.toggle('hidden', tab !== 'lyrics');
    $('psQueue').classList.toggle('hidden', tab !== 'queue');
    if (tab === 'lyrics') {
      const vid = S.state && S.state.track && S.state.track.videoId || '';
      if (vid !== S.lastLyricTrack || !S.lyricNodes.length) {
        S.lastLyricTrack = vid;
        loadLyrics();
      } else updateLyrics();
    } else renderQueue();
  }

  function openSheet(tab) {
    S.sheetOpen = true;
    $('playerSheet').classList.add('open');
    $('playerSheet').setAttribute('aria-hidden', 'false');
    setSheetTab(tab || S.sheetTab);
  }

  function closeSheet() {
    S.sheetOpen = false;
    $('playerSheet').classList.remove('open');
    $('playerSheet').setAttribute('aria-hidden', 'true');
  }

  // ---- bindings ----------------------------------------------------------
  function bind(id, method) {
    const el = $(id);
    if (!el) return;
    el.onclick = function () { act(method); };
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
    ['pillPlay', 'psPlay'].forEach(function (id) { bind(id, 'player.toggle'); });
    ['pillPrev', 'psPrev'].forEach(function (id) { bind(id, 'player.previous'); });
    ['pillNext', 'psNext'].forEach(function (id) { bind(id, 'player.next'); });
    ['pillShuffle', 'psShuffle'].forEach(function (id) { bind(id, 'player.shuffle'); });
    ['pillRepeat', 'psRepeat'].forEach(function (id) { bind(id, 'player.repeat'); });
    bind('psLike', 'player.like');

    ['pillOpen', 'pillUp'].forEach(function (id) {
      $(id).onclick = function () { S.sheetOpen ? closeSheet() : openSheet(); };
    });
    $('psClose').onclick = closeSheet;

    document.querySelectorAll('#psTabs .tab').forEach(function (b) {
      b.onclick = function () { setSheetTab(b.dataset.tab); };
    });
    document.querySelectorAll('.nav-item').forEach(function (b) {
      b.onclick = function () { goto(b.dataset.page); };
    });
    $('backBtn').onclick = function () {
      if (S.page === 'playlist') goto('library');
      else goto('home');
    };

    document.querySelectorAll('#searchChips .chip').forEach(function (chip) {
      chip.onclick = function () {
        S.search.filter = chip.dataset.filter;
        document.querySelectorAll('#searchChips .chip').forEach(function (b) {
          b.classList.toggle('active', b === chip);
        });
        if (S.search.query) doSearch();
      };
    });

    $('q').addEventListener('input', function () {
      clearTimeout(S.searchTimer);
      S.searchTimer = setTimeout(doSearch, 350);
    });
    $('q').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { clearTimeout(S.searchTimer); doSearch(); }
    });

    bindSeek('psSeek');
    bindVolume();

    document.querySelectorAll('img').forEach(function (img) {
      img.addEventListener('error', function () { img.classList.add('noart'); });
    });

    setInterval(function () {
      if (!S.state) return;
      renderPlayer();
      if (S.sheetOpen && S.sheetTab === 'lyrics') updateLyrics();
    }, 250);
  }

  bindAll();
  connect();
})();
