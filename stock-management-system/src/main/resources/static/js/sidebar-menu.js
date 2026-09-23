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

});