/* ============================================================
   stock-outward.js - Stock Outward panel
   (tabs, search, new-request popup, customer suggestions, total)
   ============================================================ */
(function () {
    'use strict';

    var activeTab = null;
    var suggestTimer = null;

    function el(id) { return document.getElementById(id); }

    /* ============================================================
       LIST: tabs + search   (list load hone ke baad chalta hai)
       ============================================================ */
    window.soInit = function () {
        var rows = document.querySelectorAll('#soTable .so-row');
        var awaiting = document.querySelectorAll('#soTable .so-row[data-awaiting="true"]').length;

        // approver ke liye kuch pending ho to "Awaiting my approval" tab khulta hai
        activeTab = awaiting > 0 ? 'awaiting' : 'all';
        var tabs = document.querySelectorAll('.so-tab');
        tabs.forEach(function (t) {
            t.classList.toggle('active', t.dataset.tab === activeTab);
            t.addEventListener('click', function () {
                activeTab = t.dataset.tab;
                tabs.forEach(function (x) { x.classList.toggle('active', x === t); });
                applyFilter();
            });
        });

        var search = el('soSearch');
        if (search) search.addEventListener('input', applyFilter);

        rows.forEach(function (row) {
            row.addEventListener('click', function (e) {
                if (e.target.closest('a')) return;
                window.location.href = row.dataset.href;
            });
        });

        applyFilter();
    };

    function matchesTab(row) {
        var st = row.dataset.status;
        switch (activeTab) {
            case 'awaiting': return row.dataset.awaiting === 'true';
            case 'mine':     return row.dataset.mine === 'true';
            case 'hold':     return st === 'ON_HOLD';
            case 'issued':   return st === 'ISSUED';
            case 'closed':   return st === 'REJECTED' || st === 'CANCELLED';
            default:         return true;
        }
    }

    function applyFilter() {
        var q = (el('soSearch') ? el('soSearch').value : '').toLowerCase().trim();
        var rows = document.querySelectorAll('#soTable .so-row');
        var shown = 0;
        rows.forEach(function (row) {
            var ok = matchesTab(row) && (q === '' || row.innerText.toLowerCase().indexOf(q) > -1);
            row.style.display = ok ? '' : 'none';
            if (ok) shown++;
        });
        var none = el('soNoMatch');
        if (none) none.style.display = shown === 0 ? '' : 'none';
        var info = el('soShowing');
        if (info) info.textContent = 'Showing ' + shown + ' of ' + rows.length;
    }

    /* ============================================================
       POPUP: open / close
       ============================================================ */
    window.openRequestModal = function () {
        var m = el('soModal');
        if (!m) return;
        if (!el('soItems').children.length) window.soAddRow();
        m.classList.add('open');
        updateTotal();
    };

    window.closeRequestModal = function () {
        var m = el('soModal');
        if (m) m.classList.remove('open');
    };

    /* ============================================================
       PRODUCTS: rows, "in stock" hint, total
       ============================================================ */
    window.soAddRow = function () {
        var tpl = el('soRowTemplate');
        var box = el('soItems');
        if (!tpl || !box) return;
        box.appendChild(tpl.content.cloneNode(true));
    };

    window.soRemoveRow = function (btn) {
        var box = el('soItems');
        var row = btn.closest('.so-item-row');
        if (!row || !box) return;
        if (box.querySelectorAll('.so-item-row').length > 1) {
            var hint = row.nextElementSibling;
            if (hint && hint.classList.contains('so-item-hint')) hint.remove();
            row.remove();
        } else {
            row.querySelector('select').value = '';
            row.querySelector('input[name=quantity]').value = 1;
            row.querySelector('input[name=unitPrice]').value = '';
            updateHint(row);
        }
        updateTotal();
    };

    /* har product row ke neeche "in stock" hint (sirf jaankari, approver decide karte hain) */
    function updateHint(row) {
        var sel = row.querySelector('select');
        var qty = parseInt(row.querySelector('input[name=quantity]').value, 10) || 0;
        var hint = row.nextElementSibling;
        if (!hint || !hint.classList.contains('so-item-hint')) {
            hint = document.createElement('div');
            hint.className = 'so-item-hint so-muted';
            row.parentNode.insertBefore(hint, row.nextSibling);
        }
        var opt = sel.options[sel.selectedIndex];
        if (!sel.value || !opt) { hint.textContent = ''; hint.classList.remove('warn'); return; }
        var avail = parseInt(opt.dataset.available, 10) || 0;
        if (qty > avail) {
            hint.textContent = 'Only ' + avail + ' in stock right now. You can still raise the request - approvers will see this.';
            hint.classList.add('warn');
        } else {
            hint.textContent = avail + ' in stock.';
            hint.classList.remove('warn');
        }
    }

    /* kul amount = sab rows ka (qty x price) */
    function updateTotal() {
        var box = el('soTotalBox');
        if (!box) return;
        var total = 0, any = false;
        document.querySelectorAll('#soItems .so-item-row').forEach(function (row) {
            var q = parseFloat(row.querySelector('input[name=quantity]').value) || 0;
            var p = parseFloat(row.querySelector('input[name=unitPrice]').value);
            if (!isNaN(p) && q > 0) { total += q * p; any = true; }
        });
        box.classList.toggle('so-hidden', !any);
        el('soTotal').textContent = '\u20B9 ' + total.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function onRowChange(e) {
        var row = e.target.closest && e.target.closest('#soItems .so-item-row');
        if (!row) return;
        updateHint(row);
        updateTotal();
    }
    document.addEventListener('change', onRowChange);
    document.addEventListener('input', onRowChange);

    /* ============================================================
       CUSTOMER: suggestion list, pick, clear
       ============================================================ */
    function custBase() {
        // list page ka apna address + /customers (address badle to bhi kaam karta hai)
        return window.location.pathname.replace(/\/+$/, '') + '/customers';
    }

    var FIELD_IDS = ['so-customerName', 'so-customerMobile', 'so-customerCompany', 'so-customerGst',
                     'so-customerEmail', 'so-customerAddress', 'so-customerCity', 'so-customerState',
                     'so-customerPincode'];

    /* purane customer ka state list mein na ho to bhi dikhe */
    function setState(value) {
        var sel = el('so-customerState');
        if (!value) { sel.value = ''; return; }
        var found = Array.prototype.some.call(sel.options, function (o) { return o.value === value; });
        if (!found) {
            var opt = document.createElement('option');
            opt.value = value;
            opt.textContent = value;
            sel.appendChild(opt);
        }
        sel.value = value;
    }

    function pickCustomer(c) {
        el('so-customerId').value = c.id || '';
        el('so-customerName').value = c.name || '';
        el('so-customerMobile').value = c.mobile || '';
        el('so-customerCompany').value = c.company || '';
        el('so-customerGst').value = c.gst || '';
        el('so-customerEmail').value = c.email || '';
        el('so-customerAddress').value = c.addressLine || '';
        el('so-customerCity').value = c.city || '';
        setState(c.state);
        el('so-customerPincode').value = c.pincode || '';

        el('soPickedName').textContent = c.name + (c.company ? '  \u00B7  ' + c.company : '');
        el('soPickedSub').textContent = c.mobile + (c.city ? '  \u00B7  ' + c.city : '') +
                                        (c.state ? ', ' + c.state : '');
        el('soPicked').classList.remove('so-hidden');
        el('soCustHint').style.display = 'none';
        el('so-cust-search').value = '';
        el('soSuggest').classList.remove('open');
    }

    function clearCustomer() {
        el('so-customerId').value = '';
        FIELD_IDS.forEach(function (id) { el(id).value = ''; });
        el('so-cust-search').value = '';
        el('soPicked').classList.add('so-hidden');
        el('soCustHint').style.display = '';
        el('so-cust-search').focus();
    }

    function renderSuggestions(list) {
        var box = el('soSuggest');
        box.innerHTML = '';
        if (!list.length) {
            var none = document.createElement('div');
            none.className = 'so-suggest-empty';
            none.textContent = 'No existing customer found - fill the details below to add a new one.';
            box.appendChild(none);
        }
        list.forEach(function (c) {
            var item = document.createElement('div');
            item.className = 'so-suggest-item';

            var top = document.createElement('div');
            var strong = document.createElement('b');
            strong.textContent = c.name;
            top.appendChild(strong);
            if (c.company) top.appendChild(document.createTextNode('  \u00B7  ' + c.company));

            var sub = document.createElement('div');
            sub.className = 'so-suggest-sub';
            sub.textContent = c.mobile + (c.city ? '  \u00B7  ' + c.city : '') +
                              (c.gst ? '  \u00B7  GST ' + c.gst : '') + (c.email ? '  \u00B7  ' + c.email : '');

            item.appendChild(top);
            item.appendChild(sub);
            item.addEventListener('mousedown', function (e) {
                e.preventDefault();
                pickCustomer(c);
            });
            box.appendChild(item);
        });
        box.classList.add('open');
    }

    document.addEventListener('input', function (e) {
        if (e.target.id !== 'so-cust-search') return;
        var q = e.target.value.trim();
        clearTimeout(suggestTimer);
        if (q.length < 2) { el('soSuggest').classList.remove('open'); return; }
        suggestTimer = setTimeout(function () {
            fetch(custBase() + '?q=' + encodeURIComponent(q), { headers: { 'Accept': 'application/json' } })
                .then(function (r) { return r.ok ? r.json() : []; })
                .then(renderSuggestions)
                .catch(function () { el('soSuggest').classList.remove('open'); });
        }, 250);
    });

    document.addEventListener('click', function (e) {
        if (e.target.id === 'soPickedClear') { clearCustomer(); return; }
        var box = el('soSuggest');
        if (box && !e.target.closest('.so-search-box')) box.classList.remove('open');
    });

    /* ============================================================
       POPUP band karna (bahar click ya Esc)
       ============================================================ */
    function bindModalOnce() {
        var m = el('soModal');
        if (m && !m.dataset.bound) {
            m.dataset.bound = 'true';
            m.addEventListener('click', function (e) { if (e.target === this) window.closeRequestModal(); });
        }
        if (window.__soKeydown) document.removeEventListener('keydown', window.__soKeydown);
        window.__soKeydown = function (e) { if (e.key === 'Escape') window.closeRequestModal(); };
        document.addEventListener('keydown', window.__soKeydown);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindModalOnce);
    else bindModalOnce();
})();