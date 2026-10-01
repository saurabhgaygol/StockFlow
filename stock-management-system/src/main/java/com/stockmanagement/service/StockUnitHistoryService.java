package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.StockUnitHistory;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.repository.StockUnitHistoryRepository;
import com.stockmanagement.repository.VendorRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockUnitHistoryService {

    public static final String INWARD = "INWARD";
    public static final String SOLD = "SOLD";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";

    private final StockUnitHistoryRepository historyRepo;
    private final StockInwardRepository stockRepo;
    private final ProductCategoryRepository categoryRepo;
    private final VendorRepository vendorRepo;
    private final UserPermissionService permissionService;

    public StockUnitHistoryService(StockUnitHistoryRepository historyRepo,
                                   StockInwardRepository stockRepo,
                                   ProductCategoryRepository categoryRepo,
                                   VendorRepository vendorRepo,
                                   UserPermissionService permissionService) {
        this.historyRepo = historyRepo;
        this.stockRepo = stockRepo;
        this.categoryRepo = categoryRepo;
        this.vendorRepo = vendorRepo;
        this.permissionService = permissionService;
    }

    public record DeviceView(StockInward unit, String productName, List<StockUnitHistory> events) {
        public StockInward getUnit() { return unit; }
        public String getProductName() { return productName; }
        public List<StockUnitHistory> getEvents() { return events; }
    }

    public void record(StockInward unit, String eventType, String fromStatus, String toStatus,
                       Long requestId, String requestNo, String customerName, BigDecimal amount,
                       String reason, Long actorId, String actorName) {
        StockUnitHistory h = new StockUnitHistory();
        h.setCompanyName(unit.getCompanyName());
        h.setStockId(unit.getId());
        h.setImeiNumber(unit.getImeiNumber());
        h.setProductName(productNameOf(unit.getProductId()));
        h.setEventType(eventType);
        h.setFromStatus(fromStatus);
        h.setToStatus(toStatus);
        h.setRequestId(requestId);
        h.setRequestNo(requestNo);
        h.setCustomerName(customerName);
        h.setAmount(amount);
        h.setReason(reason == null || reason.isBlank() ? null : trim(reason.trim(), 1000));
        h.setActorId(actorId);
        h.setActorName(actorName);
        historyRepo.save(h);
    }

    public void recordInward(StockInward unit, String actorName) {
        String vendor = unit.getVendorId() == null ? null
                : vendorRepo.findById(unit.getVendorId()).map(v -> v.getVendorName()).orElse(null);
        StringBuilder note = new StringBuilder("Received");
        if (vendor != null) note.append(" from ").append(vendor);
        if (unit.getInvoiceNumber() != null && !unit.getInvoiceNumber().isBlank()) {
            note.append(", invoice ").append(unit.getInvoiceNumber().trim());
        }
        record(unit, INWARD, null, unit.getStatus(), null, null, null, unit.getUnitPrice(),
                note.toString(), null, actorName);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> search(String query, CustomUserDetails actor) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 4) {
            throw new IllegalArgumentException("Type at least 4 characters of the IMEI.");
        }
        UserTable me = actor.getUser();
        boolean superAdmin = permissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN");

        List<StockInward> units = superAdmin
                ? stockRepo.findTop10ByImeiNumberContainingOrderByIdDesc(q)
                : stockRepo.findTop10ByImeiNumberContainingAndCompanyNameOrderByIdDesc(q, me.getCompanyName());

        List<DeviceView> out = new ArrayList<>();
        for (StockInward u : units) {
            out.add(new DeviceView(u, productNameOf(u.getProductId()),
                    historyRepo.findByStockIdOrderByCreatedAtAscIdAsc(u.getId())));
        }
        return out;
    }

    private String productNameOf(Long productId) {
        if (productId == null) return null;
        return categoryRepo.findById(productId).map(ProductCategory::getProductName).orElse(null);
    }

    private String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}