package com.keyloop.scheduler.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final int MAX_CORRELATION_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId = normalizeOrGenerate(incoming);

        MDC.put(MDC_KEY, correlationId);
        request.setAttribute(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String normalizeOrGenerate(String candidate) {
        if (candidate == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_ID_LENGTH || !isPrintableAscii(trimmed)) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }

    private boolean isPrintableAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch >= 0x7F) {
                return false;
            }
        }
        return true;
    }
}
