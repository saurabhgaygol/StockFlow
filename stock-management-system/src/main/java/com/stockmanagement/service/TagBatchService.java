package com.stockmanagement.service;

import com.stockmanagement.entity.TagBatch;
import com.stockmanagement.entity.TagBatchItem;
import com.stockmanagement.entity.TagCounter;
import com.stockmanagement.entity.TagLabel;
import com.stockmanagement.repository.TagBatchItemRepository;
import com.stockmanagement.repository.TagBatchRepository;
import com.stockmanagement.repository.TagCounterRepository;
import com.stockmanagement.repository.TagLabelRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tag Studio > Generate & Print.
 *
 *  - A shop fills the "Create tags" form: business type + product rows (category, size, colour, style, MRP, qty).
 *  - Saving makes one TagBatch with its own number TAG-2026-000001 (per company, per year, never shared between
 *    companies), one TagBatchItem per row, and ONE barcode per row (a TagLabel): T2026000001-001, -002 ...
 *  - Qty = how many copies of that row's tag are printed. Qty 4 means 4 tags, all with the same barcode.
 *  - Edit: every detail and the Qty can be changed and products can be added, but a barcode is NEVER changed and a
 *    product is never deleted.
 *  - Tags are only for printing: stock is NOT changed here (that stays in Products & Variants).
 *  - Company always comes from the caller; every read is filtered by company.
 */
@Service
public class TagBatchService {

    public static final int MAX_ROWS = 200;
    public static final int MAX_QTY_PER_ROW = 500;
    public static final int MAX_TAGS_PER_BATCH = 1000;
    private static final int LIST_LIMIT = 300;

    /** what the form sends */
    public record ItemIn(Long id, String category, String size, String colour, String styleCode, BigDecimal mrp, Integer qty) {}
    public record BatchIn(String businessType, List<ItemIn> items) {}

    public record Created(Long id, String batchNo, int tags) {}

    /** result of an edit: tags (copies) now, how many new products (new barcodes) were added, rows changed */
    public record Updated(Long id, String batchNo, int tags, int added, int changedRows) {}

    /** one row of the list on the page */
    public record BatchRow(Long id, String batchNo, LocalDateTime createdAt, String businessType, String typeLabel,
                           String summary, int items, int tags, String createdBy, LocalDateTime updatedAt,
                           String updatedBy, String search) {}

    /** one product of a batch with its barcode; qty = how many copies of this tag are printed */
    public record TagView(Long id, int seqNo, String barcode, String category, String size, String colour,
                          String styleCode, BigDecimal mrp, int qty) {}

    /** one product row as the edit form needs it (id = the product row, not the barcode) */
    public record ItemView(Long id, int lineNo, String barcode, String category, String size, String colour,
                           String styleCode, BigDecimal mrp, int qty) {}

    public record BatchDetail(TagBatch batch, String typeLabel, TagStudioService.TagFieldSpec spec,
                              List<ItemView> items, List<TagView> tags) {}

    private final TagBatchRepository batchRepo;
    private final TagBatchItemRepository itemRepo;
    private final TagLabelRepository labelRepo;
    private final TagCounterRepository counterRepo;
    private final TagStudioService tagStudioService;

