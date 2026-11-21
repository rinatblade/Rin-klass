(function () {
  if (window.__klass) { return; }
  var B = window.KlassBridge;
  if (!B) return;
  var K = window.__klass = {};
  var on = true;
  var state = {};          // url -> 1 (да) / 2 (нет) / 3 (проверяется)
  var boxes = new Map();   // элемент -> {div, url, mode}
  var root = null, pending = false, probes = 0, MAXP = 500;
  var pickLayer = null, hl = null, hlEl = null, hidden = false, logged = {}, logCount = 0;
  var mutTimes = new WeakMap(), animEls = new Map();   // элементы, которые страница анимирует скриптом

  function addStyle() {
    var s = document.createElement('style');
    s.textContent =
      '#klass-root{position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646;pointer-events:none}' +
      '.klass-box{position:fixed;box-sizing:border-box;pointer-events:auto;cursor:pointer;' +
      'border:2.5px dashed #ff2d88;border-radius:12px;background:rgba(255,45,136,.07);' +
      'outline:2px solid rgba(255,255,255,.9);animation:klassPulse 1.6s ease-in-out infinite;' +
      '-webkit-tap-highlight-color:transparent}' +
      '.klass-box.rec{border-color:#7a4cf1;background:rgba(122,76,241,.07)}' +
      '.klass-box.done{border-color:#19b36b;background:rgba(25,179,107,.14);animation:none}' +
      '.klass-box.err{border-color:#e53935;background:rgba(229,57,53,.12);animation:none}' +
      '.klass-tag{position:absolute;right:-2px;top:-2px;transform:translateY(-100%);background:#ff2d88;color:#fff;' +
      'font:700 11px/1 sans-serif;padding:4px 7px;border-radius:8px 8px 0 0;white-space:nowrap}' +
      '.klass-box.rec .klass-tag{background:#7a4cf1}' +
      '.klass-box.done .klass-tag{background:#19b36b}.klass-box.err .klass-tag{background:#e53935}' +
      '#klass-pick{position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.04);' +
      'touch-action:none}' +
      '#klass-hl{position:fixed;box-sizing:border-box;z-index:2147483647;pointer-events:none;display:none;' +
      'border:3px dashed #7a4cf1;border-radius:10px;background:rgba(122,76,241,.12);outline:2px solid #fff}' +
      '@keyframes klassPulse{0%,100%{box-shadow:0 0 0 0 rgba(255,45,136,.45)}50%{box-shadow:0 0 0 6px rgba(255,45,136,0)}}';
    (document.head || document.documentElement).appendChild(s);
  }

  function ensureRoot() {
    if (root && root.isConnected) return root;
    root = document.createElement('div');
    root.id = 'klass-root';
    if (hidden) root.style.visibility = 'hidden';
    (document.body || document.documentElement).appendChild(root);
    return root;
  }

  function hideUi(v) {
    hidden = v;
    if (root) root.style.visibility = v ? 'hidden' : 'visible';
    if (pickLayer) pickLayer.style.visibility = v ? 'hidden' : 'visible';
    if (hl) hl.style.visibility = v ? 'hidden' : 'visible';
  }

  function urlOf(el) {
    try {
      var t = el.tagName;
      if (t === 'IMG') return el.currentSrc || el.src || '';
      if (t === 'VIDEO') {
        var v = el.currentSrc || el.src || '';
        if (!v) { var so = el.querySelector('source'); if (so) v = so.src || ''; }
        return v;
      }
      var bg = getComputedStyle(el).backgroundImage || '';
      var m = /url\(["']?([^"')]+)["']?\)/.exec(bg);
      return m ? new URL(m[1], location.href).href : '';
    } catch (e) { return ''; }
  }

  function readBlob(url, cb) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      if (b.size > 12 * 1024 * 1024) return;
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result); cb(s.substring(s.indexOf(',') + 1)); };
      fr.readAsDataURL(b);
    }).catch(function () {});
  }

  function probe(url) {
    if (probes >= MAXP) return;
    probes++;
    state[url] = 3;
    if (url.indexOf('blob:') === 0) {
      readBlob(url, function (b64) { B.probeData(url, b64); });
    } else {
      B.probe(url, location.href);
    }
  }

  function pick(url) {
    if (url.indexOf('blob:') === 0) {
      readBlob(url, function (b64) { B.pickData(url, location.href, b64); });
    } else {
      B.pick(url, location.href);
    }
  }

  // Запись области экрана: подходит для любой анимации (canvas, Lottie, видео, спрайты, SVG).
  function record(el) {
    try {
      var r0 = el.getBoundingClientRect();
      if (r0.top < 0 || r0.left < 0 || r0.bottom > innerHeight || r0.right > innerWidth) {
        el.scrollIntoView({ block: 'center', inline: 'center' });
      }
    } catch (e) {}
    hideUi(true);
    var go = function () {
      var r = el.getBoundingClientRect();
      var l = Math.max(0, r.left), t = Math.max(0, r.top);
      var w = Math.min(innerWidth, r.right) - l, h = Math.min(innerHeight, r.bottom) - t;
      B.record(l, t, w, h, window.devicePixelRatio || 1);
    };
    requestAnimationFrame(function () { requestAnimationFrame(function () { setTimeout(go, 220); }); });
  }

  var SEL = 'img,video,canvas,svg,object,embed,lottie-player,dotlottie-player,' +
    '[style*="url("],[class*="gift" i],[class*="present" i],[class*="anim" i],[class*="sticker" i],[class*="lottie" i]';

  function sized(r, min, max) {
    return r.width >= min && r.height >= min && r.width <= max && r.height <= max &&
      r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }

  function svgAnimated(el) {
    try {
      if (el.querySelector('animate,animateTransform,animateMotion,set')) return true;
      if (el.closest('[class*="lottie" i],[class*="anim" i],[class*="gift" i],[class*="present" i],[class*="sticker" i]')) return true;
      if (el.getAttribute('data-animation-path') || el.querySelector('[clip-path] [clip-path]')) return true;
    } catch (e) {}
    return false;
  }

  // спрайт-лист: большая картинка, из которой скрипт показывает по одному кадру (SpriteAnimation и т.п.)
  function spriteLike(el, url) {
    try {
      if (/getGift/i.test(url) && /[?&]type=15(&|$)/.test(url)) return true;
      if (/sprite|anim/i.test(url) && !/\.(gif|webp)(\?|$)/i.test(url)) return true;
      if (el.tagName === 'IMG') return false;
      var cs = getComputedStyle(el), w = el.getBoundingClientRect().width;
      var bs = (cs.backgroundSize || '').split(' ')[0];
      if (/px$/.test(bs) && parseFloat(bs) >= w * 1.6) return true;
      if (/%$/.test(bs) && parseFloat(bs) >= 160) return true;
    } catch (e) {}
    return false;
  }

  function cssAnimated(el) {
    try {
      var cs = getComputedStyle(el);
      return cs.animationName && cs.animationName !== 'none' && parseFloat(cs.animationDuration) > 0;
    } catch (e) { return false; }
  }

  function survey(el, r, mode) {
    if (logCount > 60) return;
    var key = el.tagName + '|' + (el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className) + '|' + Math.round(r.width / 60);
    if (logged[key]) return;
    logged[key] = 1; logCount++;
    var cls = String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || '')).substring(0, 70);
    B.log(el.tagName.toLowerCase() + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' режим=' + mode + ' класс="' + cls + '"');
  }

  function makeBox(el, url, mode) {
    var d = document.createElement('div');
    d.className = 'klass-box' + (mode === 'rec' ? ' rec' : '');
    var tag = document.createElement('span');
    tag.className = 'klass-tag';
    tag.textContent = mode === 'rec' ? '⏺ записать' : '🎁 скачать';
    d.appendChild(tag);
    var handler = function (e) {
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      if (mode === 'rec') {
        tag.textContent = '⏺ запись…';
        record(el);
      } else {
        tag.textContent = '⏳ качаю…';
        var b = boxes.get(el);
        pick(b ? b.url : url);
      }
      return false;
    };
    d.addEventListener('click', handler, true);
    d.addEventListener('touchend', function (e) { e.stopPropagation(); }, true);
    ensureRoot().appendChild(d);
    return d;
  }

  function overlapsPlaced(r, placed) {
    var a = r.width * r.height;
    for (var i = 0; i < placed.length; i++) {
      var p = placed[i];
      var w = Math.min(r.right, p.right) - Math.max(r.left, p.left);
      var h = Math.min(r.bottom, p.bottom) - Math.max(r.top, p.top);
      if (w > 0 && h > 0) {
        var inter = w * h, m = Math.min(a, p.width * p.height);
        if (inter / m > 0.6) return true;
      }
    }
    return false;
  }

  function tick() {
    pending = false;
    if (!on || hidden) return;
    var dl = [], rec = [];
    var list = document.querySelectorAll(SEL);
    for (var i = 0; i < list.length && i < 1500; i++) {
      var el = list[i];
      var tg = el.tagName;
      if (el.closest && el.closest('#klass-root,#klass-pick,#klass-hl')) continue;
      var r = el.getBoundingClientRect();
      if (tg === 'CANVAS' || tg === 'OBJECT' || tg === 'EMBED' || tg === 'LOTTIE-PLAYER' || tg === 'DOTLOTTIE-PLAYER') {
        if (sized(r, 56, 900)) { survey(el, r, 'rec'); rec.push([el, r]); }
        continue;
      }
      if (tg === 'SVG' || tg === 'svg') {
        if (sized(r, 56, 900) && svgAnimated(el)) { survey(el, r, 'rec'); rec.push([el, r]); }
        continue;
      }
      if (!sized(r, 40, 1000)) continue;
      var url = urlOf(el);
      if (tg === 'VIDEO') {
        var dur = el.duration;
        if (!(el.loop || (dur && dur <= 12) || !dur)) continue;
        if (/^https?:/i.test(url)) { if (state[url] === undefined) state[url] = 1; dl.push([el, r, url]); }
        else { survey(el, r, 'rec'); rec.push([el, r]); }
        continue;
      }
      if (url && /\/%23|#clip|#mask/i.test(url)) continue;
      if (url && /^(https?:|blob:|data:image)/i.test(url)) {
        var st = state[url];
        if (st === undefined) { probe(url); }
        if (st === 1) { dl.push([el, r, url]); continue; }
        if (st === 2 && tg !== 'VIDEO' && sized(r, 56, 900) && spriteLike(el, url)) {
          survey(el, r, 'rec-sprite'); rec.push([el, r]); continue;
        }
        if (st === undefined) continue;
      }
      if (tg !== 'IMG' && cssAnimated(el) && sized(r, 56, 900)) { survey(el, r, 'rec'); rec.push([el, r]); }
    }

    var nowT = Date.now();
    animEls.forEach(function (t, el) {
      if (nowT - t > 6000 || !el.isConnected) { animEls.delete(el); return; }
      var r = el.getBoundingClientRect();
      if (sized(r, 56, 900)) { survey(el, r, 'rec-js'); rec.push([el, r]); }
    });

    var seen = new Set(), placed = [], count = 0;
    function place(el, r, url, mode) {
      if (count >= 60) return;
      if (overlapsPlaced(r, placed)) return;
      placed.push(r); count++; seen.add(el);
      var b = boxes.get(el);
      if (!b || b.url !== url || b.mode !== mode) {
        if (b) b.div.remove();
        b = { div: makeBox(el, url, mode), url: url, mode: mode };
        boxes.set(el, b);
      }
      var s = b.div.style;
      s.left = (r.left - 2) + 'px'; s.top = (r.top - 2) + 'px';
      s.width = (r.width + 4) + 'px'; s.height = (r.height + 4) + 'px';
    }
    dl.forEach(function (c) { place(c[0], c[1], c[2], 'dl'); });
    rec.forEach(function (c) { place(c[0], c[1], '', 'rec'); });
    boxes.forEach(function (b, el) {
      if (!seen.has(el)) { b.div.remove(); boxes.delete(el); }
    });
  }

  function schedule() {
    if (pending || !on || hidden) return;
    pending = true;
    setTimeout(function () { requestAnimationFrame(tick); }, 90);
  }

  // ----- ручной режим: нажмите на анимацию, чтобы выделить, и ещё раз - записать
  function findAt(x, y) {
    var list = document.elementsFromPoint(x, y);
    var best = null;
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e.closest && e.closest('#klass-root,#klass-pick,#klass-hl')) continue;
      var t = e.tagName;
      var base = (t === 'svg' || t === 'SVG' || (e.ownerSVGElement)) ? (e.ownerSVGElement || e) : e;
      var r = base.getBoundingClientRect();
      if (r.width < 32 || r.height < 32) continue;
      var big = r.width * r.height > innerWidth * innerHeight * 0.7;
      if (/^(CANVAS|VIDEO|IMG|OBJECT|EMBED|LOTTIE-PLAYER|DOTLOTTIE-PLAYER)$/i.test(base.tagName) || base.tagName === 'svg') {
        if (!big) return base;
      }
      if (!best && !big && r.width <= 900 && r.height <= 900) best = base;
    }
    return best;
  }

  K.pickMode = function (v) {
    if (!v) {
      if (pickLayer) { pickLayer.remove(); pickLayer = null; }
      if (hl) { hl.style.display = 'none'; }
      hlEl = null;
      return;
    }
    if (pickLayer) return;
    if (!hl) { hl = document.createElement('div'); hl.id = 'klass-hl'; (document.body || document.documentElement).appendChild(hl); }
    hl.style.display = 'none'; hl.style.visibility = 'visible';
    pickLayer = document.createElement('div');
    pickLayer.id = 'klass-pick';
    pickLayer.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      var x = e.clientX, y = e.clientY;
      if (hlEl && hl.style.display === 'block') {
        var r = hl.getBoundingClientRect();
        if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
          var target = hlEl;
          K.pickMode(false);
          B.pickModeOff();
          record(target);
          return false;
        }
      }
      var el = findAt(x, y);
      if (!el) { hl.style.display = 'none'; hlEl = null; return false; }
      hlEl = el;
      var rr = el.getBoundingClientRect();
      hl.style.left = rr.left + 'px'; hl.style.top = rr.top + 'px';
      hl.style.width = rr.width + 'px'; hl.style.height = rr.height + 'px';
      hl.style.display = 'block';
      return false;
    }, true);
    (document.body || document.documentElement).appendChild(pickLayer);
  };

  K.recordDone = function () { hideUi(false); schedule(); };

  K.result = function (url, ok) { state[url] = ok ? 1 : 2; schedule(); };

  K.flash = function (url, ok, text) {
    boxes.forEach(function (b) {
      if (b.url === url && url) {
        b.div.className = 'klass-box ' + (ok ? 'done' : 'err');
        var t = b.div.firstChild; if (t) t.textContent = text || (ok ? '✓ скачано' : '✗ ошибка');
        setTimeout(function () {
          b.div.className = 'klass-box' + (b.mode === 'rec' ? ' rec' : '');
          if (t) t.textContent = b.mode === 'rec' ? '⏺ записать' : '🎁 скачать';
        }, 3200);
      }
    });
  };

  K.setOn = function (v) {
    on = !!v;
    if (!on) {
      boxes.forEach(function (b) { b.div.remove(); });
      boxes.clear();
    } else { schedule(); }
  };

  K.info = function () {
    return JSON.stringify({ url: location.href, boxes: boxes.size, known: Object.keys(state).length, probes: probes });
  };

  addStyle();
  window.addEventListener('scroll', schedule, true);
  window.addEventListener('resize', schedule);
  window.addEventListener('load', schedule);
  function ours(n) {
    return n && n.closest && n.closest('#klass-root,#klass-pick,#klass-hl');
  }
  try {
    new MutationObserver(function (recs) {
      var relevant = false, now = Date.now();
      for (var i = 0; i < recs.length; i++) {
        var m = recs[i], t = m.target;
        if (!t || t.nodeType !== 1 || ours(t)) continue;
        relevant = true;
        if (m.type === 'attributes' && m.attributeName === 'style') {
          var arr = mutTimes.get(t) || [];
          arr.push(now);
          while (arr.length && now - arr[0] > 1500) arr.shift();
          mutTimes.set(t, arr);
          if (arr.length >= 5) animEls.set(t, now);
        }
      }
      if (relevant) schedule();
    }).observe(document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset', 'style', 'class'] });
  } catch (e) {}
  setInterval(schedule, 1500);
  schedule();
})();
