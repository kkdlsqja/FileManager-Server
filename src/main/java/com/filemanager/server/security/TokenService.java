package com.filemanager.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filemanager.server.domain.AuthToken;
import com.filemanager.server.domain.AuthTokenRepository;
import com.filemanager.server.domain.User;

@Service
public class TokenService {

    private static final Duration TOKEN_LIFETIME = Duration.ofDays(30);

    private final AuthTokenRepository tokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(AuthTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Transactional
    public String issueToken(User user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        tokenRepository.save(new AuthToken(
                hash(token),
                user,
                Instant.now().plus(TOKEN_LIFETIME)
        ));

        return token;
    }

    @Transactional(readOnly = true)
    public Optional<Long> findUserId(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }

        return tokenRepository
                .findByTokenHashAndExpiresAtAfter(hash(token), Instant.now())
                .map(authToken -> authToken.getUser().getId());
    }

    @Transactional
    public void revokeToken(String token) {
        if (token != null && !token.trim().isEmpty()) {
            tokenRepository.deleteById(hash(token));
        }
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("토큰을 처리할 수 없습니다.", exception);
        }
    }
}