    public TagBatchService(TagBatchRepository batchRepo, TagBatchItemRepository itemRepo, TagLabelRepository labelRepo,
                           TagCounterRepository counterRepo, TagStudioService tagStudioService) {
        this.batchRepo = batchRepo;
        this.itemRepo = itemRepo;
        this.labelRepo = labelRepo;
        this.counterRepo = counterRepo;
        this.tagStudioService = tagStudioService;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public Created create(String company, BatchIn in, Long userId, String userName) {
        if (company == null || company.isBlank()) throw new IllegalArgumentException("No company is linked to your account.");
        if (in == null) throw new IllegalArgumentException("Nothing to save.");

        // business type: must be one of the types Super Admin gave this company
        List<String> allowed = tagStudioService.businessTypes(company);
        if (allowed.isEmpty()) throw new IllegalArgumentException("Tag Studio has no business type for your company yet.");
        String type = in.businessType() == null ? "" : in.businessType().trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty() && allowed.size() == 1) type = allowed.get(0);
        if (!allowed.contains(type)) throw new IllegalArgumentException("Please choose a business type.");
        TagStudioService.TagFieldSpec spec = tagStudioService.fieldSpec(type);

        List<ItemIn> clean = clean(in.items(), spec, false);
        int totalTags = 0;
        for (ItemIn r : clean) totalTags += r.qty();
        if (totalTags > MAX_TAGS_PER_BATCH) {
            throw new IllegalArgumentException("Up to " + MAX_TAGS_PER_BATCH + " tags in one batch. Split it into two batches.");
        }

        // number: per company and year, taken under a row lock
        int year = Year.now().getValue();
        long seq = nextSeq(company, year);
        String batchNo = String.format(Locale.ROOT, "TAG-%d-%06d", year, seq);
        String barcodePrefix = String.format(Locale.ROOT, "T%d%06d-", year, seq);

        TagBatch b = new TagBatch();
        b.setCompanyName(company);
        b.setBatchNo(batchNo);
        b.setBatchYear(year);
        b.setSeqNo((int) seq);
        b.setBusinessType(type);
        b.setTotalItems(clean.size());
        b.setTotalTags(totalTags);
        b.setCreatedByUserId(userId);
        b.setCreatedByName(userName);
        batchRepo.save(b);

        int line = 0, piece = 0;
        List<TagLabel> labels = new ArrayList<>(clean.size());
        for (ItemIn r : clean) {
            TagBatchItem it = new TagBatchItem();
            it.setBatchId(b.getId());
            it.setLineNo(++line);
            it.setCategory(r.category());
            it.setSizeLabel(r.size());
            it.setColour(r.colour());
            it.setStyleCode(r.styleCode());
            it.setMrp(r.mrp());
            it.setQty(r.qty());
            itemRepo.save(it);
            labels.add(label(company, b.getId(), it.getId(), ++piece, barcodePrefix));   // one barcode per row
        }
        labelRepo.saveAll(labels);
        return new Created(b.getId(), batchNo, totalTags);
    }

    // ------------------------------------------------------------------ edit a batch

    /**
     * Saves the edited rows of a batch. Rows with an id are existing products: every detail and the Qty may change,
     * the barcode stays. Rows without an id are new products and get the next barcode of the batch. Rows that are not
     * sent stay as they are (nothing is ever deleted).
     */
    @Transactional
    public Updated update(String company, Long batchId, BatchIn in, Long userId, String userName) {
        if (in == null) throw new IllegalArgumentException("Nothing to save.");
        TagBatch b = batchRepo.findLocked(batchId, company)
                .orElseThrow(() -> new IllegalArgumentException("Tag batch not found."));
        TagStudioService.TagFieldSpec spec = tagStudioService.fieldSpec(b.getBusinessType());
        List<ItemIn> rows = clean(in.items(), spec, true);

        Map<Long, TagBatchItem> items = new LinkedHashMap<>();
        for (TagBatchItem it : itemRepo.findByBatchIdOrderByLineNoAsc(b.getId())) items.put(it.getId(), it);
        List<TagLabel> labels = labelRepo.findByBatchIdAndCompanyNameOrderBySeqNoAsc(b.getId(), company);
        Set<Long> hasBarcode = new HashSet<>();
        int lastPiece = 0;
        for (TagLabel t : labels) {
            hasBarcode.add(t.getItemId());
            lastPiece = Math.max(lastPiece, nz(t.getSeqNo()));
        }
        int lastLine = 0;
        for (TagBatchItem it : items.values()) lastLine = Math.max(lastLine, nz(it.getLineNo()));

        // check everything first, so a bad row saves nothing
        Set<Long> seen = new HashSet<>();
        Map<Long, Integer> qtyAfter = new HashMap<>();
        for (TagBatchItem it : items.values()) qtyAfter.put(it.getId(), nz(it.getQty()));
        int newRows = 0, newQty = 0;
        for (int i = 0; i < rows.size(); i++) {
            ItemIn r = rows.get(i);
            if (r.id() == null) {
                newRows++;
                newQty += r.qty();
                continue;
            }
            if (!items.containsKey(r.id()) || !seen.add(r.id())) {
                throw new IllegalArgumentException("Row " + (i + 1) + ": This product is not part of the batch.");
            }
            qtyAfter.put(r.id(), r.qty());
        }
        int total = newQty;
        for (int q : qtyAfter.values()) total += q;
        if (total > MAX_TAGS_PER_BATCH) {
            throw new IllegalArgumentException("Up to " + MAX_TAGS_PER_BATCH + " tags in one batch. Make a new batch for the rest.");
        }
        if (items.size() + newRows > MAX_ROWS) {
            throw new IllegalArgumentException("Up to " + MAX_ROWS + " products in one batch. Make a new batch for the rest.");
        }

        String prefix = barcodePrefix(b);
        List<TagLabel> added = new ArrayList<>();
        int changed = 0;
        for (ItemIn r : rows) {
            TagBatchItem it;
            if (r.id() == null) {
                it = new TagBatchItem();
                it.setBatchId(b.getId());
                it.setLineNo(++lastLine);
                changed++;
            } else {
                it = items.get(r.id());
                if (!same(it, r) || r.qty() != nz(it.getQty())) changed++;
            }
            it.setCategory(r.category());
            it.setSizeLabel(r.size());
            it.setColour(r.colour());
            it.setStyleCode(r.styleCode());
            it.setMrp(r.mrp());
            it.setQty(r.qty());
            itemRepo.save(it);
            if (!hasBarcode.contains(it.getId())) {
                added.add(label(company, b.getId(), it.getId(), ++lastPiece, prefix));
                hasBarcode.add(it.getId());
            }
        }
        labelRepo.saveAll(added);

        if (changed > 0) {
            b.setTotalItems(items.size() + newRows);
            b.setTotalTags(total);
            touch(b, userId, userName);
            batchRepo.save(b);
        }
        return new Updated(b.getId(), b.getBatchNo(), total, newRows, changed);
    }

