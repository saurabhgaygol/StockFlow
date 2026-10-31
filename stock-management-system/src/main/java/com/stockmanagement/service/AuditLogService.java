package com.stockmanagement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.stockmanagement.config.ClientIp;
import com.stockmanagement.entity.AuditLog;
import com.stockmanagement.repository.AuditLogRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class AuditLogService {
	
	 private final AuditLogRepository auditLogRepository;
	 private final boolean trustProxy;
	 private final int trustedHops;

	 public AuditLogService(AuditLogRepository auditLogRepository,
	         @Value("${app.security.trust-proxy:${api.external.trust-proxy:false}}") boolean trustProxy,
	         @Value("${app.security.trusted-proxy-hops:${api.external.trusted-proxy-hops:1}}") int trustedHops) {
	        this.auditLogRepository = auditLogRepository;
	        this.trustProxy = trustProxy;
	        this.trustedHops = Math.max(1, trustedHops);
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
	        // X-Forwarded-For is trusted only behind our own proxy (see ClientIp); a direct caller cannot fake its IP.
	        return ClientIp.resolve(request, trustProxy, trustedHops);
	    }
	 
	 
}