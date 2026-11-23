package com.stockmanagement.service;

import com.stockmanagement.entity.CompanyBusinessType;
import com.stockmanagement.repository.CompanyBusinessTypeRepository;
import com.stockmanagement.repository.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tag Studio, step 1: which business types a shop has (clothing, jewellery ...).
 *
 *  - Only Super Admin sets them (Settings > Shop Features, inside the Tag Studio row).
 *  - A shop can have one OR many types (e.g. Clothing + Footwear). It gets only the types that were saved.
 *  - The shop sees Tag Studio in its Retail menu only when the TAG_STUDIO feature is ON
 *    AND at least one business type is saved (see isReady).
 *  - Company always comes from the caller; the shop can never choose or change its own types.
 */
@Service
public class TagStudioService {

    /** code + label + short help shown under the checkbox + the group it is listed under. */
    public record BusinessType(String code, String label, String help, String group) {}

    private static final Map<String, BusinessType> BUSINESS_TYPES = new LinkedHashMap<>();
    static {
        // Fashion
        add(new BusinessType("CLOTHING", "Clothing & Apparel", "Size, colour, style code, MRP", "Fashion"));
        add(new BusinessType("FOOTWEAR", "Footwear", "UK size, colour, article no, MRP", "Fashion"));
        // Jewellery (two separate kinds: weight-based gold/silver, and price-based imitation)
        add(new BusinessType("JEWELLERY", "Gold & Silver Jewellery", "Gross / net weight, purity, HUID, making charge", "Jewellery"));
        add(new BusinessType("IMITATION", "Imitation / Fashion Jewellery", "Design code, colour, MRP", "Jewellery"));
        // Electronics
        add(new BusinessType("ELECTRONICS", "Mobile & Electronics", "Model, IMEI / serial no, warranty", "Electronics"));
        // Daily needs
        add(new BusinessType("GROCERY", "Grocery & FMCG", "Net qty, batch, MFG / EXP date", "Daily needs"));
        add(new BusinessType("PHARMACY", "Medical / Pharmacy", "Batch, expiry date, MRP", "Daily needs"));
        add(new BusinessType("COSMETICS", "Cosmetics & Beauty", "Shade, net qty, batch, expiry", "Daily needs"));
        // Others
        add(new BusinessType("STATIONERY", "Books & Stationery", "Item code, brand, MRP", "Others"));
        add(new BusinessType("TOYS", "Toys & Gifts", "Age group, item code, MRP", "Others"));
        add(new BusinessType("OPTICAL", "Optical", "Frame code, size, colour", "Others"));
        add(new BusinessType("FURNITURE", "Furniture & Home", "Model, material, size", "Others"));
        add(new BusinessType("HARDWARE", "Hardware & Paints", "Item code, size / spec, unit", "Others"));
        add(new BusinessType("GENERAL", "General Store", "Item code, brand, unit", "Others"));
    }

    private static void add(BusinessType t) { BUSINESS_TYPES.put(t.code(), t); }

    /**
     * What the "Create tags" form asks for, per business type. Every type uses the same columns
     * (category, size, colour, style, MRP, qty); only their names and suggestions change.
     * Category and size are free text with suggestions, so a shop can type its own values too.
     */
    public record TagFieldSpec(String sizeLabel, String colourLabel, String styleLabel, boolean sizeRequired,
                               List<String> categories, List<String> sizes, List<String> colours) {}

    /**
     * One size list for every clothing shop, smallest to biggest:
     * letter sizes XXS ... 7XL, then number sizes 18 ... 48 (waist / chest / shirt), then FREE SIZE,
     * then kids sizes by age. A shop can still type its own size; it is saved in capital letters.
     */
    private static final String CLOTHING_SIZES = "XXS, XS, S, M, L, XL, XXL, 3XL, 4XL, 5XL, 6XL, 7XL, "
            + range(18, 48) + ", FREE SIZE, "
            + "0-3M, 3-6M, 6-12M, 1-2Y, 2-3Y, 3-4Y, 4-5Y, 5-6Y, 6-7Y, 7-8Y, 8-9Y, 9-10Y, 10-11Y, 11-12Y, 12-13Y, 13-14Y, 14-15Y, 15-16Y";

