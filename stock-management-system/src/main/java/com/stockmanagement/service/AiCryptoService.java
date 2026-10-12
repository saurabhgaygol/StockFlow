package com.stockmanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API keys ko DB me AES-256-GCM se encrypt karke rakhta hai.
 *
 * Master key ka order:
 *   1) APP_AI_MASTER_KEY (env) / app.ai.master-key  -> agar set hai to wahi
 *   2) Key file (default: <user home>/.stockflow/ai-master.key) -> nahi hai to pehli baar khud ban jaati hai
 *
 * IMPORTANT: key file ka backup rakho. Ye file kho gayi to saved API keys dobara daalni padengi.
 * Format: "v1:" + base64(iv[12] + ciphertext+tag)
 */
@Service
public class AiCryptoService {

    private static final Logger log = LoggerFactory.getLogger(AiCryptoService.class);
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public AiCryptoService(@Value("${app.ai.master-key:}") String masterKeyBase64,
                           @Value("${app.ai.key-file:}") String keyFile) {
        SecretKeySpec k = null;
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            k = fromBase64(masterKeyBase64, "app.ai.master-key");
        } else {
            k = fromFile(keyFile);
        }
        this.key = k;
    }

    private static SecretKeySpec fromBase64(String b64, String source) {
        try {
            byte[] raw = Base64.getDecoder().decode(b64.trim());
            if (raw.length == 32) {
                return new SecretKeySpec(raw, "AES");
            }
            log.warn("{} must be 32 bytes (base64). AI connections stay disabled.", source);
        } catch (IllegalArgumentException e) {
            log.warn("{} is not valid base64. AI connections stay disabled.", source);
        }
        return null;
    }

    /** Key file se padho; file nahi hai to nayi key banakar likh do. */
    private static SecretKeySpec fromFile(String configured) {
        try {
            Path p = (configured == null || configured.isBlank())
                    ? Paths.get(System.getProperty("user.home"), ".stockflow", "ai-master.key")
                    : Paths.get(configured.trim());
            if (Files.exists(p)) {
                SecretKeySpec k = fromBase64(Files.readString(p, StandardCharsets.UTF_8), "AI key file " + p);
                if (k != null) {
                    log.info("AI master key loaded from {}", p);
                }
                return k;
            }
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            try {
                p.toFile().setReadable(false, false);
                p.toFile().setReadable(true, true);
                p.toFile().setWritable(false, false);
                p.toFile().setWritable(true, true);
            } catch (Exception ignore) {
                // permission tightening is best-effort (e.g. Windows)
            }
            log.warn("AI master key created at {}. BACK UP THIS FILE - if it is lost, saved API keys cannot be read.", p);
            return new SecretKeySpec(raw, "AES");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read or create the AI master key file ({}). AI connections stay disabled.", e.getMessage());
            return null;
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the API key.");
        }
    }

    public String decrypt(String stored) {
        requireKey();
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalStateException("Unknown key format.");
            }
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the saved API key (master key changed?). Please re-enter it.");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Server encryption key is not configured (app.ai.master-key). Please contact IT.");
        }
    }
}