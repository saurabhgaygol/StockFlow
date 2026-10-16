/*
 * StockFlow AI - browser-side export (Excel / PDF / PowerPoint).
 *
 * Security model:
 *  - The file is built ONLY in the user's browser from the verified data the server just returned.
 *    Nothing is uploaded, stored on the server, or kept in localStorage / sessionStorage.
 *  - The data lives only in a JS closure. After a file is downloaded its data is wiped and the
 *    download buttons disappear. Unused data is wiped automatically after 10 minutes, and on refresh.
 *  - Cells that could run as spreadsheet formulas (= + - @) are neutralised.
 *  - All libraries are served from this application (/js/vendor), never from a CDN.
 */
(function () {
    'use strict';

    var LIBS = {
        xlsx: ['/js/vendor/exceljs.min.js'],
        pdf: ['/js/vendor/jspdf.umd.min.js', '/js/vendor/jspdf.plugin.autotable.min.js'],
        pptx: ['/js/vendor/pptxgen.bundle.js']
    };
    var LABEL = { xlsx: 'Excel', pdf: 'PDF', pptx: 'PowerPoint' };
    var EXT = { xlsx: 'xlsx', pdf: 'pdf', pptx: 'pptx' };
    var TTL_MS = 10 * 60 * 1000;
    var BRAND = '1F3A5F', ACCENT = '2E7D9A', ZEBRA = 'F3F6FA';
    var loaded = {};

    function loadScript(src) {
        if (loaded[src]) return loaded[src];
        loaded[src] = new Promise(function (resolve, reject) {
            var s = document.createElement('script');
            s.src = (window.__ctx || '') + src;
            s.onload = resolve;
            s.onerror = function () { reject(new Error('Could not load ' + src)); };
            document.head.appendChild(s);
        });
        return loaded[src];
    }

    function loadLibs(fmt) {
        return LIBS[fmt].reduce(function (p, src) { return p.then(function () { return loadScript(src); }); }, Promise.resolve());
    }

    /* ---------- helpers ---------- */

    var PALETTE = ['2E7D9A', 'F2B134', '3FA796', 'E4572E', '7A5195', '4C9F70', 'D1495B', '5C6B7A'];

    function safeText(v) {
        if (v === null || v === undefined) return '';
        var s = String(v);
        return /^[=+\-@\t\r]/.test(s) ? "'" + s : s;
    }
    function isNum(v) { return typeof v === 'number' && isFinite(v); }
    function cleanName(t) {
        var n = String(t || 'Report').replace(/[^A-Za-z0-9 _-]+/g, '').trim().replace(/\s+/g, '_');
        return (n || 'Report').slice(0, 60);
    }
    function stamp() {
        var d = new Date(), p = function (n) { return n < 10 ? '0' + n : '' + n; };
        return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes());
    }
    function nowText() {
        var d = new Date();
        return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) + ' ' +
            d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
    }
    function download(blob, name) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url; a.download = name; a.rel = 'noopener';
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
    }
    function hexRgb(h) { return [parseInt(h.substr(0, 2), 16), parseInt(h.substr(2, 2), 16), parseInt(h.substr(4, 2), 16)]; }
    function onColor(h) { var c = hexRgb(h), l = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255; return l > 0.62 ? '1B2430' : 'FFFFFF'; }
    function mix(h, pct) {   // white ke saath milao (zebra / halka rang)
        var c = hexRgb(h).map(function (x) { return Math.round(x + (255 - x) * pct); });
        return c.map(function (x) { return ('0' + x.toString(16)).slice(-2); }).join('').toUpperCase();
    }
    function palette(f) { return [f.accent].concat(PALETTE.filter(function (c) { return c !== f.accent; })); }
    function numCols(sec) {
        var out = [];
        sec.columns.forEach(function (c, i) {
            var any = false, all = true;
            sec.rows.forEach(function (r) { if (r[i] !== null && r[i] !== undefined && r[i] !== '') { any = true; if (!isNum(r[i])) all = false; } });
            if (any && all) out.push(i);
        });
        return out;
    }
    function fmtNum(v) { return isNum(v) ? v.toLocaleString('en-IN', { maximumFractionDigits: 2 }) : (v === null || v === undefined ? '' : String(v)); }
    function hasChart(sec) { return !!sec.chart && (sec.show === 'chart' || sec.show === 'both'); }
    function hasTable(sec) { return sec.show !== 'chart'; }
    function sheetName(h, i, used) {
        var n = String(h || ('Sheet ' + (i + 1))).replace(/[\\\/\?\*\[\]:]/g, ' ').trim().slice(0, 28) || ('Sheet ' + (i + 1));
        var base = n, k = 2;
        while (used[n.toLowerCase()]) { n = base.slice(0, 25) + ' ' + k++; }
        used[n.toLowerCase()] = 1;
        return n;
    }
    function chartData(sec) {
        var c = sec.chart;
        return {
            labels: sec.rows.map(function (r) { return String(r[c.label] === null || r[c.label] === undefined ? '' : r[c.label]).slice(0, 28); }),
            values: sec.rows.map(function (r) { return isNum(r[c.value]) ? r[c.value] : 0; }),
            name: sec.columns[c.value]
        };
    }

    /* ---------- Excel ---------- */

    function buildXlsx(f) {
        var wb = new window.ExcelJS.Workbook();
        wb.creator = 'StockFlow AI Assistant';
        wb.created = new Date();
        var used = {};
        function fill(h) { return { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF' + h } }; }

        function banner(ws, n, text, sub) {
            var span = Math.max(n, 2);
            ws.mergeCells(1, 1, 1, span);
            var t = ws.getCell(1, 1);
            t.value = safeText(text);
            t.font = { name: 'Calibri', size: 16, bold: true, color: { argb: 'FF' + onColor(f.primary) } };
            t.fill = fill(f.primary);
            t.alignment = { vertical: 'middle', indent: 1 };
            ws.getRow(1).height = 30;
            ws.mergeCells(2, 1, 2, span);
            var st = ws.getCell(2, 1);
            st.value = safeText(sub);
            st.font = { name: 'Calibri', size: 10, italic: true, color: { argb: 'FF5A6B7B' } };
            st.alignment = { indent: 1 };
        }

        if (f.summary && f.summary.length) {
            var ss = wb.addWorksheet(sheetName('Summary', 0, used));
            banner(ss, 6, f.title, f.subtitle || ('Generated ' + nowText()));
            f.summary.forEach(function (b, i) {
                ss.mergeCells(4 + i, 1, 4 + i, 6);
                var c = ss.getCell(4 + i, 1);
                c.value = safeText('•  ' + b);
                c.font = { name: 'Calibri', size: 12 };
                c.alignment = { wrapText: true, vertical: 'top', indent: 1 };
                ss.getRow(4 + i).height = Math.max(20, Math.ceil(b.length / 95) * 18);
            });
            for (var k = 1; k <= 6; k++) ss.getColumn(k).width = 18;
        }

        f.sections.forEach(function (sec, si) {
            var n = sec.columns.length, nums = numCols(sec);
            var ws = wb.addWorksheet(sheetName(sec.heading, si, used), { views: [{ state: 'frozen', ySplit: 4 }] });
            banner(ws, n, (sec.heading && f.sections.length > 1) ? f.title + ' - ' + sec.heading : (sec.heading || f.title),
                (f.subtitle ? f.subtitle + '  |  ' : '') + 'Generated ' + nowText() + '  |  ' + sec.rows.length + ' row(s)' + (sec.truncated ? '  |  partial list (row limit reached)' : ''));
            ws.getRow(3).height = 6;
            var hr = ws.getRow(4);
            sec.columns.forEach(function (c, i) {
                var cell = hr.getCell(i + 1);
                cell.value = safeText(c);
                cell.font = { name: 'Calibri', bold: true, color: { argb: 'FF' + onColor(f.accent) } };
                cell.fill = fill(f.accent);
                cell.alignment = { vertical: 'middle', horizontal: nums.indexOf(i) >= 0 ? 'right' : 'left', wrapText: true };
                cell.border = { bottom: { style: 'medium', color: { argb: 'FF' + f.primary } } };
            });
            hr.height = 22;
            var widths = sec.columns.map(function (c) { return String(c).length + 4; });
            var zebra = mix(f.accent, 0.9);
            function put(rowIdx, r, bold) {
                var row = ws.getRow(rowIdx);
                sec.columns.forEach(function (c, i) {
                    var v = r[i], cell = row.getCell(i + 1);
                    if (isNum(v)) {
                        cell.value = v; cell.numFmt = Number.isInteger(v) ? '#,##0' : '#,##0.00';
                        cell.alignment = { horizontal: 'right' };
                        widths[i] = Math.max(widths[i], String(v).length + 3);
                    } else {
                        var s = safeText(v); cell.value = s;
                        widths[i] = Math.max(widths[i], Math.min(s.length + 3, 48));
                    }
                    cell.font = { name: 'Calibri', size: 11, bold: !!bold };
                });
                return row;
            }
            sec.rows.forEach(function (r, ri) {
                var row = put(5 + ri, r, false);
                for (var i = 1; i <= n; i++) {
                    var cell = row.getCell(i);
                    cell.border = { bottom: { style: 'thin', color: { argb: 'FFD9E1EA' } } };
                    if (ri % 2 === 1) cell.fill = fill(zebra);
                }
            });
            if (sec.totals) {
                var tr = put(5 + sec.rows.length, sec.totals, true);
                for (var j = 1; j <= n; j++) {
                    var tc = tr.getCell(j);
                    tc.border = { top: { style: 'medium', color: { argb: 'FF' + f.primary } } };
                    tc.fill = fill(mix(f.accent, 0.8));
                }
            }
            widths.forEach(function (w, i) { ws.getColumn(i + 1).width = Math.min(Math.max(w, 10), 50); });
            ws.autoFilter = { from: { row: 4, column: 1 }, to: { row: 4 + Math.max(sec.rows.length, 1), column: n } };
            ws.pageSetup = { orientation: n > 6 ? 'landscape' : 'portrait', fitToPage: true, fitToWidth: 1, fitToHeight: 0 };
        });

        return wb.xlsx.writeBuffer().then(function (buf) {
            return new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
        });
    }

    /* ---------- PDF ---------- */

    function pdfChart(doc, sec, x, y, w, h, pal) {
        var cd = chartData(sec), type = sec.chart.type, n = cd.values.length;
        doc.setDrawColor(200, 208, 216); doc.setLineWidth(0.5);
        if (type === 'pie') {
            var total = cd.values.reduce(function (a, b) { return a + Math.max(b, 0); }, 0) || 1;
            var r = Math.min(h / 2 - 6, w / 4), cx = x + r + 10, cy = y + h / 2, ang = -Math.PI / 2;
            cd.values.forEach(function (v, i) {
                var a = Math.max(v, 0) / total * Math.PI * 2, steps = Math.max(2, Math.ceil(a / 0.08));
                var pts = [[cx, cy]];
                for (var k = 0; k <= steps; k++) pts.push([cx + r * Math.cos(ang + a * k / steps), cy + r * Math.sin(ang + a * k / steps)]);
                var c = hexRgb(pal[i % pal.length]);
                doc.setFillColor(c[0], c[1], c[2]);
                var lines = [];
                for (var q = 1; q < pts.length; q++) lines.push([pts[q][0] - pts[q - 1][0], pts[q][1] - pts[q - 1][1]]);
                doc.lines(lines, pts[0][0], pts[0][1], [1, 1], 'F', true);
                ang += a;
            });
            doc.setFontSize(9);
            cd.labels.forEach(function (lb, i) {
                if (i > 11) return;
                var c = hexRgb(pal[i % pal.length]);
                doc.setFillColor(c[0], c[1], c[2]); doc.rect(cx + r + 24, y + 8 + i * 15, 9, 9, 'F');
                doc.setTextColor(40, 50, 60);
                doc.text(lb + '  (' + fmtNum(cd.values[i]) + ')', cx + r + 38, y + 16 + i * 15);
            });
            return;
        }
        var left = x + 46, bottom = y + h - 26, top = y + 10, right = x + w - 10;
        var max = Math.max.apply(null, cd.values.concat([1])), min = Math.min.apply(null, cd.values.concat([0]));
        var span = (max - min) || 1;
        function py(v) { return bottom - (v - min) / span * (bottom - top); }
        doc.setFontSize(8); doc.setTextColor(110, 120, 130);
        for (var g = 0; g <= 4; g++) {
            var gv = min + span * g / 4, gy = py(gv);
            doc.line(left, gy, right, gy);
            doc.text(fmtNum(Math.round(gv * 100) / 100), left - 4, gy + 3, { align: 'right' });
        }
        var slot = (right - left) / Math.max(n, 1);
        var c0 = hexRgb(pal[0]);
        if (type === 'line') {
            doc.setDrawColor(c0[0], c0[1], c0[2]); doc.setLineWidth(2); doc.setFillColor(c0[0], c0[1], c0[2]);
            cd.values.forEach(function (v, i) {
                var px = left + slot * (i + 0.5), pyy = py(v);
                if (i > 0) doc.line(left + slot * (i - 0.5), py(cd.values[i - 1]), px, pyy);
            });
            cd.values.forEach(function (v, i) { doc.circle(left + slot * (i + 0.5), py(v), 2.5, 'F'); });
            doc.setLineWidth(0.5);
        } else {
            doc.setFillColor(c0[0], c0[1], c0[2]);
            cd.values.forEach(function (v, i) {
                var bw = Math.min(slot * 0.6, 46), bx = left + slot * (i + 0.5) - bw / 2, by = py(Math.max(v, 0)), b0 = py(Math.min(0, max));
                doc.rect(bx, Math.min(by, b0), bw, Math.abs(b0 - by) || 1, 'F');
            });
        }
        doc.setFontSize(8); doc.setTextColor(40, 50, 60);
        var every = Math.ceil(n / Math.max(1, Math.floor((right - left) / 52)));
        cd.labels.forEach(function (lb, i) {
            if (i % every) return;
            doc.text(lb.length > 14 ? lb.slice(0, 13) + '..' : lb, left + slot * (i + 0.5), bottom + 12, { align: 'center' });
        });
        doc.setTextColor(110, 120, 130);
        doc.text(cd.name, x + w / 2, y + h - 4, { align: 'center' });
    }

    function buildPdf(f) {
        var jsPDF = window.jspdf.jsPDF;
        var wide = f.orientation === 'landscape' || (f.orientation !== 'portrait' && f.sections.some(function (s) { return s.columns.length > 6; }));
        var doc = new jsPDF({ orientation: wide ? 'landscape' : 'portrait', unit: 'pt', format: 'a4' });
        var W = doc.internal.pageSize.getWidth(), H = doc.internal.pageSize.getHeight(), M = 36;
        var pal = palette(f), pc = hexRgb(f.primary), ac = hexRgb(f.accent), oc = hexRgb(onColor(f.primary));
        var autoT = function (opts) {
            if (typeof doc.autoTable === 'function') doc.autoTable(opts);
            else if (typeof window.autoTable === 'function') window.autoTable(doc, opts);
            else if (window.jspdfAutoTable && typeof window.jspdfAutoTable.default === 'function') window.jspdfAutoTable.default(doc, opts);
            else throw new Error('PDF table plugin missing');
        };

        doc.setFillColor(pc[0], pc[1], pc[2]); doc.rect(0, 0, W, 70, 'F');
        doc.setTextColor(oc[0], oc[1], oc[2]);
        doc.setFont('helvetica', 'bold'); doc.setFontSize(19); doc.text(String(f.title), M, 32);
        doc.setFont('helvetica', 'normal'); doc.setFontSize(9.5);
        doc.text((f.subtitle ? f.subtitle + '   |   ' : '') + 'Generated ' + nowText(), M, 52);
        var y = 90;

        if (f.summary && f.summary.length) {
            doc.setTextColor(pc[0], pc[1], pc[2]); doc.setFont('helvetica', 'bold'); doc.setFontSize(12);
            doc.text('Key points', M, y); y += 16;
            doc.setFont('helvetica', 'normal'); doc.setFontSize(10.5); doc.setTextColor(40, 50, 60);
            f.summary.forEach(function (b) {
                var lines = doc.splitTextToSize(b, W - 2 * M - 14);
                if (y + lines.length * 14 > H - 50) { doc.addPage(); y = 50; }
                doc.setFillColor(ac[0], ac[1], ac[2]); doc.circle(M + 3, y - 3.5, 2, 'F');
                doc.text(lines, M + 14, y); y += lines.length * 14 + 3;
            });
            y += 8;
        }

        f.sections.forEach(function (sec) {
            var nums = numCols(sec);
            if (y > H - 120) { doc.addPage(); y = 50; }
            if (sec.heading) {
                doc.setFont('helvetica', 'bold'); doc.setFontSize(13); doc.setTextColor(pc[0], pc[1], pc[2]);
                doc.text(sec.heading, M, y); y += 14;
            }
            if (hasChart(sec) && sec.rows.length) {
                var ch = 220;
                if (y + ch > H - 50) { doc.addPage(); y = 50; }
                pdfChart(doc, sec, M, y, W - 2 * M, ch, pal);
                y += ch + 10;
            }
            if (hasTable(sec)) {
                var cs = {}; nums.forEach(function (i) { cs[i] = { halign: 'right' }; });
                autoT({
                    startY: y,
                    head: [sec.columns],
                    body: sec.rows.map(function (r) { return r.map(fmtNum); }),
                    foot: sec.totals ? [sec.totals.map(fmtNum)] : undefined,
                    showFoot: 'lastPage',
                    theme: 'striped',
                    styles: { font: 'helvetica', fontSize: 9, cellPadding: 5, overflow: 'linebreak', textColor: [40, 50, 60] },
                    headStyles: { fillColor: ac, textColor: hexRgb(onColor(f.accent)), fontStyle: 'bold' },
                    footStyles: { fillColor: hexRgb(mix(f.accent, 0.8)), textColor: [20, 30, 40], fontStyle: 'bold' },
                    alternateRowStyles: { fillColor: hexRgb(mix(f.accent, 0.92)) },
                    columnStyles: cs,
                    margin: { left: M, right: M, bottom: 40 }
                });
                y = doc.lastAutoTable ? doc.lastAutoTable.finalY + 24 : y + 40;
                if (sec.truncated) {
                    doc.setFont('helvetica', 'italic'); doc.setFontSize(8.5); doc.setTextColor(120, 130, 140);
                    doc.text('Partial list: row limit reached.', M, y - 12);
                }
            }
        });

        var pages = doc.internal.getNumberOfPages();
        for (var p = 1; p <= pages; p++) {
            doc.setPage(p); doc.setFont('helvetica', 'normal'); doc.setFontSize(8); doc.setTextColor(120, 130, 140);
            doc.text('StockFlow AI Assistant', M, H - 18);
            doc.text('Page ' + p + ' of ' + pages, W - M, H - 18, { align: 'right' });
        }
        return Promise.resolve(doc.output('blob'));
    }

    /* ---------- PowerPoint ---------- */

    function buildPptx(f) {
        var P = new window.PptxGenJS();
        P.layout = 'LAYOUT_WIDE';
        P.author = 'StockFlow AI Assistant';
        P.title = f.title;
        var pal = palette(f), onP = onColor(f.primary), onA = onColor(f.accent);

        var s1 = P.addSlide();
        s1.background = { color: f.primary };
        s1.addShape(P.ShapeType.rect, { x: 0, y: 5.6, w: 13.33, h: 0.12, fill: { color: pal[1] } });
        s1.addText(String(f.title), { x: 0.8, y: 2.0, w: 11.7, h: 1.4, fontFace: 'Calibri', fontSize: 40, bold: true, color: onP, fit: 'shrink' });
        s1.addText(f.subtitle || 'StockFlow AI Assistant', { x: 0.8, y: 3.5, w: 11.7, h: 0.5, fontFace: 'Calibri', fontSize: 18, color: onP });
        s1.addText('Generated ' + nowText(), { x: 0.8, y: 4.05, w: 11.7, h: 0.4, fontFace: 'Calibri', fontSize: 13, color: onP });

        if (f.summary && f.summary.length) {
            var sm = P.addSlide();
            sm.addText('Key points', { x: 0.6, y: 0.3, w: 12, h: 0.7, fontFace: 'Calibri', fontSize: 28, bold: true, color: f.primary });
            sm.addText(f.summary.map(function (b) { return { text: b, options: { bullet: true, breakLine: true } }; }),
                { x: 0.8, y: 1.3, w: 11.7, h: 5.4, fontFace: 'Calibri', fontSize: 20, color: '28323C', valign: 'top', paraSpaceAfter: 10, fit: 'shrink' });
        }

        function chartOpts(t, cd, box, small) {
            var fs = small ? 9 : 11;
            if (t === 'pie') return { x: box.x, y: box.y, w: box.w, h: box.h, chartColors: pal.slice(0, Math.max(cd.values.length, 1)), showPercent: true, showLegend: true, legendPos: small ? 'b' : 'r', dataLabelFontSize: fs + 1 };
            var common = { x: box.x, y: box.y, w: box.w, h: box.h, showValue: true, dataLabelFontSize: fs, catAxisLabelFontSize: fs, valAxisLabelFontSize: fs, valGridLine: { color: 'E3E8EE', size: 0.5 } };
            return t === 'line' ? Object.assign(common, { chartColors: [pal[0]], lineSize: 3, lineDataSymbolSize: small ? 6 : 8, showLegend: false })
                                : Object.assign(common, { barDir: 'col', chartColors: [pal[0]], showLegend: false });
        }
        function chartType(t) { return t === 'pie' ? P.charts.PIE : (t === 'line' ? P.charts.LINE : P.charts.BAR); }

        // 2-4 charts ek hi slide par (AI ne same "slide" naam diya ho)
        function addChartGroup(group) {
            var cs = P.addSlide(), k = group.length, cols = k === 2 ? 2 : (k === 3 ? 3 : 2), rowsN = k === 4 ? 2 : 1;
            var heads = group.map(function (g) { return g.heading || f.title; });
            cs.addText(f.title, { x: 0.6, y: 0.3, w: 12, h: 0.7, fontFace: 'Calibri', fontSize: 24, bold: true, color: f.primary, fit: 'shrink' });
            var gap = 0.25, W = 12.1, H = 5.7, cw = (W - gap * (cols - 1)) / cols, ch = (H - gap * (rowsN - 1)) / rowsN;
            group.forEach(function (g, i) {
                var cx = 0.6 + (i % cols) * (cw + gap), cy = 1.2 + Math.floor(i / cols) * (ch + gap), cd = chartData(g);
                cs.addText(g.heading || '', { x: cx, y: cy, w: cw, h: 0.35, fontFace: 'Calibri', fontSize: 13, bold: true, color: '3B4754', fit: 'shrink' });
                cs.addChart(chartType(g.chart.type), [{ name: cd.name, labels: cd.labels, values: cd.values }], chartOpts(g.chart.type, cd, { x: cx, y: cy + 0.4, w: cw, h: ch - 0.45 }, true));
            });
        }

        function addChartSlide(sec, head, narrow) {
            var cd = chartData(sec), cs = P.addSlide(), t = sec.chart.type;
            cs.addText(head, { x: 0.6, y: 0.3, w: 12, h: 0.7, fontFace: 'Calibri', fontSize: 26, bold: true, color: f.primary });
            cs.addChart(chartType(t), [{ name: cd.name, labels: cd.labels, values: cd.values }], chartOpts(t, cd, narrow ? { x: 0.5, y: 1.2, w: 7.3, h: 5.6 } : { x: 0.6, y: 1.2, w: 12.1, h: 5.7 }, !!narrow));
            return cs;
        }

        function addTables(sec, head, onSlide) {
            var n = sec.columns.length, nums = numCols(sec);
            if (hasTable(sec)) {
                var PER = n > 7 ? 10 : 11, pages = onSlide ? 1 : Math.max(1, Math.ceil(sec.rows.length / PER));
                for (var p = 0; p < pages; p++) {
                    var sl = onSlide || P.addSlide();
                    if (!onSlide) sl.addText(head + (pages > 1 ? '  (' + (p + 1) + '/' + pages + ')' : ''), { x: 0.6, y: 0.3, w: 12, h: 0.7, fontFace: 'Calibri', fontSize: 24, bold: true, color: f.primary });
                    var rows = [sec.columns.map(function (c, ci) {
                        return { text: String(c), options: { bold: true, color: onA, fill: { color: f.accent }, align: nums.indexOf(ci) >= 0 ? 'right' : 'left' } };
                    })];
                    sec.rows.slice(p * PER, (p + 1) * PER).forEach(function (r, ri) {
                        rows.push(sec.columns.map(function (c, ci) {
                            return { text: fmtNum(r[ci]), options: { align: nums.indexOf(ci) >= 0 ? 'right' : 'left', fill: { color: ri % 2 ? mix(f.accent, 0.92) : 'FFFFFF' }, color: '28323C' } };
                        }));
                    });
                    if (sec.totals && p === pages - 1) {
                        rows.push(sec.totals.map(function (v, ci) {
                            return { text: fmtNum(v), options: { bold: true, align: nums.indexOf(ci) >= 0 ? 'right' : 'left', fill: { color: mix(f.accent, 0.8) }, color: '1B2430' } };
                        }));
                    }
                    sl.addTable(rows, onSlide ? { x: 8.0, y: 1.5, w: 4.7, fontFace: 'Calibri', fontSize: 12, border: { type: 'solid', color: 'D9E1EA', pt: 0.5 }, margin: 0.06 } : { x: 0.6, y: 1.2, w: 12.1, fontFace: 'Calibri', fontSize: n > 7 ? 10 : 12, border: { type: 'solid', color: 'D9E1EA', pt: 0.5 }, margin: 0.06 });
                    sl.addText('StockFlow AI Assistant' + (sec.truncated ? '  |  partial list (row limit reached)' : ''), { x: 0.6, y: 7.0, w: 9, h: 0.3, fontSize: 9, color: '8896A5' });
                }
            }
        }

        var done = {};
        f.sections.forEach(function (sec, si) {
            if (done[si]) return;
            var head = sec.heading || f.title, plot = hasChart(sec) && sec.rows.length;
            if (plot && sec.slide) {
                var group = [];
                for (var j = si; j < f.sections.length && group.length < 4; j++) {
                    var o = f.sections[j];
                    if (o.slide === sec.slide && hasChart(o) && o.rows.length) { group.push(o); done[j] = 1; }
                }
                if (group.length > 1) { addChartGroup(group); group.forEach(function (g) { addTables(g, g.heading || f.title); }); return; }
            }
            // chhota table + chart: ek hi slide par (chart left, table right)
            if (plot && hasTable(sec) && sec.rows.length <= 9 && sec.columns.length <= 4) { addTables(sec, head, addChartSlide(sec, head, true)); return; }
            if (plot) addChartSlide(sec, head);
            addTables(sec, head);
        });
        return P.write({ outputType: 'blob' });
    }

    /* ---------- public ---------- */

    function make(fmt, file) {
        return loadLibs(fmt).then(function () {
            if (fmt === 'xlsx') return buildXlsx(file);
            if (fmt === 'pdf') return buildPdf(file);
            return buildPptx(file);
        });
    }


    /* ---------- AI-likha code: locked worker me chalta hai ---------- */

    var WORKER = '/js/ai-sandbox-worker.js';
    var retries = 0;

    // Worker tabhi chalega jab server ne use "network band" CSP ke saath diya ho
    function checkWorkerPolicy() {
        return fetch(WORKER, { credentials: 'same-origin', cache: 'no-store' }).then(function (r) {
            var csp = r.headers.get('content-security-policy') || '';
            if (!r.ok || csp.indexOf("connect-src 'none'") < 0) throw new Error('The secure report sandbox is not available. Please contact your admin.');
        });
    }

    function runCode(p) {
        return checkWorkerPolicy().then(function () {
            return new Promise(function (resolve, reject) {
                var w = new Worker(WORKER), over = false;
                function fin() { over = true; clearTimeout(t); w.terminate(); }      // code browser se hat jaata hai
                var t = setTimeout(function () { if (!over) { fin(); reject(new Error('The report took too long to build.')); } }, 60000);
                w.onmessage = function (e) {
                    var m = e.data || {};
                    if (over) return;
                    fin();
                    if (m.t !== 'done') { reject(new Error(m.msg || 'The report code failed.')); return; }
                    var files = (m.files || []).slice(0, 5).filter(function (f) { return f && f.blob instanceof Blob && /\.(xlsx|pdf|pptx)$/i.test(f.name || ''); });
                    if (!files.length) reject(new Error('The report code did not produce a valid file.')); else resolve(files);
                };
                w.onerror = function (e) { if (!over) { fin(); reject(new Error((e && e.message) || 'The report code failed.')); } };
                w.postMessage({ t: 'run', code: p.code, formats: p.formats, data: p.data });
            });
        });
    }

    /* ---------- permission popup (composer ke upar, chat theme ke saath) ---------- */

    var STYLE_ID = 'ai-perm-style';
    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        var st = document.createElement('style');
        st.id = STYLE_ID;
        st.textContent =
            '.ai-perm{position:fixed;z-index:2100;box-sizing:border-box;background:#fff;border:1px solid #e4dcfb;border-radius:18px;padding:16px 18px;' +
            'box-shadow:0 18px 48px rgba(43,15,92,.26);font-family:inherit;opacity:0;transform:translateY(14px) scale(.98);' +
            'transition:opacity .28s ease,transform .32s cubic-bezier(.2,.9,.3,1.15);}' +
            '.ai-perm.in{opacity:1;transform:none;}' +
            '.ai-perm.out{opacity:0;transform:translateY(10px) scale(.98);transition:opacity .2s ease,transform .2s ease;}' +
            '.ai-perm .pm-row{display:flex;gap:12px;align-items:flex-start;}' +
            '.ai-perm .pm-ic{width:40px;height:40px;flex:0 0 40px;border-radius:12px;display:flex;align-items:center;justify-content:center;color:#fff;' +
            'background:linear-gradient(135deg,#6c3ce0,#8b5cf6);}' +
            '.ai-perm .pm-ic svg{width:20px;height:20px;}' +
            '.ai-perm h4{margin:0 0 3px;font-size:15px;font-weight:800;color:#2b0f5c;}' +
            '.ai-perm p{margin:0;font-size:12.5px;line-height:1.5;color:#6b6591;}' +
            '.ai-perm .pm-meta{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px;}' +
            '.ai-perm .pm-tag{font-size:11.5px;font-weight:700;color:#6c3ce0;background:#f3edff;border-radius:999px;padding:3px 10px;}' +
            '.ai-perm .pm-act{margin-top:14px;display:flex;gap:10px;justify-content:flex-end;}' +
            '.ai-perm button{font-family:inherit;font-size:13px;font-weight:700;border-radius:11px;padding:9px 18px;cursor:pointer;' +
            'transition:transform .12s ease,box-shadow .15s ease,background .15s ease,opacity .15s ease;}' +
            '.ai-perm .pm-no{background:#fff;color:#6b6591;border:1px solid #e4dcfb;}' +
            '.ai-perm .pm-no:hover{background:#faf8ff;border-color:#cfc3f5;}' +
            '.ai-perm .pm-yes{border:none;color:#fff;background:linear-gradient(135deg,#6c3ce0,#8b5cf6);box-shadow:0 6px 16px rgba(108,60,224,.32);}' +
            '.ai-perm .pm-yes:hover{transform:translateY(-1px);box-shadow:0 9px 20px rgba(108,60,224,.4);}' +
            '.ai-perm button:disabled{opacity:.6;cursor:default;transform:none;}' +
            '.ai-perm .pm-bar{height:4px;border-radius:4px;background:#f3edff;overflow:hidden;margin-top:12px;}' +
            '.ai-perm .pm-bar i{display:block;height:100%;width:40%;border-radius:4px;background:linear-gradient(90deg,#6c3ce0,#8b5cf6);animation:aiPmSlide 1.1s ease-in-out infinite;}' +
            '@keyframes aiPmSlide{0%{margin-left:-40%}100%{margin-left:100%}}' +
            '.ai-perm.ok .pm-ic{background:linear-gradient(135deg,#1f9d63,#34c38f);}' +
            '.ai-perm.bad .pm-ic{background:linear-gradient(135deg,#d6336c,#f06595);}';
        document.head.appendChild(st);
    }

    var ICON_FILE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 14h6M9 17h4"/></svg>';
    var ICON_OK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
    var ICON_BAD = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 8v5M12 17h.01"/><circle cx="12" cy="12" r="9"/></svg>';
    var current = null;

    function place(card) {
        var box = document.querySelector('.ai-compbox') || document.querySelector('#aiInput') || null;
        var r = box ? box.getBoundingClientRect() : { left: 16, width: window.innerWidth - 32, top: window.innerHeight - 90 };
        var w = Math.min(560, Math.max(280, r.width), window.innerWidth - 24);
        card.style.width = w + 'px';
        card.style.left = Math.max(12, r.left + (r.width - w) / 2) + 'px';
        card.style.bottom = Math.max(12, window.innerHeight - r.top + 12) + 'px';
    }

    function closeCard(card, after) {
        if (!card) return;
        card.classList.remove('in'); card.classList.add('out');
        window.removeEventListener('resize', card._rs);
        document.removeEventListener('keydown', card._kd, true);
        setTimeout(function () { card.remove(); if (after) after(); }, 230);
        if (current === card) current = null;
    }

    /**
     * Permission popup. opts: {title, what, tags[], run:()=>Promise, onDeny, onDone, onFail(msg)}
     * Allow dabane par hi file banti hai; "Not now" par kuch nahi banta aur data mita diya jaata hai.
     */
    function askPermission(opts) {
        ensureStyle();
        if (current) closeCard(current);
        var card = document.createElement('div');
        card.className = 'ai-perm';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-live', 'polite');
        current = card;
        var tags = (opts.tags || []).map(function (t) { return '<span class="pm-tag">' + String(t).replace(/[<>&"]/g, '') + '</span>'; }).join('');
        card.innerHTML =
            '<div class="pm-row"><div class="pm-ic">' + ICON_FILE + '</div><div style="min-width:0">' +
            '<h4></h4><p></p><div class="pm-meta">' + tags + '</div></div></div>' +
            '<div class="pm-act"><button type="button" class="pm-no">Not now</button><button type="button" class="pm-yes">Allow &amp; create</button></div>';
        card.querySelector('h4').textContent = 'Create ' + opts.what + '?';
        var msg = card.querySelector('p');
        msg.textContent = 'Your report is ready. It is built privately in this browser and downloaded to your device. Nothing is stored on any server.';
        document.body.appendChild(card);
        place(card);
        card._rs = function () { place(card); };
        window.addEventListener('resize', card._rs);
        requestAnimationFrame(function () { requestAnimationFrame(function () { card.classList.add('in'); }); });

        var yes = card.querySelector('.pm-yes'), no = card.querySelector('.pm-no'), act = card.querySelector('.pm-act');
        var expire = setTimeout(function () { if (card.parentNode) { closeCard(card, function () { if (opts.onDeny) opts.onDeny(true); }); } }, TTL_MS);
        function setState(kind, title, text) {
            card.classList.remove('ok', 'bad');
            if (kind) card.classList.add(kind);
            card.querySelector('.pm-ic').innerHTML = kind === 'ok' ? ICON_OK : (kind === 'bad' ? ICON_BAD : ICON_FILE);
            card.querySelector('h4').textContent = title;
            msg.textContent = text;
        }
        function finish(kind, title, text, ms) {
            clearTimeout(expire);
            setState(kind, title, text);
            var bar = card.querySelector('.pm-bar'); if (bar) bar.remove();
            act.innerHTML = '';
            setTimeout(function () { closeCard(card); }, ms);
        }
        no.addEventListener('click', function () {
            clearTimeout(expire);
            closeCard(card, function () { if (opts.onDeny) opts.onDeny(false); });
        });
        card._kd = function (e) { if (e.key === 'Escape') { e.stopPropagation(); no.click(); } };
        document.addEventListener('keydown', card._kd, true);
        yes.addEventListener('click', function () {
            yes.disabled = true; no.disabled = true; yes.textContent = 'Creating...';
            setState('', 'Creating ' + opts.what + '...', 'Please wait a moment. Your file will download automatically.');
            var bar = document.createElement('div'); bar.className = 'pm-bar'; bar.innerHTML = '<i></i>';
            card.insertBefore(bar, act);
            Promise.resolve().then(opts.run).then(function () {
                if (opts.onDone) opts.onDone();
                finish('ok', 'Your file is ready', 'Downloaded. The report data and code have been removed from this page.', 2200);
            }).catch(function (e) {
                var m = e && e.message ? e.message : 'failed';
                var willRetry = opts.onFail ? opts.onFail(m) : false;
                if (willRetry) finish('', 'Improving your report...', 'The first attempt had a problem. Fixing it automatically.', 2200);
                else finish('bad', 'Could not create the file', m, 4200);
            });
        });
        setTimeout(function () { yes.focus(); }, 320);
    }

    /**
     * Chat reply ke saath file aane par permission poochhta hai.
     * retry(text): AI se code theek karwane ke liye; note(text): chat me chhota local message.
     */
    function attach(rowEl, payload, retry, note) {
        if (!payload) return;
        var isCode = !!(payload.code && payload.data);
        if (!isCode && !(payload.sections && payload.sections.length)) return;
        var formats = (payload.formats || []).filter(function (f) { return LABEL[f]; });
        if (!formats.length) return;

        var data = JSON.parse(JSON.stringify(payload));      // apni copy; kaam ke baad mita di jaati hai
        var what = formats.map(function (f) { return LABEL[f]; }).join(' + ');
        var tags = [];
        if (isCode) { var rows = 0; data.data.forEach(function (d) { rows += d.rows.length; }); tags.push(rows + ' rows'); }
        else { var n = 0; data.sections.forEach(function (s) { n += s.rows.length; }); tags.push(n + ' rows'); if (data.sections.length > 1) tags.push(data.sections.length + ' sections'); }
        if (data.title) tags.unshift(String(data.title).slice(0, 40));

        askPermission({
            what: what, tags: tags,
            run: function () {
                if (isCode) {
                    return runCode({ title: data.title, formats: data.formats, code: data.code, data: data.data }).then(function (files) {
                        files.forEach(function (f, i) { setTimeout(function () { download(f.blob, f.name); }, i * 450); });
                    });
                }
                var chain = Promise.resolve(), base = cleanName(data.title) + '-' + stamp();
                formats.forEach(function (fmt, i) {
                    chain = chain.then(function () { return make(fmt, data); }).then(function (blob) {
                        setTimeout(function () { download(blob, base + '.' + EXT[fmt]); }, i * 450);
                    });
                });
                return chain;
            },
            onDone: function () { data = null; retries = 0; },
            onDeny: function (expired) {
                data = null;
                if (note) note(expired
                    ? 'The file request expired, so I did not create the ' + what + '. Ask me again whenever you need it.'
                    : 'Okay, I did not create the ' + what + '. Just ask again whenever you need it.');
            },
            onFail: function (msg) {
                if (isCode && retries < 2 && typeof retry === 'function' && msg.indexOf('sandbox is not available') < 0) {
                    retries++; data = null;
                    retry('The report code failed with this error: ' + msg + '. Please fix it and build the file again.');
                    return true;
                }
                retries = 0; data = null;
                return false;
            }
        });
    }

    window.AiExport = { attach: attach, make: make };
})();