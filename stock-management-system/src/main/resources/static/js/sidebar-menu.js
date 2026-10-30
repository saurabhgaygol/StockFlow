document.addEventListener('DOMContentLoaded', function () {

    document.querySelectorAll('.sb-group-head').forEach(function (head) {
        head.addEventListener('click', function () {
            var group = head.closest('.sb-group');
            group.classList.toggle('open');
        });
        head.style.cursor = 'pointer';
    });

    document.querySelectorAll('.sb-group').forEach(function (group) {
        if (group.querySelector('.sb-item.active')) {
            group.classList.add('open');
        }
    });

    document.querySelectorAll('.sb-item').forEach(function (link) {
        link.addEventListener('click', function () {
            document.querySelectorAll('.sb-item').forEach(function (el) {
                el.classList.remove('active');
            });
            link.classList.add('active');

            var parentGroup = link.closest('.sb-group');
            if (parentGroup) {
                parentGroup.classList.add('open');
            }
        });
    });

});

/* =====================================================================
 * FOUC FIX (sabhi pages: Stock Inward, Stock Outward, Approval Chain,
 * Vendors, Users, Reports)
 *
 * Sidebar click par htmx sirf #page-content swap karta hai. Naye page ki
 * CSS us swap ke BAAD aati hai, isliye milliseconds ke liye bina CSS wala
 * page dikhta tha.
 *
 * Ab: swap hote hi (browser paint se pehle) naya content hide ho jata hai,
 * page ki CSS load hone tak wait karta hai, phir halke fade-in ke saath
 * dikhta hai. Sidebar aur topbar hamesha stable rehte hain.
 *
 * NOTE: hide ka flag <html> par rakha hai, #page-content par nahi - kyunki
 * htmx settle ke waqt naye #page-content ke attributes (class/style) reset
 * kar deta hai aur wahan lagayi class ~20ms mein hat jati hai.
 * ===================================================================== */
