package com.stockmanagement.service;

import com.stockmanagement.entity.Company;
import com.stockmanagement.repository.CompanyRepository;
import com.stockmanagement.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Companies ka kaam (Settings > User ka Company dropdown).
 *
 *  - activeNames(): dropdown ki list. Table khali ho to pehli baar maujooda users ki companies (Satcop, Bellbuny...)
 *    apne aap bhar deta hai, isliye alag se data daalne ki zarurat nahi.
 *  - resolveForUser(): SUPER_ADMIN ne dropdown me jo chuna uska sahi naam deta hai.
 *      "__NEW__" + naya naam  -> company bana deta hai (wahi naam pehle se ho to wahi use hota hai)
 *      koi aur naam           -> table me hona chahiye, warna error
 *  Normal admin ke liye ye use hi nahi hota: unka user hamesha unki apni company me banta hai (UserController).
 */
@Service
public class CompanyService {

    public static final String NEW_OPTION = "__NEW__";
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 &.,'()/-]{1,149}$");

    private final CompanyRepository repo;
    private final UserRepository userRepo;

    public CompanyService(CompanyRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    /** Chalu companies ke naam A-Z. Pehli baar table khali ho to users ki companies se bhar deta hai. */
    @Transactional
    public List<String> activeNames() {
        if (repo.count() == 0) {
            for (String name : userRepo.findDistinctCompanyNames()) {
                String n = clean(name);
                if (n.isEmpty() || repo.findFirstByCompanyNameIgnoreCase(n).isPresent()) continue;
                Company c = new Company();
                c.setCompanyName(n);
                c.setCreatedBy("system (from existing users)");
                repo.save(c);
            }
        }
        List<String> out = new ArrayList<>();
        for (Company c : repo.findByStatusOrderByCompanyNameAsc("ACTIVE")) out.add(c.getCompanyName());
        return out;
    }

    /**
     * SUPER_ADMIN ka chuna hua naam -> sahi company naam (jo save hoga).
     * chosen = dropdown value; newName = "+ New company…" chunne par likha naam.
     */
    @Transactional
    public String resolveForUser(String chosen, String newName, String actor) {
        String pick = chosen == null ? "" : chosen.trim();
        if (pick.isEmpty()) throw new IllegalArgumentException("Company name is required.");

        if (NEW_OPTION.equals(pick)) {
            String n = clean(newName);
            if (n.isEmpty()) throw new IllegalArgumentException("New company name is required.");
            if (!ALLOWED.matcher(n).matches()) {
                throw new IllegalArgumentException("Company name must be 2 to 150 characters (letters, numbers, space and & . , ' ( ) / -).");
            }
            Company existing = repo.findFirstByCompanyNameIgnoreCase(n).orElse(null);
            if (existing != null) {
                if (!"ACTIVE".equalsIgnoreCase(existing.getStatus())) {
                    throw new IllegalArgumentException("Company \"" + existing.getCompanyName() + "\" exists but is not active.");
                }
                return existing.getCompanyName();   // pehle se hai: wahi use karo, duplicate nahi
            }
            Company c = new Company();
            c.setCompanyName(n);
            c.setCreatedBy(actor);
            return repo.save(c).getCompanyName();
        }

        Company c = repo.findFirstByCompanyNameIgnoreCase(clean(pick)).orElse(null);
        if (c == null || !"ACTIVE".equalsIgnoreCase(c.getStatus())) {
            throw new IllegalArgumentException("Please choose a valid company.");
        }
        return c.getCompanyName();
    }

    /** Aage-peeche ki space hatao, beech ki zyada space ek karo. */
    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}