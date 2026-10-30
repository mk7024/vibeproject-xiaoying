package com.muse.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SessionService {
    public static final String COOKIE_NAME = "muse_session";
    public static final String OWNER_ATTRIBUTE = SessionService.class.getName() + ".owner";
    private static final Duration LIFETIME = Duration.ofDays(30);
    private final SecureRandom random = new SecureRandom();
    private final StoreMapper store;
    private final Clock clock;
    private final AppProperties properties;

    public SessionService(StoreMapper store, Clock clock, AppProperties properties) {
        this.store = store;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public ApiModels.Identity open(HttpServletRequest request, HttpServletResponse response, boolean fresh) {
        Rows.IdentityRow existing = fresh ? null : findCurrent(request);
        if (existing != null) return view(existing);
        if (fresh) deleteCurrentSession(request);
        Rows.IdentityRow identity = createIdentity();
        createSession(identity.id, response);
        return view(identity);
    }

    @Transactional
    public ApiModels.Identity rotate(String identityId, HttpServletRequest request, HttpServletResponse response) {
        deleteCurrentSession(request);
        Rows.IdentityRow identity = store.findIdentity(identityId);
        if (identity == null) throw ApiException.unauthorized("身份不存在，请刷新页面");
        createSession(identity.id, response);
        return view(identity);
    }

    @Transactional
    public ApiModels.Identity logout(HttpServletRequest request, HttpServletResponse response) {
        deleteCurrentSession(request);
        Rows.IdentityRow identity = createIdentity();
        createSession(identity.id, response);
        return view(identity);
    }

    public Rows.IdentityRow findCurrent(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String token = cookie.getValue();
                if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
                return store.findSession(hash(token), clock.instant());
            }
        }
        return null;
    }

    public static String owner(HttpServletRequest request) {
        Object owner = request.getAttribute(OWNER_ATTRIBUTE);
        if (!(owner instanceof String id)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先创建或恢复访客身份");
        }
        return id;
    }

    private ApiModels.Identity view(Rows.IdentityRow identity) {
        Rows.AccountRow account = store.findAccountByIdentity(identity.id);
        return identity.view(account == null ? null : account.email);
    }

    private Rows.IdentityRow createIdentity() {
        var identity = new Rows.IdentityRow();
        identity.id = UUID.randomUUID().toString();
        identity.displayName = "灵感访客 " + identity.id.substring(0, 6).toUpperCase();
        identity.createdAt = clock.instant();
        store.insertIdentity(identity);
        return identity;
    }

    private void createSession(String identityId, HttpServletResponse response) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.insertSession(hash(token), identityId, clock.instant().plus(LIFETIME));
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/").maxAge(LIFETIME).build().toString());
    }

    private void deleteCurrentSession(HttpServletRequest request) {
        String hash = currentTokenHash(request);
        if (hash != null) store.deleteSession(hash);
    }

    private String currentTokenHash(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String token = cookie.getValue();
                if (token != null && token.matches("[A-Za-z0-9_-]{43}")) return hash(token);
            }
        }
        return null;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