(function () {

    var root = document.documentElement;

    // chhoti si CSS yahin se inject hoti hai - koi CSS file edit nahi karni
    var style = document.createElement('style');
    style.textContent =
        'html.sf-nav-wait #page-content{visibility:hidden !important}' +
        'html.sf-nav-reveal #page-content{animation:sf-page-reveal .22s ease-out}' +
        '@keyframes sf-page-reveal{from{opacity:0}to{opacity:1}}' +
        '@media (prefers-reduced-motion:reduce){html.sf-nav-reveal #page-content{animation:none}}' +
        /* global "Loading..." overlay: sidebar click se naya page dikhne tak (sabhi pages) */
        '#sf-nav-loader{position:fixed;z-index:900;display:none;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:rgba(246,247,251,.9)}' +
        '#sf-nav-loader.show{display:flex}' +
        '#sf-nav-loader .sf-nl-spin{width:44px;height:44px;box-sizing:border-box;border-radius:50%;border:4px solid #e4dcfb;border-top-color:#6c3ce0;animation:sf-nl-spin .8s linear infinite}' +
        '#sf-nav-loader .sf-nl-text{font-size:13px;letter-spacing:.02em;color:#6b7190;font-family:inherit}' +
        '@keyframes sf-nl-spin{to{transform:rotate(360deg)}}' +
        '@media (prefers-reduced-motion:reduce){#sf-nav-loader .sf-nl-spin{animation-duration:2.4s}}';
    document.head.appendChild(style);

    var MAX_WAIT_MS = 3000;   // CSS kisi wajah se fail ho to bhi page dikhe
    var token = 0;            // jaldi-jaldi click par sirf latest navigation reveal kare
    var revealTimer = null;

    /* ---------- global loader: dikhao / chhupao ---------- */
    var loaderShowTimer = null;
    var loaderSafetyTimer = null;

    var keepMode = false;       // true = page ka apna loader hai, global spinner uske khatam hone tak chale
    var ownObserver = null;

    function resetKeep() {
        keepMode = false;
        root.classList.remove('sf-nav-keep');
        if (ownObserver) {
            ownObserver.disconnect();
            ownObserver = null;
        }
    }

    function hideNavLoader() {
        clearTimeout(loaderShowTimer);
        clearTimeout(loaderSafetyTimer);
        resetKeep();
        var el = document.getElementById('sf-nav-loader');
        if (el) {
            el.classList.remove('show');
        }
    }

    // Kuch pages (Stock Inward/Outward, Audit Logs, Dashboard) apna #page-body tab tak chhupa ke rakhte hain
    // jab tak unka data / CSS ready na ho ("sf-hidden" ya "sd-wait" class). Us dauran global spinner chalta
    // rehta hai, taaki khali page na dikhe. Gate khulte hi (ya hat jane par) spinner hat jata hai.
    function gateClosed() {
        var gate = document.getElementById('page-body');
        return !!(gate && (gate.classList.contains('sf-hidden') || gate.classList.contains('sd-wait')));
    }

    function watchGate() {
        var interval = setInterval(function () {
            if (!gateClosed()) {
                hideNavLoader();   // resetKeep() ke zariye ye interval bhi band ho jata hai
            }
        }, 16);
        ownObserver = { disconnect: function () { clearInterval(interval); } };
    }

    function showNavLoader() {
        clearTimeout(loaderShowTimer);
        clearTimeout(loaderSafetyTimer);
        resetKeep();
        // 120ms ke baad hi dikhao, taaki bahut tez page par spinner jhapak kar na jaye
        loaderShowTimer = setTimeout(function () {
            var pc = document.getElementById('page-content');
            if (!pc) {
                return;
            }
            var el = document.getElementById('sf-nav-loader');
            if (!el) {
                el = document.createElement('div');
                el.id = 'sf-nav-loader';
                el.setAttribute('role', 'status');
                el.setAttribute('aria-live', 'polite');
                el.innerHTML = '<div class="sf-nl-spin"></div><div class="sf-nl-text">Loading...</div>';
                document.body.appendChild(el);
            }
            // sirf content wale hisse par (sidebar / topbar khule rahenge)
            var r = pc.getBoundingClientRect();
            el.style.top = r.top + 'px';
            el.style.left = r.left + 'px';
            el.style.width = r.width + 'px';
            el.style.height = r.height + 'px';
            el.classList.add('show');
        }, 120);
        // safety: kuch bhi atak jaye to spinner 20s se zyada na ruke
        loaderSafetyTimer = setTimeout(hideNavLoader, 20000);
    }

    // listeners sirf ek baar lagao (script kisi page me dobara aaye to double na ho)
    if (!window.__sfNavLoaderInit) {
        window.__sfNavLoaderInit = true;

        document.body.addEventListener('htmx:beforeRequest', function (evt) {
            var d = evt.detail || {};
            if (!d.target || d.target.id !== 'page-content') {
                return;   // table search / chhote updates par spinner nahi
            }
            var cfg = d.requestConfig;
            if (cfg && cfg.verb && String(cfg.verb).toLowerCase() !== 'get') {
                return;   // form save (POST) par nahi
            }
            showNavLoader();
        });

        ['htmx:responseError', 'htmx:sendError', 'htmx:timeout', 'htmx:swapError'].forEach(function (name) {
            document.body.addEventListener(name, hideNavLoader);
        });
    }

    document.body.addEventListener('htmx:afterSwap', function (evt) {
        var pc = evt.target;
        // sirf tab jab poora #page-content swap hua ho (sidebar navigation);
        // table search / data refresh jaise chhote htmx updates ko touch nahi karta
        if (!pc || pc.id !== 'page-content') {
            return;
        }

        // sirf GET navigation (sidebar click) par chalao. Form save (POST) jaise swaps
        // ke apne transitions hote hain (jaise Users page ka hx-swap="... transition:true"),
        // unke beech me page hide nahi karna.
        var reqCfg = evt.detail && evt.detail.requestConfig;
        if (reqCfg && reqCfg.verb && String(reqCfg.verb).toLowerCase() !== 'get') {
            return;
        }

        var myToken = ++token;

        // Global spinner abhi dikh raha hai? Nahi (page bahut tez aaya): pending timer bhi band,
        // spinner aayega hi nahi. Haan: neeche reveal() me hatega (ya gate khulne par).
        var navEl = document.getElementById('sf-nav-loader');
        var navShown = !!(navEl && navEl.classList.contains('show'));
        if (!navShown) {
            hideNavLoader();
        }

        // 1) naya content turant hide (paint se pehle, same task me)
        clearTimeout(revealTimer);
        root.classList.remove('sf-nav-reveal');
        root.classList.add('sf-nav-wait');

        // 2) is page ki jo stylesheets abhi load ho rahi hain unka wait
        var pending = [];
        pc.querySelectorAll('link[rel="stylesheet"]').forEach(function (link) {
            if (!link.sheet) {
                pending.push(new Promise(function (resolve) {
                    link.addEventListener('load', resolve);
                    link.addEventListener('error', resolve);
                }));
            }
        });

        // 3) CSS ready + 2 frames (layout settle) -> fade-in
        var done = false;
        function reveal() {
            if (done || myToken !== token) {
                return;
            }
            done = true;
            requestAnimationFrame(function () {
                requestAnimationFrame(function () {
                    if (myToken !== token) {
                        return;
                    }
                    root.classList.remove('sf-nav-wait');
                    root.classList.add('sf-nav-reveal');
                    // htmx settle (~20ms) ke baad dekho: page ka #page-body abhi band hai (data aa raha hai)?
                    //  - haan: spinner chalta rahe, body khulte hi hatega (khali page nahi dikhega)
                    //  - nahi: naya page tayyar, spinner hatao
                    setTimeout(function () {
                        if (myToken !== token) {
                            return;
                        }
                        if (gateClosed()) {
                            if (!keepMode) {
                                keepMode = true;
                                watchGate();
                            }
                        } else {
                            hideNavLoader();
                        }
                    }, 30);
                    revealTimer = setTimeout(function () {
                        root.classList.remove('sf-nav-reveal');
                    }, 300);
                });
            });
        }

        Promise.all(pending).then(reveal);
        setTimeout(reveal, MAX_WAIT_MS);
    });

})();

