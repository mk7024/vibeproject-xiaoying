package com.muse.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final SessionService sessions;

    public WebConfiguration(SessionService sessions) { this.sessions = sessions; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Rows.IdentityRow identity = sessions.findCurrent(request);
                if (identity == null) {
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "请先创建或恢复访客身份");
                }
                request.setAttribute(SessionService.OWNER_ATTRIBUTE, identity.id);
                return true;
            }
        }).addPathPatterns("/api/**")
                .excludePathPatterns("/api/session", "/api/templates", "/api/templates/*", "/api/categories", "/api/health");
    }

    @Component
    public static class PrivacyHeaders extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("X-Content-Type-Options", "nosniff");
            boolean apiRequest = request.getRequestURI().startsWith("/api/");
            if (apiRequest) {
                response.setHeader("Cache-Control", "private, no-store");
                response.setHeader("Pragma", "no-cache");
            }
            boolean mutation = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                    && !"OPTIONS".equals(request.getMethod());
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            // A custom header blocks HTML forms; cross-origin scripts require CORS, which is disabled.
            if (mutation && ("cross-site".equals(fetchSite) || "same-site".equals(fetchSite)
                    || (apiRequest && !"1".equals(request.getHeader("X-Muse-Request"))))) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"不允许的请求\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
