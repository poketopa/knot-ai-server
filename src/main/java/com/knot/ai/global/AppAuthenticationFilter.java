package com.knot.ai.global;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AppAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public AppAuthenticationFilter(AiServerProperties properties) {
        expected = ("Bearer " + properties.token()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(
                expected,
                authorization.getBytes(StandardCharsets.UTF_8)
        )) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write("{\"code\":\"UNAUTHORIZED\",\"message\":\"앱 인증이 필요합니다\"}");
            return;
        }
        chain.doFilter(
                request,
                response
        );
    }
}
