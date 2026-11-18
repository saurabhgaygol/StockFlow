package com.stockmanagement.service;

import com.stockmanagement.entity.RetailShopLogo;
import com.stockmanagement.repository.RetailShopLogoRepository;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shop logo for the printed bills. PNG or JPEG, up to 500 KB. */
@Service
public class RetailLogoService {

    public static final int MAX_BYTES = 500 * 1024;

    private final RetailShopLogoRepository repo;

    public RetailLogoService(RetailShopLogoRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public boolean has(String company) {
        return repo.existsByCompanyName(company);
    }

    @Transactional(readOnly = true)
    public Optional<RetailShopLogo> get(String company) {
        return repo.findByCompanyName(company);
    }

    @Transactional
    public void save(String company, byte[] data) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Choose a logo image first.");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Logo is too big. Please use an image under 500 KB.");
        String type = detect(data);
        if (type == null) throw new IllegalArgumentException("Logo must be a PNG or JPG image.");
        RetailShopLogo l = repo.findByCompanyName(company).orElseGet(RetailShopLogo::new);
        l.setCompanyName(company);
        l.setContentType(type);
        l.setImageData(data);
        repo.save(l);
    }

    @Transactional
    public void remove(String company) {
        repo.deleteByCompanyName(company);
    }

    /** the file's own first bytes decide the type, not the name the browser sent */
    private static String detect(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }
}