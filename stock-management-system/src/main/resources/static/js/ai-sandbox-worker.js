/*
 * StockFlow AI - sandbox worker. AI ka likha report-code yahin chalta hai.
 * Is worker ke paas DOM, page, cookies, storage kuch nahi hai. Server isse CSP header ke saath deta hai
 * (connect-src 'none', worker-src 'none', script-src 'self' + 'unsafe-eval'), isliye network bhi band hai.
 * Sirf postMessage se result wapas jaata hai; parent isko khatam (terminate) kar deta hai.
 */
'use strict';
var LIB_FILES = {
    xlsx: ['/js/vendor/exceljs.min.js'],
    pdf: ['/js/vendor/jspdf.umd.min.js', '/js/vendor/jspdf.plugin.autotable.min.js'],
    pptx: ['/js/vendor/pptxgen.bundle.js']
};
var MAX_FILE = 25 * 1024 * 1024;

self.onmessage = function (ev) {
    var m = ev.data || {};
    if (m.t !== 'run') return;
    self.onmessage = null;                       // sirf ek hi run
    var files = [];
    Promise.resolve().then(function () {
        var seen = {};
        (m.formats || []).forEach(function (f) {
            (LIB_FILES[f] || []).forEach(function (u) { if (!seen[u]) { seen[u] = 1; importScripts(self.location.origin + u); } });
        });
        var libs = {
            PptxGenJS: self.PptxGenJS,
            ExcelJS: self.ExcelJS,
            jsPDF: self.jspdf && self.jspdf.jsPDF,
            autoTable: self.jspdfAutoTable && self.jspdfAutoTable.default || self.autoTable
        };
        function emit(name, blob) {
            if (!(blob instanceof Blob)) throw new Error('emit() needs a Blob');
            if (blob.size > MAX_FILE) throw new Error('file too large');
            var n = String(name || 'report').replace(/[^A-Za-z0-9._ -]+/g, '_').slice(0, 80);
            files.push({ name: n, blob: blob });
            return Promise.resolve();
        }
        var AsyncFn = Object.getPrototypeOf(async function () {}).constructor;
        var fn = new AsyncFn('data', 'libs', 'emit', String(m.code || ''));
        return fn(m.data, libs, emit);
    }).then(function () {
        if (!files.length) throw new Error('code did not call emit(name, blob)');
        self.postMessage({ t: 'done', files: files });
    }).catch(function (e) {
        self.postMessage({ t: 'err', msg: String((e && e.message) || e).slice(0, 300) });
    });
};