package com.stockmanagement.controller;

import com.stockmanagement.service.RetailBillingService;
import com.stockmanagement.service.RetailLogoService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The bill link that is sent to the customer (WhatsApp / SMS / e-mail): /b/{token}.
 * No login. The token is a long random secret, so only the person who got the link can open the bill.
 */
@Controller
public class PublicBillController {

    private final RetailBillingService billing;
    private final RetailLogoService logoService;

    public PublicBillController(RetailBillingService billing, RetailLogoService logoService) {
        this.billing = billing;
        this.logoService = logoService;
    }

    @GetMapping("/b/{token}")
    public String view(@PathVariable("token") String token,
                       @RequestParam(value = "size", defaultValue = "80") String size, Model model) {
        RetailBillingService.BillDetail d;
        try {
            d = billing.detailByToken(token);
        } catch (IllegalArgumentException e) {
            return "retail/bill-public-missing";
        }
        model.addAttribute("d", d);
        model.addAttribute("bill", d.bill());
        model.addAttribute("lines", d.lines());
        model.addAttribute("payments", d.payments());
        model.addAttribute("rates", d.rateRows());
        model.addAttribute("profile", d.profile());
        model.addAttribute("pointsBalance", d.pointsBalance());
        model.addAttribute("hasLogo", logoService.has(d.bill().getCompanyName()));
        model.addAttribute("saved", d.bill().getItemDiscount().add(d.bill().getManualDiscount()).add(d.bill().getBillDiscount()));
        model.addAttribute("publicView", true);
        model.addAttribute("token", token);
        model.addAttribute("logoUrl", "/b/" + token + "/logo");
        return "a4".equals(size) ? "retail/bill-print-a4" : "retail/bill-print-80";
    }

    @GetMapping("/b/{token}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable("token") String token) {
        try {
            String company = billing.detailByToken(token).bill().getCompanyName();
            return logoService.get(company)
                    .map(l -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(l.getContentType()))
                            .cacheControl(CacheControl.noCache())
                            .body(l.getImageData()))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}