    /** "18, 19, 20 ... 48" */
    private static String range(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(i);
        }
        return sb.toString();
    }

    /** standard colour names (saved as typed here: first letter of every word capital) */
    private static final String COLOURS = "Black, White, Off White, Cream, Beige, Grey, Charcoal, Silver, Navy Blue, Blue, "
            + "Sky Blue, Royal Blue, Teal, Turquoise, Green, Olive, Bottle Green, Mint, Yellow, Mustard, Orange, Peach, "
            + "Red, Maroon, Wine, Pink, Baby Pink, Magenta, Purple, Lavender, Brown, Coffee, Tan, Khaki, Gold, Multicolour";
    private static final String BASIC_COLOURS = "Black, White, Grey, Silver, Blue, Navy Blue, Green, Yellow, Orange, Red, "
            + "Pink, Purple, Brown, Gold, Transparent, Multicolour";

    private static final Map<String, TagFieldSpec> FIELD_SPECS = new LinkedHashMap<>();
    static {
        spec("CLOTHING", "Size", "Colour", "Style code", true,
                "Shirt, T-Shirt, Jeans, Trouser, Kurta, Jacket, Sweatshirt, Shorts", CLOTHING_SIZES, COLOURS);
        spec("FOOTWEAR", "UK size", "Colour", "Article no", true,
                "Sports shoes, Formal shoes, Casual shoes, Sandals, Slippers, Boots", range(1, 13), COLOURS);
        spec("JEWELLERY", "Purity", "Metal", "Design code", true,
                "Ring, Chain, Necklace, Earrings, Bangle, Bracelet, Pendant", "24K, 22K, 18K, 14K, 925 Silver", "Gold, Yellow Gold, White Gold, Rose Gold, Silver, Platinum");
        spec("IMITATION", "Size", "Colour", "Design code", false,
                "Earrings, Necklace set, Bangles, Ring, Bracelet, Hair accessory", "Free size, 2.4, 2.6, 2.8", "Gold, Rose Gold, Silver, Oxidised, Kundan, Pearl White, " + BASIC_COLOURS);
        spec("ELECTRONICS", "Model", "Colour", "IMEI / Serial no", true,
                "Mobile, Charger, Earphones, Cover, Power bank, Cable, Speaker", "", BASIC_COLOURS);
        spec("GROCERY", "Net qty", "Brand", "Batch no", false,
                "Rice, Atta, Dal, Oil, Spices, Snacks, Beverages", "100 g, 250 g, 500 g, 1 kg, 5 kg, 500 ml, 1 L", "");
        spec("PHARMACY", "Pack", "Brand", "Batch no", false,
                "Tablet, Capsule, Syrup, Ointment, Device", "10 tablets, 15 tablets, 60 ml, 100 ml", "");
        spec("COSMETICS", "Net qty", "Shade", "Batch no", false,
                "Lipstick, Cream, Shampoo, Perfume, Nail polish, Face wash", "5 g, 50 ml, 100 ml, 200 ml", "");
        spec("STATIONERY", "Size", "Colour", "Item code", false,
                "Notebook, Pen, Book, File, Art supply, Bag", "A4, A5, Pocket", BASIC_COLOURS);
        spec("TOYS", "Age", "Colour", "Item code", false,
                "Soft toy, Car, Puzzle, Doll, Board game, Educational", "0-2 yrs, 3-5 yrs, 6+ yrs, 10+ yrs", BASIC_COLOURS);
        spec("OPTICAL", "Size", "Colour", "Frame code", false,
                "Frame, Sunglasses, Lens, Case", "Small, Medium, Large", BASIC_COLOURS);
        spec("FURNITURE", "Size", "Colour / finish", "Model", false,
                "Chair, Table, Bed, Sofa, Shelf, Wardrobe", "", "Walnut, Teak, Oak, Wenge, Rosewood, White, Black, Brown, Grey");
        spec("HARDWARE", "Size / spec", "Colour", "Item code", false,
                "Paint, Tool, Pipe, Fitting, Electrical, Adhesive", "1 L, 4 L, 10 L, 1/2 inch, 1 inch", BASIC_COLOURS);
        spec("GENERAL", "Size / pack", "Brand", "Item code", false,
                "Item", "", "");
    }

    private static void spec(String code, String sizeLabel, String colourLabel, String styleLabel, boolean sizeRequired,
                             String categories, String sizes, String colours) {
        List<String> sizeList = new ArrayList<>();
        for (String v : split(sizes)) sizeList.add(v.toUpperCase(java.util.Locale.ROOT));
        FIELD_SPECS.put(code, new TagFieldSpec(sizeLabel, colourLabel, styleLabel, sizeRequired, split(categories),
                List.copyOf(sizeList), split(colours)));
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) if (!p.isBlank()) out.add(p.trim());
        return List.copyOf(out);
    }

    /** Form fields for one business type (falls back to the General Store fields). */
    public TagFieldSpec fieldSpec(String typeCode) {
        TagFieldSpec f = typeCode == null ? null : FIELD_SPECS.get(typeCode);
        return f != null ? f : FIELD_SPECS.get("GENERAL");
    }

    private final CompanyBusinessTypeRepository repo;
    private final CompanyRepository companyRepo;
    private final CompanyFeatureService featureService;

    public TagStudioService(CompanyBusinessTypeRepository repo, CompanyRepository companyRepo,
                            CompanyFeatureService featureService) {
        this.repo = repo;
        this.companyRepo = companyRepo;
        this.featureService = featureService;
    }

    public List<BusinessType> businessTypes() {
        return List.copyOf(BUSINESS_TYPES.values());
    }

    /** Business types grouped for the Shop Features screen: "Fashion" -> [Clothing, Footwear] ... (catalog order). */
    public Map<String, List<BusinessType>> businessTypeGroups() {
        Map<String, List<BusinessType>> groups = new LinkedHashMap<>();
        for (BusinessType t : BUSINESS_TYPES.values()) {
            groups.computeIfAbsent(t.group(), g -> new ArrayList<>()).add(t);
        }
        return groups;
    }

    /** Type codes saved for the company, in catalog order (empty when none). */
    @Transactional(readOnly = true)
    public List<String> businessTypes(String companyName) {
        if (companyName == null || companyName.isBlank()) return List.of();
        Set<String> saved = new LinkedHashSet<>();
        for (CompanyBusinessType row : repo.findByCompanyNameOrderByIdAsc(companyName.trim())) {
            saved.add(row.getBusinessType());
        }
        List<String> ordered = new ArrayList<>();
        for (String code : BUSINESS_TYPES.keySet()) {
            if (saved.contains(code)) ordered.add(code);
        }
        return ordered;
    }

    /** Display labels of the company's types, e.g. ["Clothing & Apparel", "Footwear"]. */
    @Transactional(readOnly = true)
    public List<String> businessTypeLabels(String companyName) {
        List<String> labels = new ArrayList<>();
        for (String code : businessTypes(companyName)) labels.add(label(code));
        return labels;
    }

    /** At least one business type is saved for the company. */
    @Transactional(readOnly = true)
    public boolean hasBusinessType(String companyName) {
        return companyName != null && !companyName.isBlank() && repo.existsByCompanyName(companyName.trim());
    }

    /** Tag Studio is usable by the shop: feature ON and at least one business type saved. */
    @Transactional(readOnly = true)
    public boolean isReady(String companyName) {
        return featureService.isEnabled(companyName, CompanyFeatureService.TAG_STUDIO)
                && hasBusinessType(companyName);
    }

    /**
     * Super Admin saves the business types of a company (replaces the old set). Tag Studio must already be ON
     * and at least one type must be ticked. Returns the previous type codes, for the audit log.
     */
    @Transactional
    public List<String> setBusinessTypes(String companyName, Collection<String> typeCodes, Long userId, String userName) {
        Set<String> wanted = new LinkedHashSet<>();
        if (typeCodes != null) {
            for (String c : typeCodes) {
                String code = c == null ? "" : c.trim().toUpperCase();
                if (code.isEmpty()) continue;
                if (!BUSINESS_TYPES.containsKey(code)) {
                    throw new IllegalArgumentException("Unknown business type: " + c);
                }
                wanted.add(code);
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Please tick at least one business type.");
        }
        String canonical = companyRepo.findFirstByCompanyNameIgnoreCase(companyName == null ? "" : companyName.trim())
                .map(c -> c.getCompanyName())
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));
        if (!featureService.isEnabled(canonical, CompanyFeatureService.TAG_STUDIO)) {
            throw new IllegalArgumentException("Turn ON Tag Studio first.");
        }

        List<CompanyBusinessType> existing = repo.findByCompanyNameOrderByIdAsc(canonical);
        List<String> previous = businessTypes(canonical);
        Set<String> kept = new LinkedHashSet<>();
        for (CompanyBusinessType row : existing) {
            if (wanted.contains(row.getBusinessType()) && kept.add(row.getBusinessType())) continue;
            repo.delete(row);                       // unticked (or a duplicate row): remove
        }
        for (String code : wanted) {
            if (kept.contains(code)) continue;
            CompanyBusinessType row = new CompanyBusinessType();
            row.setCompanyName(canonical);
            row.setBusinessType(code);
            row.setCreatedByUserId(userId);
            row.setCreatedByName(userName);
            repo.save(row);
        }
        return previous;
    }

    public String label(String code) {
        BusinessType t = code == null ? null : BUSINESS_TYPES.get(code);
        return t == null ? code : t.label();
    }

    /** "Clothing & Apparel, Footwear" for messages and the audit log. */
    public String labels(Collection<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes != null) for (String c : codes) out.add(label(c == null ? null : c.trim().toUpperCase()));
        return out.isEmpty() ? "none" : String.join(", ", out);
    }
}