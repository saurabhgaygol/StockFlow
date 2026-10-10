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
        '@media (prefers-reduced-motion:reduce){html.sf-nav-reveal #page-content{animation:none}}';
    document.head.appendChild(style);

    var MAX_WAIT_MS = 3000;   // CSS kisi wajah se fail ho to bhi page dikhe
    var token = 0;            // jaldi-jaldi click par sirf latest navigation reveal kare
    var revealTimer = null;

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