/**
 * Minimal client-side fragment router (design doc section 2). Clicking a
 * sidebar link fetches that page's fragment endpoint and swaps it into
 * #main-content, updating the URL with history.pushState so the address bar
 * stays on a real, bookmarkable path under http://localhost:8080/ without a
 * full page reload - no SPA framework, just fetch + pushState.
 *
 * Every fragment endpoint is protected server-side by the SAME
 * @PreAuthorize annotation as the corresponding full-page controller method
 * (see UserController, RoleController, etc.) - this script is a UX
 * convenience, never the security boundary.
 */
(function () {
    var mainContent = document.getElementById('main-content');
    var pageTitle = document.getElementById('sms-page-title');

    function csrfHeaders(base) {
        var headers = base || {};
        var tokenMeta = document.querySelector('meta[name="_csrf"]');
        var headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (tokenMeta && headerMeta) {
            headers[headerMeta.content] = tokenMeta.content;
        }
        return headers;
    }

    function loadFragment(fragmentUrl, targetUrl, label, pushState) {
        fetch(fragmentUrl, {headers: csrfHeaders({'X-Requested-With': 'XMLHttpRequest'})})
            .then(function (res) {
                if (res.status === 401) {
                    window.location.href = '/login';
                    return null;
                }
                if (res.status === 403) {
                    return res.text().then(function () {
                        mainContent.innerHTML = '<div class="alert alert-danger">You do not have permission to view this page.</div>';
                        return null;
                    });
                }
                return res.text();
            })
            .then(function (html) {
                if (html === null || html === undefined) {
                    return;
                }
                mainContent.innerHTML = html;
                if (label && pageTitle) {
                    pageTitle.textContent = label;
                }
                if (pushState) {
                    window.history.pushState({fragmentUrl: fragmentUrl}, '', targetUrl);
                }
                markActiveNav(targetUrl);
            })
            .catch(function () {
                window.location.href = targetUrl; // fall back to a normal navigation on any client error
            });
    }

    function markActiveNav(targetUrl) {
        document.querySelectorAll('.nav-link-fragment').forEach(function (link) {
            link.classList.toggle('active', link.getAttribute('href') === targetUrl);
        });
    }

    document.querySelectorAll('.nav-link-fragment').forEach(function (link) {
        link.addEventListener('click', function (e) {
            var fragmentUrl = link.getAttribute('data-fragment-url');
            var targetUrl = link.getAttribute('href');
            if (!fragmentUrl) {
                return; // no fragment endpoint registered for this item - let the browser navigate normally
            }
            e.preventDefault();
            loadFragment(fragmentUrl, targetUrl, link.textContent.trim(), true);
        });
    });

    window.addEventListener('popstate', function (e) {
        if (e.state && e.state.fragmentUrl) {
            loadFragment(e.state.fragmentUrl, window.location.pathname, null, false);
        }
    });
})();
