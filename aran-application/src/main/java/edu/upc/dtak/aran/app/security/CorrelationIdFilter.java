package edu.upc.dtak.aran.app.security;

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

/**
 * Assigns a correlation ID to every request and exposes it in:
 * <ul>
 *   <li>The SLF4J MDC as {@code correlationId} — included in every log line.</li>
 *   <li>The {@code X-Correlation-ID} response header — echo'd back to the caller.</li>
 * </ul>
 *
 * <p>The ID is taken from the incoming {@code X-Correlation-ID} request header if
 * present; otherwise a new UUID is generated. This allows callers (the SPA, the
 * gateway, or integration tests) to inject a trace ID that threads through the
 * entire request, including any downstream calls to aran-lake.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 1} — just after
 * {@link org.apache.catalina.filters.RemoteIpFilter} which resolves the real
 * client IP, so the MDC already has the correct address when this filter fires.
 * All subsequent filters and code see the correlation ID in MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER    = "X-Correlation-ID";
    public static final String MDC_KEY   = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