/* =====================================================================
 * ADDRESS BAR: sidebar ya htmx click par URL nahi badlega.
 * Hamesha wahi pehla URL dikhega jis par page full load hua tha.
 * ===================================================================== */
(function () {
    if (!window.htmx) {
        return;
    }

    // htmx ki history / URL push band
    htmx.config.historyEnabled = false;

    // safety: agar kisi wajah se phir bhi push ho jaye, to URL pehle wale par wapas
    var firstUrl = window.location.pathname + window.location.search;
    document.body.addEventListener('htmx:pushedIntoHistory', function () {
        window.history.replaceState(null, '', firstUrl);
    });
})();

/* =====================================================================
 * ADDRESS BAR HAMESHA DASHBOARD + RELOAD PAR LAST PAGE
 * (upar ke address bar block ko chhue bina):
 *  - Address bar me hamesha Dashboard ka URL dikhta hai, kuch aur nahi.
 *  - Jis page par ho, uska path sessionStorage (sirf is tab ke liye) me yaad.
 *  - Reload par (Dashboard URL khulne ke baad) yaad rakha page wapas load hota hai.
 *  - F5 / Ctrl+R par browser reload nahi hota, sirf page ka content
 *    (#page-content) refresh hota hai, isliye side menu nahi jhapakta.
 *    (Ctrl+F5, Shift+F5, Ctrl+Shift+R aur browser ka reload button normal reload
 *    karte hain; tab bhi reload ke baad yaad rakha page wapas aa jata hai.)
 *  - Logout par yaad rakha page hata diya jata hai.
 *
 * GUARD: pages ke andar ye script dobara include hoti hai aur har htmx swap
 * ke saath dobara chal jati hai, isliye ye block sirf ek baar chalta hai.
 * ===================================================================== */
