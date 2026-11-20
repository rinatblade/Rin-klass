(function () {
  if (window.__klass) { window.__klass.setOn(true); return; }
  var B = window.KlassBridge;
  if (!B) return;
  var K = window.__klass = {};
  var on = true;
  var state = {};          // url -> 1 (да) / 2 (нет) / 3 (проверяется)
  var boxes = new Map();   // элемент -> {div, url}
  var root = null, timer = 0, pending = false, probes = 0, MAXP = 500;

  function addStyle() {
    var s = document.createElement('style');
    s.textContent =
      '#klass-root{position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none}' +
      '.klass-box{position:fixed;box-sizing:border-box;pointer-events:auto;cursor:pointer;' +
      'border:2.5px dashed #ff2d88;border-radius:12px;background:rgba(255,45,136,.07);' +
      'outline:2px solid rgba(255,255,255,.9);animation:klassPulse 1.6s ease-in-out infinite;' +
      '-webkit-tap-highlight-color:transparent}' +
      '.klass-box.done{border-color:#19b36b;background:rgba(25,179,107,.14);animation:none}' +
      '.klass-box.err{border-color:#e53935;background:rgba(229,57,53,.12);animation:none}' +
      '.klass-tag{position:absolute;right:-2px;top:-2px;transform:translateY(-100%);background:#ff2d88;color:#fff;' +
      'font:700 11px/1 sans-serif;padding:4px 7px;border-radius:8px 8px 0 0;white-space:nowrap}' +
      '.klass-box.done .klass-tag{background:#19b36b}.klass-box.err .klass-tag{background:#e53935}' +
      '@keyframes klassPulse{0%,100%{box-shadow:0 0 0 0 rgba(255,45,136,.45)}50%{box-shadow:0 0 0 6px rgba(255,45,136,0)}}';
    (document.head || document.documentElement).appendChild(s);
  }

  function ensureRoot() {
    if (root && root.isConnected) return root;
    root = document.createElement('div');
    root.id = 'klass-root';
    (document.body || document.documentElement).appendChild(root);
    return root;
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

  function candidates() {
    return document.querySelectorAll(
      'img,video,[style*="url("],[class*="gift" i],[class*="present" i],[class*="anim" i],[class*="sticker" i]');
  }

  function visible(r) {
    return r.width >= 40 && r.height >= 40 && r.width <= 1000 && r.height <= 1000 &&
      r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }

  function makeBox(el, url) {
    var d = document.createElement('div');
    d.className = 'klass-box';
    var tag = document.createElement('span');
    tag.className = 'klass-tag';
    tag.textContent = '🎁 скачать';
    d.appendChild(tag);
    var handler = function (e) {
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      tag.textContent = '⏳ качаю…';
      pick(boxes.get(el) ? boxes.get(el).url : url);
      return false;
    };
    d.addEventListener('click', handler, true);
    d.addEventListener('touchend', function (e) { e.stopPropagation(); }, true);
    ensureRoot().appendChild(d);
    return d;
  }

  function tick() {
    pending = false;
    if (!on) return;
    var seen = new Set();
    var list = candidates();
    var count = 0;
    for (var i = 0; i < list.length && count < 80; i++) {
      var el = list[i];
      var r = el.getBoundingClientRect();
      if (!visible(r)) continue;
      var url = urlOf(el);
      if (!url || !/^(https?:|blob:|data:image)/i.test(url)) continue;
      if (el.tagName === 'VIDEO') {
        var dur = el.duration;
        if (!(el.loop || (dur && dur <= 12))) continue;
        if (state[url] === undefined) state[url] = 1;
      }
      var st = state[url];
      if (st === undefined) { probe(url); continue; }
      if (st !== 1) continue;
      count++;
      seen.add(el);
      var b = boxes.get(el);
      if (!b || b.url !== url) {
        if (b) b.div.remove();
        b = { div: makeBox(el, url), url: url };
        boxes.set(el, b);
      }
      var s = b.div.style;
      s.left = (r.left - 2) + 'px'; s.top = (r.top - 2) + 'px';
      s.width = (r.width + 4) + 'px'; s.height = (r.height + 4) + 'px';
    }
    boxes.forEach(function (b, el) {
      if (!seen.has(el)) { b.div.remove(); boxes.delete(el); }
    });
  }

  function schedule() {
    if (pending || !on) return;
    pending = true;
    setTimeout(function () { requestAnimationFrame(tick); }, 90);
  }

  K.result = function (url, ok) { state[url] = ok ? 1 : 2; schedule(); };

  K.flash = function (url, ok, text) {
    boxes.forEach(function (b) {
      if (b.url === url) {
        b.div.className = 'klass-box ' + (ok ? 'done' : 'err');
        var t = b.div.firstChild; if (t) t.textContent = text || (ok ? '✓ скачано' : '✗ ошибка');
        setTimeout(function () {
          b.div.className = 'klass-box';
          if (t) t.textContent = '🎁 скачать';
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
  try {
    new MutationObserver(schedule).observe(document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset', 'style', 'class'] });
  } catch (e) {}
  setInterval(schedule, 1500);
  schedule();
})();
