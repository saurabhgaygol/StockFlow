function openVendorModal()  { document.getElementById('vendorModal').classList.add('open'); }
function closeVendorModal() { document.getElementById('vendorModal').classList.remove('open'); }
function closeEditModal()   { document.getElementById('vendorEditModal').classList.remove('open'); }

function openEditModal(row) {
    document.getElementById('editVendorForm').action = '/settings/vendors/edit/' + row.dataset.id;
    document.getElementById('edit-vendorName').value    = row.dataset.name    || '';
    document.getElementById('edit-contactPerson').value = row.dataset.contact || '';
    document.getElementById('edit-phone').value         = row.dataset.phone   || '';
    document.getElementById('edit-email').value         = row.dataset.email   || '';
    document.getElementById('edit-address').value       = row.dataset.address || '';
    document.getElementById('edit-gstNumber').value     = row.dataset.gst     || '';
    document.getElementById('vendorEditModal').classList.add('open');
}

function initVendorModals() {
    ['vendorModal', 'vendorEditModal'].forEach(function (mid) {
        var el = document.getElementById(mid);
        if (el && !el.dataset.bound) {
            el.dataset.bound = 'true';
            el.addEventListener('click', function (e) {
                if (e.target === this) this.classList.remove('open');
            });
        }
    });
}

document.addEventListener('DOMContentLoaded', initVendorModals);
document.body.addEventListener('htmx:afterSwap', initVendorModals);