(function () {
    if (!window.htmx) {
        return;
    }
    if (window.__sfLastPageInit) {
        return;
    }
    window.__sfLastPageInit = true;

    var KEY = 'sf.lastPage';
    var root = document.documentElement;
    var currentUrl = window.location.pathname + window.location.search;

    function store(path) {
        try { window.sessionStorage.setItem(KEY, path); } catch (e) {}
    }
    function load() {
        try { return window.sessionStorage.getItem(KEY); } catch (e) { return null; }
    }
    function clearStore() {
        try { window.sessionStorage.removeItem(KEY); } catch (e) {}
    }

    // kya ye page browser reload se khula?
    function isReload() {
        try {
            var nav = window.performance.getEntriesByType('navigation')[0];
            if (nav && nav.type) { return nav.type === 'reload'; }
            return window.performance.navigation && window.performance.navigation.type === 1;
        } catch (e) {
            return false;
        }
    }

    // Dashboard ka URL: sidebar ke flat link jiska naam "Dashboard" hai
    // (na mile to pehla flat link, wo bhi na ho to current URL)
    var dashboardUrl = currentUrl;
    var flat = document.querySelectorAll('.sb-flat-item');
    var found = false;
    for (var i = 0; i < flat.length; i++) {
        var href = flat[i].getAttribute('href');
        if (!href) { continue; }
        if (flat[i].textContent.trim() === 'Dashboard') {
            dashboardUrl = href;
            found = true;
            break;
        }
    }
    if (!found) {
        for (var j = 0; j < flat.length; j++) {
            var h2 = flat[j].getAttribute('href');
            if (h2) { dashboardUrl = h2; break; }
        }
    }

    // sidebar me us page ko active dikhao
    function markActive(path) {
        var items = document.querySelectorAll('.sb-item');
        var base = String(path).split('?')[0];
        var hit = null;
        items.forEach(function (el) {
            var h = el.getAttribute('href');
            if (h && h.split('?')[0] === base) { hit = el; }
        });
        if (!hit) { return; }
        items.forEach(function (el) { el.classList.remove('active'); });
        hit.classList.add('active');
        var g = hit.closest('.sb-group');
        if (g) { g.classList.add('open'); }
    }

    // sirf page ka content laao, side menu ko mat chhuo
    function loadContent(path, onFail) {
        var req = htmx.ajax('GET', path, {
            target: '#page-content',
            select: '#page-content',
            swap: 'outerHTML'
        });
        if (req && typeof req.catch === 'function') {
            req.catch(onFail);
        }
    }

    var restorePath = null;
    if (isReload()) {
        var saved = load();
        if (saved && saved !== currentUrl) {
            restorePath = saved;
        } else if (!saved) {
            store(currentUrl);
        }
    } else {
        // naya link / redirect / typed URL: ye page yaad rakho
        store(currentUrl);
    }

    // abhi jo page screen par hai (F5 par yahi refresh hoga)
    var currentPage = restorePath ? restorePath : currentUrl;

    // address bar hamesha Dashboard par
    try { window.history.replaceState(null, '', dashboardUrl); } catch (e) {}

    // safety: koi bhi URL push ho to wapas Dashboard par
    document.body.addEventListener('htmx:pushedIntoHistory', function () {
        try { window.history.replaceState(null, '', dashboardUrl); } catch (e) {}
    });

    // jab bhi poora #page-content GET se swap ho, wo page yaad rakho
    document.body.addEventListener('htmx:afterSwap', function (evt) {
        var pc = evt.target;
        if (!pc || pc.id !== 'page-content') { return; }
        var cfg = evt.detail && evt.detail.requestConfig;
        if (cfg && cfg.verb && String(cfg.verb).toLowerCase() !== 'get') { return; }
        var pi = evt.detail && evt.detail.pathInfo;
        var p = pi && (pi.finalRequestPath || pi.requestPath);
        if (p) {
            store(p);
            currentPage = p;
        }
    });

    // logout par yaad rakha page hatao
    document.querySelectorAll('.sb-logout').forEach(function (btn) {
        var f = btn.closest('form');
        if (f) { f.addEventListener('submit', clearStore); }
    });

    // F5 / Ctrl+R: browser reload ki jagah sirf page content refresh
    document.addEventListener('keydown', function (e) {
        var isF5 = e.key === 'F5';
        var isCtrlR = (e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey &&
                      (e.key === 'r' || e.key === 'R');
        if (!isF5 && !isCtrlR) { return; }
        if (isF5 && (e.ctrlKey || e.shiftKey || e.metaKey)) { return; }
        if (!currentPage) { return; }
        e.preventDefault();
        loadContent(currentPage, function () {
            // refresh fail ho (jaise session expire) to normal reload
            window.location.reload();
        });
    });

    // reload ke baad pichla page wapas load karo
    if (restorePath) {
        root.classList.add('sf-nav-wait');   // pehla page ek pal ko bhi na dikhe
        markActive(restorePath);
        loadContent(restorePath, function () {
            // load fail ho to jo page khula hai wahi rehne do
            clearStore();
            root.classList.remove('sf-nav-wait');
        });
    }
})();