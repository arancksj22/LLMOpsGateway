package com.example.llmgateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Authenticates /v1/** requests with an internal gateway API key. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ATTR = "gatewayKey";

    private final ApiKeyService apiKeyService;

    public ApiKeyFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader("X-API-Key");
        if (key == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                key = auth.substring(7).trim();
            }
        }
        ApiKeyInfo info = key == null ? null : safeGet(key);
        if (info == null || info.revoked()) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"type\":\"unauthorized\",\"message\":\"Missing, unknown or revoked API key\"}}");
            return;
        }
        request.setAttribute(ATTR, info);
        chain.doFilter(request, response);
    }

    private ApiKeyInfo safeGet(String key) {
        try {
            return apiKeyService.get(key);
        } catch (Exception e) {
            return null;
        }
    }
}
