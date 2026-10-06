/* ============================================================
   StockFlow - Dashboard graphs (dashboard-charts.js)
   Koi library nahi: pure SVG. Data HTML ke data-chart attribute (JSON) se aata hai.
   Types: "area" (smooth line+area, tooltip), "stack" (stacked columns), "gauge" (semi-circle)
   ============================================================ */
(function () {
    'use strict';

    var NS = 'http://www.w3.org/2000/svg';
    var uid = 0;

    /* ---------------- helpers ---------------- */
    function svgEl(name, attrs, parent) {
        var e = document.createElementNS(NS, name);
        if (attrs) {
            for (var k in attrs) {
                if (Object.prototype.hasOwnProperty.call(attrs, k)) e.setAttribute(k, attrs[k]);
            }
        }
        if (parent) parent.appendChild(e);
        return e;
    }

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function fmt(v) {
        if (v >= 1000) return (v % 1000 === 0 ? v / 1000 : (v / 1000).toFixed(1)) + 'k';
        return String(Math.round(v * 100) / 100);
    }

    function niceScale(max, ticks) {
        if (!(max > 0)) return { max: ticks, step: 1 };
        var raw = max / ticks;
        var mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
        var norm = raw / mag;
        var step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
        return { max: step * Math.ceil(max / step), step: step };
    }

    /* smooth path: har segment me horizontal-tangent bezier */
    function curve(xs, ys) {
        var d = 'M' + xs[0].toFixed(1) + ',' + ys[0].toFixed(1);
        for (var i = 1; i < xs.length; i++) {
            var mx = (xs[i - 1] + xs[i]) / 2;
            d += ' C' + mx.toFixed(1) + ',' + ys[i - 1].toFixed(1) + ' ' + mx.toFixed(1) + ',' + ys[i].toFixed(1)
               + ' ' + xs[i].toFixed(1) + ',' + ys[i].toFixed(1);
        }
        return d;
    }

    function makeTip(box) {
        var t = document.createElement('div');
        t.className = 'd2-tip';
        box.appendChild(t);
        return t;
    }

    function placeTip(box, tip, x, y) {
        var bw = box.clientWidth, bh = box.clientHeight;
        var tw = tip.offsetWidth, th = tip.offsetHeight;
        var left = x + 16;
        if (left + tw > bw) left = x - tw - 16;
        if (left < 0) left = 0;
        var top = Math.max(0, Math.min(y - th / 2, bh - th));
        tip.style.left = left + 'px';
        tip.style.top = top + 'px';
    }

    function tipRow(color, name, value) {
        return '<div class="d2-tip-row"><i style="background:' + esc(color) + '"></i><span>' + esc(name)
             + '</span><b>' + esc(value) + '</b></div>';
    }

    /* ---------------- AREA (smooth line + gradient area) ---------------- */
    function renderArea(box, cfg) {
        var W = box.clientWidth, H = box.clientHeight;
        if (W < 60 || H < 60) return;
        box.innerHTML = '';

        var id = 'd2c' + (++uid);
        var m = { t: 12, r: 16, b: 28, l: 36 };
        var n = cfg.labels.length;
        if (!n) return;

        var svg = svgEl('svg', { width: W, height: H, viewBox: '0 0 ' + W + ' ' + H, role: 'img' }, box);
        var tip = makeTip(box);
        var iw = W - m.l - m.r, ih = H - m.t - m.b;

        var maxV = 0;
        cfg.series.forEach(function (s) { s.data.forEach(function (v) { if (v > maxV) maxV = v; }); });
        var sc = niceScale(maxV, 4);

        function X(i) { return m.l + (n < 2 ? iw / 2 : iw * i / (n - 1)); }
        function Y(v) { return m.t + ih - (v / sc.max) * ih; }

        var defs = svgEl('defs', null, svg);
        cfg.series.forEach(function (s, si) {
            var g = svgEl('linearGradient', { id: id + 'g' + si, x1: 0, y1: 0, x2: 0, y2: 1 }, defs);
            svgEl('stop', { offset: '0%', 'stop-color': s.color, 'stop-opacity': 0.30 }, g);
            svgEl('stop', { offset: '100%', 'stop-color': s.color, 'stop-opacity': 0.02 }, g);
        });

        /* grid + y labels */
        var ax = svgEl('g', { 'class': 'ax' }, svg);
        var steps = Math.round(sc.max / sc.step);
        for (var t = 0; t <= steps; t++) {
            var y = Y(t * sc.step);
            svgEl('line', { 'class': t === 0 ? 'axis-line' : 'gl', x1: m.l, x2: W - m.r, y1: y, y2: y }, ax);
            var yt = svgEl('text', { x: m.l - 8, y: y + 4, 'text-anchor': 'end' }, ax);
            yt.textContent = fmt(t * sc.step);
        }

        /* x labels */
        var maxLabels = Math.max(2, Math.floor(iw / 56));
        var stepX = Math.max(1, Math.ceil(n / maxLabels));
        for (var i = 0; i < n; i += stepX) {
            var xt = svgEl('text', { x: X(i), y: H - 8, 'text-anchor': 'middle' }, ax);
            xt.textContent = cfg.labels[i];
        }

        /* areas + lines */
        var xs = [];
        for (var a = 0; a < n; a++) xs.push(X(a));
        cfg.series.forEach(function (s, si) {
            var ys = s.data.map(Y);
            if (n > 1) {
                var line = curve(xs, ys);
                svgEl('path', {
                    d: line + ' L' + xs[n - 1].toFixed(1) + ',' + Y(0).toFixed(1) + ' L' + xs[0].toFixed(1) + ',' + Y(0).toFixed(1) + ' Z',
                    fill: 'url(#' + id + 'g' + si + ')'
                }, svg);
                svgEl('path', {
                    d: line, fill: 'none', stroke: s.color, 'stroke-width': 2.5,
                    'stroke-linecap': 'round', 'stroke-linejoin': 'round'
                }, svg);
            }
        });

        /* hover guide + points */
        var guide = svgEl('line', { 'class': 'guide', x1: 0, x2: 0, y1: m.t, y2: m.t + ih, visibility: 'hidden' }, svg);
        var pts = cfg.series.map(function (s) {
            return s.data.map(function (v, i) {
                return svgEl('circle', {
                    'class': 'pt', cx: xs[i], cy: Y(v), r: n > 40 ? 0 : 3, stroke: s.color
                }, svg);
            });
        });

        var ov = svgEl('rect', { x: m.l, y: m.t, width: iw, height: ih, fill: 'transparent' }, svg);

        function idxAt(clientX) {
            var r = svg.getBoundingClientRect();
            var f = n < 2 ? 0 : (clientX - r.left - m.l) / iw * (n - 1);
            return Math.max(0, Math.min(n - 1, Math.round(f)));
        }
        function reset() {
            guide.setAttribute('visibility', 'hidden');
            pts.forEach(function (row) { row.forEach(function (c) { c.setAttribute('r', n > 40 ? 0 : 3); }); });
            tip.style.opacity = 0;
        }
        ov.addEventListener('pointermove', function (e) {
            var i = idxAt(e.clientX);
            guide.setAttribute('x1', xs[i]);
            guide.setAttribute('x2', xs[i]);
            guide.setAttribute('visibility', 'visible');
            pts.forEach(function (row, si) {
                row.forEach(function (c, ci) { c.setAttribute('r', ci === i ? 5.5 : (n > 40 ? 0 : 3)); });
            });
            var html = '<div class="d2-tip-title">' + esc((cfg.titles && cfg.titles[i]) || cfg.labels[i]) + '</div>';
            cfg.series.forEach(function (s) { html += tipRow(s.color, s.name, fmt(s.data[i])); });
            tip.innerHTML = html;
            tip.style.opacity = 1;
            var br = box.getBoundingClientRect();
            placeTip(box, tip, xs[i], e.clientY - br.top);
        });
        ov.addEventListener('pointerleave', reset);
    }

    /* ---------------- STACK (stacked columns) ---------------- */
    function renderStack(box, cfg) {
        var W = box.clientWidth, H = box.clientHeight;
        if (W < 60 || H < 60) return;
        box.innerHTML = '';

        var id = 'd2c' + (++uid);
        var m = { t: 12, r: 12, b: 34, l: 36 };
        var n = cfg.labels.length;
        if (!n) return;

        var svg = svgEl('svg', { width: W, height: H, viewBox: '0 0 ' + W + ' ' + H, role: 'img' }, box);
        var tip = makeTip(box);
        var iw = W - m.l - m.r, ih = H - m.t - m.b;

        var totals = [];
        var maxV = 0;
        for (var i = 0; i < n; i++) {
            var tt = 0;
            cfg.series.forEach(function (s) { tt += s.data[i] || 0; });
            totals.push(tt);
            if (tt > maxV) maxV = tt;
        }
        var sc = niceScale(maxV, 4);
        function Y(v) { return m.t + ih - (v / sc.max) * ih; }

        var ax = svgEl('g', { 'class': 'ax' }, svg);
        var steps = Math.round(sc.max / sc.step);
        for (var t = 0; t <= steps; t++) {
            var y = Y(t * sc.step);
            svgEl('line', { 'class': t === 0 ? 'axis-line' : 'gl', x1: m.l, x2: W - m.r, y1: y, y2: y }, ax);
            var yt = svgEl('text', { x: m.l - 8, y: y + 4, 'text-anchor': 'end' }, ax);
            yt.textContent = fmt(t * sc.step);
        }

        var slot = iw / n;
        var bw = Math.min(46, slot * 0.58);
        var maxChars = Math.max(3, Math.floor(slot / 6.4));
        var base = Y(0);
        var bars = [];

        for (var b = 0; b < n; b++) {
            var x0 = m.l + slot * b + (slot - bw) / 2;
            var total = totals[b];
            var th = (total / sc.max) * ih;

            var g = svgEl('g', { 'class': 'bar-g' }, svg);
            if (total > 0) {
                var clip = svgEl('clipPath', { id: id + 'c' + b }, g);
                svgEl('rect', { x: x0, y: base - th, width: bw, height: th + 8, rx: 6 }, clip);
                var segs = svgEl('g', { 'clip-path': 'url(#' + id + 'c' + b + ')' }, g);
                var acc = 0;
                cfg.series.forEach(function (s) {
                    var v = s.data[b] || 0;
                    if (v <= 0) return;
                    var h = (v / sc.max) * ih;
                    svgEl('rect', {
                        x: x0, y: base - acc - h, width: bw, height: h, fill: s.color,
                        stroke: 'rgba(255,255,255,.75)', 'stroke-width': 1
                    }, segs);
                    acc += h;
                });
            } else {
                svgEl('rect', { x: x0, y: base - 2, width: bw, height: 2, fill: 'rgba(108,60,224,.18)', rx: 1 }, g);
            }
            bars.push(g);

            var name = String(cfg.labels[b]);
            var lbl = svgEl('text', { x: x0 + bw / 2, y: H - 14, 'text-anchor': 'middle', 'class': 'ax-t' }, ax);
            lbl.textContent = name.length > maxChars ? name.slice(0, maxChars - 1) + '\u2026' : name;
            var ttl = svgEl('title', null, lbl);
            ttl.textContent = name;

            (function (idx, gx) {
                var hit = svgEl('rect', { x: m.l + slot * idx, y: m.t, width: slot, height: ih + 10, fill: 'transparent' }, svg);
                hit.addEventListener('pointermove', function (e) {
                    bars.forEach(function (bg, k) { bg.setAttribute('opacity', k === idx ? 1 : 0.55); });
                    var html = '<div class="d2-tip-title">' + esc(cfg.labels[idx]) + '</div>';
                    cfg.series.forEach(function (s) { html += tipRow(s.color, s.name, fmt(s.data[idx] || 0)); });
                    html += '<div class="d2-tip-total">Total <b>' + fmt(totals[idx]) + '</b></div>';
                    tip.innerHTML = html;
                    tip.style.opacity = 1;
                    var br = box.getBoundingClientRect();
                    placeTip(box, tip, gx, e.clientY - br.top);
                });
                hit.addEventListener('pointerleave', function () {
                    bars.forEach(function (bg) { bg.setAttribute('opacity', 1); });
                    tip.style.opacity = 0;
                });
            })(b, x0 + bw / 2);
        }
    }

    /* ---------------- GAUGE (semi-circle) ---------------- */
    function renderGauge(box, cfg) {
        box.innerHTML = '';
        var id = 'd2c' + (++uid);
        var W = 220, H = 128, cx = 110, cy = 108, r = 86, sw = 16;

        var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: '100%', role: 'img' }, box);
        var defs = svgEl('defs', null, svg);
        var g = svgEl('linearGradient', { id: id, x1: 0, y1: 0, x2: 1, y2: 0 }, defs);
        svgEl('stop', { offset: '0%', 'stop-color': cfg.color, 'stop-opacity': 0.55 }, g);
        svgEl('stop', { offset: '100%', 'stop-color': cfg.color, 'stop-opacity': 1 }, g);

        svgEl('path', {
            d: 'M' + (cx - r) + ',' + cy + ' A' + r + ',' + r + ' 0 0 1 ' + (cx + r) + ',' + cy,
            fill: 'none', stroke: 'rgba(108,60,224,.12)', 'stroke-width': sw, 'stroke-linecap': 'round'
        }, svg);

        if (cfg.value >= 0) {
            var p = Math.min(cfg.value, 99.99) / 100;
            var th = Math.PI * (1 - p);
            var x = cx + r * Math.cos(th), y = cy - r * Math.sin(th);
            if (cfg.value > 0) {
                svgEl('path', {
                    d: 'M' + (cx - r) + ',' + cy + ' A' + r + ',' + r + ' 0 0 1 ' + x.toFixed(2) + ',' + y.toFixed(2),
                    fill: 'none', stroke: 'url(#' + id + ')', 'stroke-width': sw, 'stroke-linecap': 'round'
                }, svg);
                svgEl('circle', { cx: x.toFixed(2), cy: y.toFixed(2), r: 4, fill: '#fff' }, svg);
            }
        }

        var v = svgEl('text', { x: cx, y: cy - 18, 'text-anchor': 'middle', 'class': 'gv' }, svg);
        v.textContent = cfg.value >= 0 ? cfg.value + '%' : '\u2013';
        var l = svgEl('text', { x: cx, y: cy + 2, 'text-anchor': 'middle', 'class': 'gl-t' }, svg);
        l.textContent = cfg.label;

        var sub = document.createElement('div');
        sub.className = 'd2-gauge-sub';
        sub.textContent = cfg.sub || '';
        box.appendChild(sub);
    }

    /* ---------------- boot ---------------- */
    function renderBox(box) {
        if (box.__d2) return;
        box.__d2 = true;

        var cfg;
        try { cfg = JSON.parse(box.getAttribute('data-chart')); } catch (e) { return; }
        if (!cfg) return;

        if (cfg.type === 'gauge') { renderGauge(box, cfg); return; }

        var draw = cfg.type === 'stack' ? renderStack : renderArea;
        draw(box, cfg);

        if (window.ResizeObserver) {
            var last = box.clientWidth;
            new ResizeObserver(function () {
                var w = box.clientWidth;
                if (Math.abs(w - last) > 2 || !box.firstChild) { last = w; draw(box, cfg); }
            }).observe(box);
        }
    }

    function init() {
        var boxes = document.querySelectorAll('.d2-chart[data-chart]');
        for (var i = 0; i < boxes.length; i++) renderBox(boxes[i]);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();