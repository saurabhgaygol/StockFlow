package com.stockmanagement.aiagent;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * AI ke liye alag DB connection. Production me ai.readonly.datasource.* set karo jo sirf SELECT-only
 * MySQL user (sql/ai_readonly_user.sql) ka ho: tab code me galti bhi ho to DB delete/update hone hi nahi dega.
 * Set na ho to main DataSource par fallback (tab bhi connection read-only mode me chalta hai).
 * (DataSource bean nahi banaya taaki Spring Boot ka main DataSource disturb na ho.)
 */
@Component
public class AiReadOnlyDataSourceConfig {

    private final DataSource ds;
    private final HikariDataSource own;

    public AiReadOnlyDataSourceConfig(
            @Value("${ai.readonly.datasource.url:}") String url,
            @Value("${ai.readonly.datasource.username:}") String user,
            @Value("${ai.readonly.datasource.password:}") String pass,
            DataSource primary) {
        if (url == null || url.isBlank()) {
            this.own = null;
            this.ds = primary;
        } else {
            HikariConfig c = new HikariConfig();
            c.setJdbcUrl(url);
            c.setUsername(user);
            c.setPassword(pass);
            c.setReadOnly(true);
            c.setMaximumPoolSize(4);
            c.setConnectionTimeout(5000);
            c.setPoolName("ai-readonly");
            this.own = new HikariDataSource(c);
            this.ds = own;
        }
    }

    public DataSource get() { return ds; }

    @PreDestroy
    void close() { if (own != null) own.close(); }
}