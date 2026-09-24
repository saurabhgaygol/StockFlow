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