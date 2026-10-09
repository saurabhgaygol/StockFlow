/* ============================================================
   staff-stock.js - "Issue to Staff" popup on the Stock Outward panel
   tabs: give (office -> staff) | back (staff -> office) | move (staff -> staff) | hist
   ============================================================ */
(function () {
    'use strict';

    var staff = null, office = null, loadedTab = {};

    function $(id) { return document.getElementById(id); }
    function getJson(url) {
        return fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { if (!r.ok) throw new Error('x'); return r.json(); });
    }
    function td(tr, text, cls) {
        var c = document.createElement('td'); c.textContent = text == null || text === '' ? '-' : text;
        if (cls) c.className = cls; tr.appendChild(c); return c;
    }

    /* ---------- staff: ONE box - type to search, click to select ---------- */
    function label(s) {
        return s.name + (s.city ? ' (' + s.city + ')' : '') + ' - ' + s.inHand + ' in hand'
            + (s.temporary ? ', ' + s.temporary + ' fitted (temporary)' : '');
    }
    /* keeps the hidden <select> (the form value) filled with ALL staff */
    function fillStaff(selId, qId) {
        var sel = $(selId), keep = sel.value;
        sel.innerHTML = '';
        var ph = document.createElement('option'); ph.value = ''; ph.textContent = '- choose staff -';
        sel.appendChild(ph);
        (staff || []).forEach(function (s) {
            var o = document.createElement('option'); o.value = s.id; o.textContent = label(s);
            sel.appendChild(o);
        });
        if (keep) {
            sel.value = keep;
            var q = $(qId), cur = (staff || []).filter(function (s) { return String(s.id) === String(keep); })[0];
            if (q && cur) q.value = label(cur);
        }
    }
    function renderCombo(selId, qId, filter) {
        var list = $(selId + 'List'), q = $(qId).value.trim().toLowerCase();
        list.innerHTML = '';
        var rows = (staff || []).filter(function (s) {
            return !filter || !q || ((s.name || '') + ' ' + (s.city || '')).toLowerCase().indexOf(q) !== -1;
        });
        rows.forEach(function (s) {
            var d = document.createElement('div'); d.className = 'ss-combo-item';
            var b = document.createElement('b'); b.textContent = s.name + (s.city ? ' (' + s.city + ')' : '');
            var sp = document.createElement('span'); sp.textContent = s.inHand + ' in hand' + (s.temporary ? ', ' + s.temporary + ' temporary' : '');
            d.appendChild(b); d.appendChild(sp);
            d.addEventListener('mousedown', function (e) {
                e.preventDefault();
                var sel = $(selId);
                sel.value = s.id;
                $(qId).value = label(s);
                list.style.display = 'none';
                sel.dispatchEvent(new Event('change'));
            });
            list.appendChild(d);
        });
        if (!rows.length) { var e = document.createElement('div'); e.className = 'ss-combo-empty'; e.textContent = staff ? 'No staff found.' : 'Loading...'; list.appendChild(e); }
        list.style.display = 'block';
    }
    function withStaff(fn) {
        if (staff) { fn(); return; }
        getJson('/settings/staff-stock/staff').then(function (d) { staff = d; fn(); })
            .catch(function () { alert('Could not load the staff list. Please try again.'); });
    }

    /* ---------- device list with checkboxes ---------- */
    function renderUnits(prefix, units) {
        var body = $(prefix + 'Body');
        body.innerHTML = '';
        var q = ($(prefix + 'Q').value || '').trim().toLowerCase();
        var shown = 0;
        (units || []).forEach(function (u) {
            var hay = ((u.category || '') + ' ' + (u.product || '') + ' ' + (u.imei || '')).toLowerCase();
            if (q && hay.indexOf(q) === -1) return;
            shown++;
            var tr = document.createElement('tr');
            var c0 = document.createElement('td');
            var cb = document.createElement('input'); cb.type = 'checkbox'; cb.name = 'stockIds'; cb.value = u.id;
            cb.setAttribute('data-p', prefix);
            cb.addEventListener('change', function () { tr.classList.toggle('sel', cb.checked); count(prefix); });
            c0.appendChild(cb); tr.appendChild(c0);
            td(tr, u.category); td(tr, u.product); td(tr, u.imei);
            if (u.since !== undefined) td(tr, u.since);
            tr.addEventListener('click', function (e) { if (e.target !== cb) { cb.checked = !cb.checked; cb.dispatchEvent(new Event('change')); } });
            body.appendChild(tr);
        });
        if (!shown) {
            var tr2 = document.createElement('tr'), c = document.createElement('td');
            c.colSpan = 5; c.className = 'ss-empty'; c.textContent = (units && units.length) ? 'No device matches your search.' : 'No device found.';
            tr2.appendChild(c); body.appendChild(tr2);
        }
        count(prefix);
    }
    function count(prefix) {
        var n = document.querySelectorAll('#' + prefix + 'Body input[type=checkbox]:checked').length;
        $(prefix + 'Count').textContent = n + ' selected';
    }

    var unitsOf = { ssGive: null, ssBack: null, ssMove: null };

    function loadOffice() {
        $('ssGiveBody').innerHTML = '<tr><td colspan="5" class="ss-empty">Loading...</td></tr>';
        var run = function (d) { office = d; unitsOf.ssGive = d; renderUnits('ssGive', d); };
        if (office) { run(office); return; }
        getJson('/settings/staff-stock/office-units').then(run)
            .catch(function () { $('ssGiveBody').innerHTML = '<tr><td colspan="5" class="ss-empty">Could not load stock.</td></tr>'; });
    }
    function loadStaffUnits(prefix, selId) {
        var id = $(selId).value;
        if (!id) { unitsOf[prefix] = []; $(prefix + 'Body').innerHTML = '<tr><td colspan="5" class="ss-empty">Choose a staff member.</td></tr>'; count(prefix); return; }
        $(prefix + 'Body').innerHTML = '<tr><td colspan="5" class="ss-empty">Loading...</td></tr>';
        getJson('/settings/staff-stock/units?staffId=' + encodeURIComponent(id))
            .then(function (d) { unitsOf[prefix] = d; renderUnits(prefix, d); })
            .catch(function () { $(prefix + 'Body').innerHTML = '<tr><td colspan="5" class="ss-empty">Could not load devices.</td></tr>'; });
    }

    function loadHistory() {
        var body = $('ssHistBody');
        body.innerHTML = '<tr><td colspan="8" class="ss-empty">Loading...</td></tr>';
        getJson('/settings/staff-stock/history').then(function (rows) {
            body.innerHTML = '';
            if (!rows.length) { body.innerHTML = '<tr><td colspan="8" class="ss-empty">Nothing yet.</td></tr>'; return; }
            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                td(tr, r.when); td(tr, r.type); td(tr, r.product); td(tr, r.imei);
                td(tr, r.from); td(tr, r.to); td(tr, r.by); td(tr, r.batch);
                body.appendChild(tr);
            });
        }).catch(function () { body.innerHTML = '<tr><td colspan="8" class="ss-empty">Could not load history.</td></tr>'; });
    }

    /* ---------- public functions (called from the HTML) ---------- */
    window.ssOpen = function () { $('ssModal').classList.add('open'); window.ssTab(window.__ssLast || 'give'); };
    window.ssClose = function () { $('ssModal').classList.remove('open'); };

    window.ssTab = function (name) {
        window.__ssLast = name;
        document.querySelectorAll('.ss-tab').forEach(function (t) { t.classList.toggle('active', t.getAttribute('data-tab') === name); });
        document.querySelectorAll('#ssModal [data-panel]').forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== name; });
        if (name === 'give') withStaff(function () { fillStaff('ssGiveStaff', 'ssGiveStaffQ'); loadOffice(); });
        if (name === 'back') withStaff(function () { fillStaff('ssBackStaff', 'ssBackStaffQ'); loadStaffUnits('ssBack', 'ssBackStaff'); });
        if (name === 'move') withStaff(function () {
            fillStaff('ssMoveFrom', 'ssMoveFromQ'); fillStaff('ssMoveTo', 'ssMoveToQ');
            loadStaffUnits('ssMove', 'ssMoveFrom');
        });
        if (name === 'hist') loadHistory();
    };

    window.ssComboOpen = function (selId, qId) { withStaff(function () { renderCombo(selId, qId, false); }); };
    window.ssComboType = function (selId, qId) {
        var sel = $(selId);
        if (sel.value) { sel.value = ''; sel.dispatchEvent(new Event('change')); }   /* typing again = choose again */
        withStaff(function () { renderCombo(selId, qId, true); });
    };
    document.addEventListener('click', function (e) {
        document.querySelectorAll('.ss-combo-list').forEach(function (l) {
            if (!l.parentNode.contains(e.target)) l.style.display = 'none';
        });
    });
    window.ssStaffPicked = function (prefix, selId) { loadStaffUnits(prefix, selId); };
    window.ssFilter = function (prefix) { renderUnits(prefix, unitsOf[prefix] || []); };

    window.ssCheck = function (form, needTo) {
        var prefix = form.getAttribute('data-p');
        var n = form.querySelectorAll('input[name=stockIds]:checked').length;
        if (!n) { alert('Select at least one device.'); return false; }
        var sel = form.querySelector('select[data-need]');
        if (sel && !sel.value) { alert('Choose the staff member.'); return false; }
        if (needTo) {
            var from = $('ssMoveFrom').value, to = $('ssMoveTo').value;
            if (!to) { alert('Choose the staff member who will receive the devices.'); return false; }
            if (from === to) { alert('Choose two different staff members.'); return false; }
        }
        if (form.id === 'ssBackForm') {
            var dm = form.querySelector('input[name=backTo][value=DAMAGED]');
            var rm = form.querySelector('textarea[name=remarks]');
            if (dm && dm.checked && !rm.value.trim()) { alert('Write the damage details in Remarks.'); rm.focus(); return false; }
        }
        return confirm(n + ' device(s) will be moved. Continue?');
    };

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && $('ssModal')) window.ssClose();
    });
})();