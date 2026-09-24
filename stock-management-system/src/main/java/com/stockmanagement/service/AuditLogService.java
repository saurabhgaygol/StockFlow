package com.stockmanagement.service;

import org.springframework.stereotype.Service;

import com.stockmanagement.entity.AuditLog;
import com.stockmanagement.repository.AuditLogRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class AuditLogService {
	
	 private final AuditLogRepository auditLogRepository;
	 
	 public AuditLogService(AuditLogRepository auditLogRepository) {
	        this.auditLogRepository = auditLogRepository;
	    }
       
	 
	 /**
	     * Full version - saari details ke saath audit log save karta hai
	     */
	    public void log(Long userId, String userName, String action, String module, String subModule,
	                     String description, String ipAddress, Long referenceId, String referenceType) {

	        AuditLog log = new AuditLog();
	        log.setUserId(userId);
	        log.setUsername(userName);
	        log.setAction(action);
	        log.setModule(module);
	        log.setSubModule(subModule);
	        log.setDescription(description);
	        log.setIpAddress(ipAddress);
	        log.setReferenceId(referenceId);
	        log.setReferenceType(referenceType);
	        

	        auditLogRepository.save(log);
	    }

	    /**
	     * Chhota version - jab reference id/type ki zarurat na ho
	     */
	    public void log(Long userId,String userName, String action, String module, String subModule,
	                     String description, String ipAddress) {

	        log(userId, userName, action, module, subModule, description, ipAddress, null, null);
	    }

	    /**
	     * HttpServletRequest se seedha IP address nikal kar log karne ke liye (shortcut)
	     */
	    public void log(Long userId,String userName, String action, String module, String subModule,
	                     String description, HttpServletRequest request) {

	        String ip = getClientIp(request);
	        log(userId,userName, action, module, subModule, description, ip, null, null);
	    }

	    /**
	     * Client ka real IP address nikalta hai (proxy/load-balancer ke case me bhi kaam karega)
	     */
	    public String getClientIp(HttpServletRequest request) {
	        String ip = request.getHeader("X-Forwarded-For");
	        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
	            ip = request.getRemoteAddr();
	        } else {
	            // Agar multiple IPs comma se separated hain, to pehla original client IP hota hai
	            ip = ip.split(",")[0].trim();
	        }
	        return ip;
	    }
	 
	 
}