    /**
     * Edits one product of a batch from its barcode row (details and Qty). The barcode stays the same.
     */
    @Transactional
    public TagView editTag(String company, Long tagId, ItemIn in, Long userId, String userName) {
        TagLabel t = labelRepo.findByIdAndCompanyName(tagId, company)
                .orElseThrow(() -> new IllegalArgumentException("Tag not found."));
        TagBatch b = batchRepo.findLocked(t.getBatchId(), company)
                .orElseThrow(() -> new IllegalArgumentException("Tag batch not found."));
        TagStudioService.TagFieldSpec spec = tagStudioService.fieldSpec(b.getBusinessType());
        List<TagBatchItem> all = itemRepo.findByBatchIdOrderByLineNoAsc(b.getId());
        TagBatchItem it = all.stream().filter(x -> x.getId().equals(t.getItemId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tag batch not found."));
        Integer qty = in == null || in.qty() == null ? it.getQty() : in.qty();
        ItemIn base = in == null ? null : new ItemIn(null, in.category(), in.size(), in.colour(), in.styleCode(), in.mrp(), qty);
        ItemIn r = clean(base == null ? List.of() : List.of(base), spec, false).get(0);

        int total = r.qty();
        for (TagBatchItem x : all) if (!x.getId().equals(it.getId())) total += nz(x.getQty());
        if (total > MAX_TAGS_PER_BATCH) {
            throw new IllegalArgumentException("Up to " + MAX_TAGS_PER_BATCH + " tags in one batch.");
        }
        if (!same(it, r) || r.qty() != nz(it.getQty())) {
            it.setCategory(r.category());
            it.setSizeLabel(r.size());
            it.setColour(r.colour());
            it.setStyleCode(r.styleCode());
            it.setMrp(r.mrp());
            it.setQty(r.qty());
            itemRepo.save(it);
            b.setTotalTags(total);
            touch(b, userId, userName);
            batchRepo.save(b);
        }
        return new TagView(t.getId(), nz(t.getSeqNo()), t.getBarcode(), r.category(), r.size(), r.colour(), r.styleCode(),
                r.mrp(), r.qty());
    }

    private static void touch(TagBatch b, Long userId, String userName) {
        b.setUpdatedAt(LocalDateTime.now());
        b.setUpdatedByUserId(userId);
        b.setUpdatedByName(userName);
    }

    private static boolean same(TagBatchItem it, ItemIn r) {
        return java.util.Objects.equals(it.getCategory(), r.category())
                && java.util.Objects.equals(it.getSizeLabel(), r.size())
                && java.util.Objects.equals(it.getColour(), r.colour())
                && java.util.Objects.equals(it.getStyleCode(), r.styleCode())
                && (it.getMrp() == null ? r.mrp() == null : r.mrp() != null && it.getMrp().compareTo(r.mrp()) == 0);
    }

    /** checks and tidies the rows of the form (shared by create, edit and single-tag edit) */
    private static List<ItemIn> clean(List<ItemIn> in, TagStudioService.TagFieldSpec spec, boolean keepIds) {
        List<ItemIn> rows = in == null ? List.of() : in;
        if (rows.isEmpty()) throw new IllegalArgumentException("Add at least one product.");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("Too many products. Up to " + MAX_ROWS + " rows at a time.");
        List<ItemIn> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ItemIn r = rows.get(i);
            String where = rows.size() == 1 ? "" : "Row " + (i + 1) + ": ";
            // one standard way of writing, whatever the user typed (extra spaces are always removed):
            //   category, colour -> "Navy Blue"      size, style code -> "FREE SIZE", "MV-LS-204"
            String category = title(text(r == null ? null : r.category(), 60));
            String size = upper(text(r == null ? null : r.size(), 30));
            String colour = title(text(r == null ? null : r.colour(), 40));
            String style = upper(text(r == null ? null : r.styleCode(), 40));
            BigDecimal mrp = r == null ? null : r.mrp();
            int qty = r == null || r.qty() == null ? 0 : r.qty();
            if (category == null) throw new IllegalArgumentException(where + "Category is required.");
            if (spec.sizeRequired() && size == null) throw new IllegalArgumentException(where + spec.sizeLabel() + " is required.");
            if (qty < 1) throw new IllegalArgumentException(where + "Qty must be 1 or more.");
            if (qty > MAX_QTY_PER_ROW) throw new IllegalArgumentException(where + "Qty can be up to " + MAX_QTY_PER_ROW + ".");
            if (mrp != null) {
                if (mrp.signum() < 0) throw new IllegalArgumentException(where + "MRP cannot be negative.");
                if (mrp.compareTo(new BigDecimal("10000000")) > 0) throw new IllegalArgumentException(where + "MRP is too large.");
                mrp = mrp.setScale(2, java.math.RoundingMode.HALF_UP);
                if (mrp.signum() == 0) mrp = null;
            }
            out.add(new ItemIn(keepIds && r != null ? r.id() : null, category, size, colour, style, mrp, qty));
        }
        return out;
    }

    private static String barcodePrefix(TagBatch b) {
        return String.format(Locale.ROOT, "T%d%06d-", nz(b.getBatchYear()), nz(b.getSeqNo()));
    }

    private static TagLabel label(String company, Long batchId, Long itemId, int piece, String prefix) {
        TagLabel t = new TagLabel();
        t.setCompanyName(company);
        t.setBatchId(batchId);
        t.setItemId(itemId);
        t.setSeqNo(piece);
        t.setBarcode(prefix + String.format(Locale.ROOT, "%03d", piece));
        return t;
    }

    // ------------------------------------------------------------------ number

    private long nextSeq(String company, int year) {
        TagCounter counter = counterRepo.findLocked(company, year).orElse(null);
        if (counter == null) {
            TagCounter c = new TagCounter();
            c.setCompanyName(company);
            c.setTagYear(year);
            c.setLastNo(0L);
            counterRepo.saveAndFlush(c);
            counter = counterRepo.findLocked(company, year).orElseThrow();
        }
        counter.setLastNo(counter.getLastNo() + 1);
        counterRepo.save(counter);
        return counter.getLastNo();
    }

    // ------------------------------------------------------------------ list

    @Transactional(readOnly = true)
    public List<BatchRow> list(String company) {
        List<TagBatch> batches = batchRepo.findByCompanyNameOrderByIdDesc(company, PageRequest.of(0, LIST_LIMIT));
        if (batches.isEmpty()) return List.of();
        Map<Long, List<TagBatchItem>> items = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (TagBatch b : batches) ids.add(b.getId());
        for (TagBatchItem it : itemRepo.findByBatchIdInOrderByBatchIdAscLineNoAsc(ids)) {
            items.computeIfAbsent(it.getBatchId(), k -> new ArrayList<>()).add(it);
        }
        List<BatchRow> out = new ArrayList<>();
        for (TagBatch b : batches) {
            List<TagBatchItem> its = items.getOrDefault(b.getId(), List.of());
            out.add(new BatchRow(b.getId(), b.getBatchNo(), b.getCreatedAt(), b.getBusinessType(),
                    tagStudioService.label(b.getBusinessType()), summary(its), nz(b.getTotalItems()), nz(b.getTotalTags()),
                    b.getCreatedByName(), b.getUpdatedAt(), b.getUpdatedByName(), search(b, its)));
        }
        return out;
    }

    /** counts for the stat cards */
    public record Stats(int batches, int tags, int tagsToday, int tagsThisMonth) {}

    public Stats stats(List<BatchRow> rows) {
        int tags = 0, today = 0, month = 0;
        LocalDate now = LocalDate.now();
        for (BatchRow r : rows) {
            tags += r.tags();
            if (r.createdAt() == null) continue;
            LocalDate d = r.createdAt().toLocalDate();
            if (d.equals(now)) today += r.tags();
            if (d.getYear() == now.getYear() && d.getMonth() == now.getMonth()) month += r.tags();
        }
        return new Stats(rows.size(), tags, today, month);
    }

    // ------------------------------------------------------------------ one batch

    @Transactional(readOnly = true)
    public Optional<BatchDetail> detail(String company, Long id) {
        if (id == null) return Optional.empty();
        return batchRepo.findByIdAndCompanyName(id, company).map(b -> {
            // a product's barcode = its first tag row (batches made before "one barcode per product" may have more)
            Map<Long, TagLabel> barcodeOf = new HashMap<>();
            for (TagLabel t : labelRepo.findByBatchIdAndCompanyNameOrderBySeqNoAsc(b.getId(), company)) {
                barcodeOf.putIfAbsent(t.getItemId(), t);
            }
            List<TagView> tags = new ArrayList<>();
            List<ItemView> rows = new ArrayList<>();
            for (TagBatchItem it : itemRepo.findByBatchIdOrderByLineNoAsc(b.getId())) {
                TagLabel t = barcodeOf.get(it.getId());
                if (t == null) continue;
                int qty = Math.max(1, nz(it.getQty()));
                tags.add(new TagView(t.getId(), nz(t.getSeqNo()), t.getBarcode(), it.getCategory(), it.getSizeLabel(),
                        it.getColour(), it.getStyleCode(), it.getMrp(), qty));
                rows.add(new ItemView(it.getId(), nz(it.getLineNo()), t.getBarcode(), it.getCategory(), it.getSizeLabel(),
                        it.getColour(), it.getStyleCode(), it.getMrp(), qty));
            }
            tags.sort(java.util.Comparator.comparingInt(TagView::seqNo));
            return new BatchDetail(b, tagStudioService.label(b.getBusinessType()),
                    tagStudioService.fieldSpec(b.getBusinessType()), rows, tags);
        });
    }

    /**
     * What goes into the PDF: every chosen product, repeated as many times as its copies.
     * tagIds empty = every product of the batch. copies[i] belongs to tagIds[i]; a missing or bad number = the
     * product's Qty. Ids that do not belong to the batch are ignored.
     */
    public List<TagView> pick(BatchDetail d, List<Long> tagIds, List<Integer> copies) {
        Map<Long, Integer> want = new LinkedHashMap<>();
        if (tagIds == null || tagIds.isEmpty()) {
            for (TagView t : d.tags()) want.put(t.id(), t.qty());
        } else {
            for (int i = 0; i < tagIds.size(); i++) {
                Integer c = copies != null && i < copies.size() ? copies.get(i) : null;
                want.put(tagIds.get(i), c);
            }
        }
        List<TagView> out = new ArrayList<>();
        int total = 0;
        for (TagView t : d.tags()) {
            if (!want.containsKey(t.id())) continue;
            Integer c = want.get(t.id());
            int n = c == null || c < 1 ? t.qty() : Math.min(c, MAX_QTY_PER_ROW);
            for (int k = 0; k < n && total < MAX_TAGS_PER_BATCH; k++, total++) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** "Shirt (5), Jeans (4)" in the order the rows were entered */
    private static String summary(List<TagBatchItem> its) {
        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (TagBatchItem it : its) byCat.merge(it.getCategory(), nz(it.getQty()), Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byCat.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(')');
        }
        return sb.toString();
    }

    private static String search(TagBatch b, List<TagBatchItem> its) {
        StringBuilder sb = new StringBuilder(nzs(b.getBatchNo())).append(' ').append(nzs(b.getCreatedByName()))
                .append(' ').append(b.getBatchNo().replace("TAG-", "T").replace("-", ""));
        for (TagBatchItem it : its) {
            sb.append(' ').append(nzs(it.getCategory())).append(' ').append(nzs(it.getSizeLabel()))
              .append(' ').append(nzs(it.getColour())).append(' ').append(nzs(it.getStyleCode()));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** "  navy   BLUE " -> "Navy Blue", "off-white" -> "Off-White", "t-shirt" -> "T-Shirt" */
    static String title(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(start && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '/' || c == '(' || c == '&' || c == '.';
        }
        return sb.toString();
    }

    static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }

    /** trims, turns any run of spaces / tabs / new lines into one space, cuts to the column size */
    private static String text(String s, int max) {
        if (s == null) return null;
        String t = s.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private static String nzs(String s) { return s == null ? "" : s; }
}