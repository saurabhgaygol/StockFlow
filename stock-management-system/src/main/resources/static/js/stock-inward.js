/* ============================================================
   stock-inward.js — Stock Inward page logic
   ============================================================ */

(function () {
    'use strict';

    var bulkRows = [];   // parsed rows from Excel/CSV

    /* ---------- Init ---------- */
    function initPage() {
        initStockModals();
        initLiveCalculations();
        initSearch();
        initPagination();
        initBulkUpload();
    }

    // Data (htmx) aane ke baad HTML se dobara call hota hai
    window.sfStockInit = initPage;

    // Full page load + htmx swap dono me chale
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initPage);
    } else {
        initPage();
    }

    /* ================= MODALS ================= */

    window.openStockModal = function () {
        var modal = document.getElementById('stockModal');
        if (modal) {
            modal.classList.add('open');
            calculateAdd();
        }
    };

    window.closeStockModal = function () {
        var m = document.getElementById('stockModal');
        if (m) m.classList.remove('open');
    };

    window.closeEditStockModal = function () {
        var m = document.getElementById('stockEditModal');
        if (m) m.classList.remove('open');
    };

    window.openEditStockModal = function (row) {
        var d = row.dataset;

        document.getElementById('editStockForm').action =
            '/settings/stock-inward/edit/' + d.id;

        setVal('edit-vendorId', d.vendorid);
        setVal('edit-categoryId', d.categoryid);
        setVal('edit-productDropdown', d.productid);   // ✅ HTML ke saath match
        setVal('edit-imeiNumber', d.imeinumber);
        setVal('edit-condition', d.condition || 'NEW');
        setVal('edit-quantity', d.quantity);
        setVal('edit-unitPrice', d.unitprice);
        setVal('edit-taxPercent', d.taxpercent);
        setVal('edit-purchaseDate', d.purchasedate);
        setVal('edit-warrantyMonths', d.warrantyperiodmonths);
        setVal('edit-warrantyStart', d.warrantystartdate);
        setVal('edit-invoiceNumber', d.invoicenumber);
        setVal('edit-poNumber', d.ponumber);
        setVal('edit-batchNumber', d.batchnumber);
        setVal('edit-warehouse', d.warehouse);
        setVal('edit-status', d.status || 'AVAILABLE');
        setVal('edit-remarks', d.remarks);

        calculateEdit();
        document.getElementById('stockEditModal').classList.add('open');
    };

    function setVal(id, val) {
        var el = document.getElementById(id);
        if (el) el.value = (val === undefined || val === null) ? '' : val;
    }

    function initStockModals() {
        ['stockModal', 'stockEditModal', 'bulkModal'].forEach(function (mid) {
            var el = document.getElementById(mid);
            if (el && !el.dataset.bound) {
                el.dataset.bound = 'true';
                el.addEventListener('click', function (e) {
                    if (e.target === this) this.classList.remove('open');
                });
            }
        });

        // Purana Escape listener hatao, phir naya lagao (duplicate na ho)
        if (window.__sfStockKeydown) {
            document.removeEventListener('keydown', window.__sfStockKeydown);
        }
        window.__sfStockKeydown = function (e) {
            if (e.key === 'Escape') {
                window.closeStockModal();
                window.closeEditStockModal();
                window.closeBulkModal();
            }
        };
        document.addEventListener('keydown', window.__sfStockKeydown);
    }

    /* ================= LIVE CALCULATIONS ================= */

    function calcTotal(qty, unitPrice, taxPercent) {
        var q = parseFloat(qty) || 0;
        var u = parseFloat(unitPrice) || 0;
        var t = parseFloat(taxPercent) || 0;

        var total = q * u;
        var grand = total + (total * t / 100);
        return {
            total: total.toFixed(2),
            grand: grand.toFixed(2)
        };
    }

    function calcWarrantyEnd(startDate, months) {
        if (!startDate || !months) return '';
        var parts = startDate.split('-');
        if (parts.length !== 3) return '';
        var d = new Date(Date.UTC(+parts[0], +parts[1] - 1, +parts[2]));
        if (isNaN(d.getTime())) return '';
        d.setUTCMonth(d.getUTCMonth() + parseInt(months, 10));
        return d.toISOString().slice(0, 10);
    }

    function calculateAdd() {
        var c = calcTotal(
            val('add-qty'), val('add-unitPrice'), val('add-taxPercent')
        );
        setVal('add-total', c.total);
        setVal('add-grandTotal', c.grand);

        var wEnd = calcWarrantyEnd(val('add-warrantyStart'), val('add-warrantyMonths'));
        setVal('add-warrantyEnd', wEnd);
    }

    function calculateEdit() {
        var c = calcTotal(
            val('edit-quantity'), val('edit-unitPrice'), val('edit-taxPercent')
        );
        setVal('edit-total', c.total);
        setVal('edit-grandTotal', c.grand);

        var wEnd = calcWarrantyEnd(val('edit-warrantyStart'), val('edit-warrantyMonths'));
        setVal('edit-warrantyEnd', wEnd);
    }

    function val(id) {
        var el = document.getElementById(id);
        return el ? el.value : '';
    }

    function initLiveCalculations() {
        ['add-qty', 'add-unitPrice', 'add-taxPercent',
         'add-warrantyStart', 'add-warrantyMonths'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el && !el.dataset.calcBound) {
                el.dataset.calcBound = 'true';
                el.addEventListener('input', calculateAdd);
            }
        });

        ['edit-quantity', 'edit-unitPrice', 'edit-taxPercent',
         'edit-warrantyStart', 'edit-warrantyMonths'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el && !el.dataset.calcBound) {
                el.dataset.calcBound = 'true';
                el.addEventListener('input', calculateEdit);
            }
        });
    }

    /* ================= SEARCH ================= */

    function initSearch() {
        var input = document.getElementById('stockSearch');
        if (!input || input.dataset.bound) return;
        input.dataset.bound = 'true';
        input.addEventListener('input', function () {
            var q = this.value.toLowerCase().trim();
            var rows = document.querySelectorAll('#stockTable tbody .stock-row');
            rows.forEach(function (row) {
                row.style.display = q === '' || row.textContent.toLowerCase().indexOf(q) > -1
                    ? '' : 'none';
            });
        });
    }

    /* ================= PAGINATION (stub) ================= */

    function initPagination() {
        var info = document.getElementById('pageInfo');
        if (info) info.textContent = 'Page 1 of 1';
    }

    /* ================= EXCEL EXPORT (CSV) ================= */

    window.exportStockExcel = function () {
        var rows = document.querySelectorAll('#stockTable tbody .stock-row');
        if (!rows.length) { alert('Nothing to export.'); return; }

        var headers = ['Serial No','Model','Vendor','Category','Qty',
                       'Purchase Date','Warranty End','Status'];
        var csv = [headers.join(',')];

        rows.forEach(function (row) {
            var cells = row.querySelectorAll('td');
            var line = [];
            for (var i = 0; i < 8; i++) {
                var text = cells[i] ? cells[i].textContent.replace(/"/g, '""') : '';
                line.push('"' + text + '"');
            }
            csv.push(line.join(','));
        });

        var blob = new Blob(['\uFEFF' + csv.join('\n')], { type: 'text/csv;charset=utf-8;' });
        var link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = 'stock-inward-' + new Date().toISOString().slice(0, 10) + '.csv';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(link.href);
    };

    /* ============================================================
       BULK UPLOAD
       ============================================================ */

    window.openBulkModal = function () {
        var modal = document.getElementById('bulkModal');
        if (modal) modal.classList.add('open');
    };

    window.closeBulkModal = function () {
        var modal = document.getElementById('bulkModal');
        if (modal) modal.classList.remove('open');
    };

    window.clearBulkFile = function () {
        bulkRows = [];
        var input = document.getElementById('bulkFileInput');
        if (input) input.value = '';

        var info = document.getElementById('bulkFileInfo');
        if (info) info.classList.add('sf-hidden');

        var wrap = document.getElementById('bulkPreviewWrap');
        if (wrap) wrap.classList.add('sf-hidden');

        var btn = document.getElementById('bulkImportBtn');
        if (btn) btn.disabled = true;

        var tbody = document.querySelector('#bulkPreviewTable tbody');
        if (tbody) tbody.innerHTML = '';

        var cnt = document.getElementById('bulkRowCount');
        if (cnt) cnt.textContent = '0';
    };

    function initBulkUpload() {
        var dz = document.getElementById('bulkDropzone');
        var input = document.getElementById('bulkFileInput');
        if (!dz || !input || dz.dataset.bound) return;
        dz.dataset.bound = 'true';

        dz.addEventListener('click', function () { input.click(); });

        ['dragenter', 'dragover'].forEach(function (ev) {
            dz.addEventListener(ev, function (e) {
                e.preventDefault();
                dz.classList.add('dragover');
            });
        });
        ['dragleave', 'drop'].forEach(function (ev) {
            dz.addEventListener(ev, function (e) {
                e.preventDefault();
                dz.classList.remove('dragover');
            });
        });
        dz.addEventListener('drop', function (e) {
            if (e.dataTransfer.files.length) {
                input.files = e.dataTransfer.files;
                handleBulkFile(e.dataTransfer.files[0]);
            }
        });

        input.addEventListener('change', function () {
            if (this.files.length) handleBulkFile(this.files[0]);
        });

        // Vendor change -> import button enable/disable
        var vendorEl = document.getElementById('bulk-vendorId');
        if (vendorEl) vendorEl.addEventListener('change', checkBulkImportEnabled);
    }

    function handleBulkFile(file) {
        var name = file.name.toLowerCase();
        if (!name.endsWith('.csv') && !name.endsWith('.xlsx') && !name.endsWith('.xls')) {
            alert('Sirf .csv, .xlsx ya .xls file allowed hai.');
            return;
        }

        document.getElementById('bulkFileName').textContent = file.name;
        document.getElementById('bulkFileInfo').classList.remove('sf-hidden');

        var reader = new FileReader();

        if (name.endsWith('.csv')) {
            reader.onload = function (e) { parseCSV(e.target.result); };
            reader.readAsText(file);
        } else {
            if (typeof XLSX === 'undefined') {
                alert('Excel parse karne ke liye SheetJS load karna padega (HTML me CDN add karo).');
                return;
            }
            reader.onload = function (e) {
                var data = new Uint8Array(e.target.result);
                var wb = XLSX.read(data, { type: 'array' });
                var sheet = wb.Sheets[wb.SheetNames[0]];
                // ✅ raw:false -> IMEI string milega, precision safe
                var json = XLSX.utils.sheet_to_json(sheet, { defval: '', raw: false });
                parseRows(json);
            };
            reader.readAsArrayBuffer(file);
        }
    }

    function parseCSV(text) {
        var lines = text.split(/\r?\n/).filter(function (l) { return l.trim(); });
        if (!lines.length) return;

        var headers = lines[0].split(',').map(function (h) { return h.trim(); });
        var json = [];

        for (var i = 1; i < lines.length; i++) {
            var cols = parseCsvLine(lines[i]);
            var obj = {};
            headers.forEach(function (h, idx) {
                obj[h] = (cols[idx] || '').trim();
            });
            json.push(obj);
        }
        parseRows(json);
    }

    // Quoted fields handle karne wala CSV line parser
    function parseCsvLine(line) {
        var out = [], cur = '', inQuotes = false;
        for (var i = 0; i < line.length; i++) {
            var ch = line[i];
            if (inQuotes) {
                if (ch === '"') {
                    if (line[i + 1] === '"') { cur += '"'; i++; }
                    else { inQuotes = false; }
                } else {
                    cur += ch;
                }
            } else {
                if (ch === '"') inQuotes = true;
                else if (ch === ',') { out.push(cur); cur = ''; }
                else cur += ch;
            }
        }
        out.push(cur);
        return out;
    }

    // XSS-safe text escape
    function esc(s) {
        var d = document.createElement('div');
        d.textContent = s == null ? '' : String(s);
        return d.innerHTML;
    }

    function parseRows(json) {
        bulkRows = [];
        var tbody = document.querySelector('#bulkPreviewTable tbody');
        if (!tbody) return;
        tbody.innerHTML = '';

        json.forEach(function (row, i) {
            // Headers ko ek baar lowercase map bana lo
            var lower = {};
            Object.keys(row).forEach(function (k) {
                var key = k.toLowerCase().trim().replace(/\*$/, '').trim();
                lower[key] = row[k];
            });

            function pick(keys) {
                for (var j = 0; j < keys.length; j++) {
                    if (lower[keys[j]] !== undefined && lower[keys[j]] !== '') {
                        return lower[keys[j]];
                    }
                }
                return '';
            }

            function num(v) {
                var n = parseFloat(v);
                return isNaN(n) ? null : n;
            }

            var item = {
                product:      String(pick(['product', 'product name'])).trim(),
                imeiNumber:   String(pick(['imei', 'imei number'])).trim(),
                categoryName: String(pick(['category', 'category name'])).trim(),
                condition:    String(pick(['condition'])).trim().toUpperCase() || 'NEW',
                quantity:     1,
                unitPrice:    num(pick(['unit price', 'price'])),
                taxPercent:   num(pick(['tax', 'tax %', 'taxpercent'])),
                status:       'AVAILABLE'
            };

            // ✅ Sirf product aur IMEI required
            var valid = item.product && item.imeiNumber;
            bulkRows.push(item);

            // ✅ 8 cells — HTML ke 8 headers se match
            var tr = document.createElement('tr');
            tr.className = valid ? 'sf-row-valid' : 'sf-row-invalid';
            tr.innerHTML =
                '<td>' + esc(i + 1) + '</td>' +
                '<td>' + (esc(item.product)      || '<span style="color:red">— required —</span>') + '</td>' +
                '<td>' + (esc(item.imeiNumber)   || '<span style="color:red">— required —</span>') + '</td>' +
                '<td>' + (esc(item.categoryName) || '<span style="color:#999">—</span>') + '</td>' +
                '<td>' + esc(item.condition) + '</td>' +
                '<td>' + (item.unitPrice != null ? item.unitPrice : '') + '</td>' +
                '<td>' + (item.taxPercent != null ? item.taxPercent : '') + '</td>' +
                '<td>' + esc(item.status) + '</td>';
            tbody.appendChild(tr);
        });

        document.getElementById('bulkRowCount').textContent = bulkRows.length;
        document.getElementById('bulkPreviewWrap').classList.remove('sf-hidden');

        checkBulkImportEnabled();
    }

    function checkBulkImportEnabled() {
        var vendorEl = document.getElementById('bulk-vendorId');
        var importBtn = document.getElementById('bulkImportBtn');
        if (!vendorEl || !importBtn) return;

        var vendorVal = vendorEl.value;
        var hasRows   = bulkRows.length > 0;
        var allValid  = hasRows && bulkRows.every(function (r) {
            return r.product && r.imeiNumber;   // ✅ serial hata diya
        });
        importBtn.disabled = !(vendorVal && allValid);
    }

})();