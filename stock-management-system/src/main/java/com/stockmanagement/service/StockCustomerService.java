package com.stockmanagement.service;

import com.stockmanagement.entity.StockCustomer;
import com.stockmanagement.repository.StockCustomerRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Customer master. Request banate waqt customer ko dhundta hai (naam + mobile se), nahi mile to naya banata hai,
 * mile to uski details taaza kar deta hai.
 */
@Service
public class StockCustomerService {

    private static final Pattern MOBILE = Pattern.compile("^\\+?[0-9]{10,15}$");
    private static final Pattern GST = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern PINCODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StockCustomerRepository repo;

    public StockCustomerService(StockCustomerRepository repo) {
        this.repo = repo;
    }

    /** Screen se aaya hua customer ka data. customerId tab bharta hai jab suggestion se chuna gaya ho. */
    public record CustomerInput(Long customerId, String name, String mobile, String company, String gst, String email,
                                String addressLine, String city, String state, String pincode) {}

    /** Suggestion list ki ek line. */
    public record Suggestion(Long id, String name, String mobile, String company, String gst, String email,
                             String addressLine, String city, String state, String pincode) {}

    @Transactional(readOnly = true)
    public List<Suggestion> search(String companyName, String query) {
        String q = query == null ? "" : query.trim();
        if (companyName == null || q.length() < 2) return List.of();
        return repo.search(companyName, q, PageRequest.of(0, 8)).stream()
                .map(c -> new Suggestion(c.getId(), c.getCustomerName(), c.getMobile(),
                        c.getBusinessName(), c.getGstNumber(), c.getEmail(),
                        c.getAddressLine(), c.getCity(), c.getState(), c.getPincode()))
                .collect(Collectors.toList());
    }

    @Transactional
    public StockCustomer resolve(String companyName, CustomerInput in, String actorName) {
        if (in == null) throw new IllegalArgumentException("Customer details are required.");

        String name = clean(in.name());
        if (name.isEmpty()) throw new IllegalArgumentException("Customer name is required.");
        if (name.length() > 150) throw new IllegalArgumentException("Customer name is too long.");

        String mobile = normalizeMobile(in.mobile());
        if (mobile.isEmpty()) throw new IllegalArgumentException("Customer mobile number is required.");
        if (!MOBILE.matcher(mobile).matches()) {
            throw new IllegalArgumentException("Enter a valid mobile number (10 to 15 digits).");
        }

        String company = clean(in.company());
        if (company.length() > 200) throw new IllegalArgumentException("Company name is too long.");

        String gst = clean(in.gst()).toUpperCase();
        if (!gst.isEmpty() && !GST.matcher(gst).matches()) {
            throw new IllegalArgumentException("GST number looks invalid (15 characters, e.g. 27ABCDE1234F1Z5).");
        }

        String email = clean(in.email());
        if (!email.isEmpty() && (email.length() > 150 || !EMAIL.matcher(email).matches())) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }

        String address = clean(in.addressLine());
        if (address.isEmpty()) throw new IllegalArgumentException("Customer address is required.");
        if (address.length() > 300) throw new IllegalArgumentException("Address is too long (max 300 characters).");

        String city = clean(in.city());
        if (city.isEmpty()) throw new IllegalArgumentException("City is required.");
        if (city.length() > 100) throw new IllegalArgumentException("City name is too long.");

        String state = clean(in.state());
        if (state.isEmpty()) throw new IllegalArgumentException("State is required.");
        if (state.length() > 100) throw new IllegalArgumentException("State name is too long.");

        String pincode = clean(in.pincode());
        if (!pincode.isEmpty() && !PINCODE.matcher(pincode).matches()) {
            throw new IllegalArgumentException("Pincode must be 6 digits.");
        }

        // 1) suggestion se chuna gaya customer, 2) warna wahi naam + wahi mobile wala purana customer, 3) warna naya
        StockCustomer c = null;
        if (in.customerId() != null) {
            c = repo.findById(in.customerId())
                    .filter(x -> companyName.equals(x.getCompanyName()))
                    .orElse(null);
        }
        Optional<StockCustomer> sameKey = repo.findFirstByCompanyNameAndMobileAndCustomerNameIgnoreCase(companyName, mobile, name);
        if (c == null && sameKey.isPresent()) {
            c = sameKey.get();
        }
        if (c != null && sameKey.isPresent() && !sameKey.get().getId().equals(c.getId())) {
            throw new IllegalArgumentException("A customer with this name and mobile number already exists.");
        }
        if (c == null) {
            c = new StockCustomer();
            c.setCompanyName(companyName);
            c.setCreatedBy(actorName);
        }

        c.setCustomerName(name);
        c.setMobile(mobile);
        c.setBusinessName(company.isEmpty() ? null : company);
        c.setGstNumber(gst.isEmpty() ? null : gst);
        c.setEmail(email.isEmpty() ? null : email);
        c.setAddressLine(address);
        c.setCity(city);
        c.setState(state);
        c.setPincode(pincode.isEmpty() ? null : pincode);
        return repo.save(c);
    }

    private String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** Space, dash, bracket hata deta hai; shuru ka + rehne deta hai. */
    private String normalizeMobile(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-()]", "");
    }
}