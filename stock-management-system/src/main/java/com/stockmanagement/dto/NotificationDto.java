package com.stockmanagement.dto;

public class NotificationDto {

    private Long id;
    private String title;
    private String message;
    private String time;   // display-friendly, e.g. "24 Sep, 03:10 PM"
    private String url;
    private boolean read;

    public NotificationDto() {}

    public NotificationDto(Long id, String title, String message, String time, String url, boolean read) {
        this.id = id;
        this.title = title;
        this.message = message;
        this.time = time;
        this.url = url;
        this.read = read;
    }

    // ===== Getters & Setters =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
}