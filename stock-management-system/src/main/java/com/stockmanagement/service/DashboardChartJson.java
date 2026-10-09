package com.stockmanagement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard ke graphs ke liye JSON (dashboardcharts.js ke data-chart attribute me jaata hai).
 * Template me use: ${@dashCharts.trend(dash)}
 * DashboardService / Controller ko chhua nahi - sirf DashboardView padhta hai.
 */
@Component("dashCharts")
public class DashboardChartJson {

    private final ObjectMapper mapper;

    public DashboardChartJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /* Inward vs Issued - smooth area chart */
    public String trend(DashboardService.DashboardView d) {
        List<String> labels = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Long> inward = new ArrayList<>();
        List<Long> issued = new ArrayList<>();
        for (DashboardService.Bucket b : d.trend()) {
            labels.add(b.label());
            titles.add(b.title());
            inward.add(b.inward());
            issued.add(b.issued());
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "area");
        cfg.put("labels", labels);
        cfg.put("titles", titles);
        List<Object> series = new ArrayList<>();
        series.add(series("Inwarded", "#8b5cf6", inward));
        series.add(series("Issued", "#34a56a", issued));
        cfg.put("series", series);
        return json(cfg);
    }

    /* Category-wise stock - stacked columns (top 8 categories) */
    public String category(DashboardService.DashboardView d) {
        List<String> labels = new ArrayList<>();
        List<Long> available = new ArrayList<>();
        List<Long> issued = new ArrayList<>();
        List<Long> damaged = new ArrayList<>();
        int i = 0;
        for (DashboardService.CategoryRow c : d.categories()) {
            if (i++ >= 8) break;
            labels.add(c.name());
            available.add(c.available());
            issued.add(c.issued());
            damaged.add(c.damaged());
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "stack");
        cfg.put("labels", labels);
        List<Object> series = new ArrayList<>();
        series.add(series("Available", "#1c7a43", available));
        series.add(series("Issued", "#6c3ce0", issued));
        series.add(series("Damaged", "#c0392b", damaged));
        cfg.put("series", series);
        return json(cfg);
    }

    /* Gauge 1: kitna stock available hai (total ke hisaab se) */
    public String gaugeStock(DashboardService.DashboardView d) {
        long total = d.totalUnits();
        long avail = 0;
        for (DashboardService.StatusSlice s : d.slices()) {
            if ("Available".equals(s.label())) avail = s.count();
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "gauge");
        cfg.put("value", total == 0 ? -1 : Math.round(avail * 100.0 / total));
        cfg.put("label", "In stock");
        cfg.put("color", "#1c7a43");
        cfg.put("sub", avail + " of " + total + " units available");
        return json(cfg);
    }

    /* Gauge 2: raised requests me se kitni issue hui */
    public String gaugeRequests(DashboardService.DashboardView d) {
        long raised = 0, issued = 0;
        for (DashboardService.FunnelRow f : d.funnel()) {
            if ("Raised".equals(f.label())) raised = f.count();
            if ("issued".equals(f.css())) issued = f.count();
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "gauge");
        cfg.put("value", raised == 0 ? -1 : Math.round(issued * 100.0 / raised));
        cfg.put("label", "Issued");
        cfg.put("color", "#6c3ce0");
        cfg.put("sub", issued + " of " + raised + " requests issued");
        return json(cfg);
    }

    /* ---------------- helpers ---------------- */
    private Map<String, Object> series(String name, String color, List<Long> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("color", color);
        m.put("data", data);
        return m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }
}