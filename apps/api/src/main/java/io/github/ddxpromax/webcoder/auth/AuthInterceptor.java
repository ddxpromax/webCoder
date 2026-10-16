package io.github.ddxpromax.webcoder.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/auth/login",
        "/api/auth/status",
        "/api/auth/register",
        "/api/auth/verify",
        "/api/version",
        "/api/health",
        "/api/healthz",
        "/api/settings/language",
        "/api/settings/upload-limit"
    );

    private final AuthStore auth;

    public AuthInterceptor(AuthStore auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) throws Exception {
        if (!auth.isEnabled()
            || PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        if (auth.validToken(AuthController.bearerToken(
            request.getHeader("Authorization")
        ))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"detail\":\"Not authenticated\"}"
        );
        return false;
    }
}
