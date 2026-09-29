package com.filemanager.server.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    Optional<AuthToken> findByTokenHashAndExpiresAtAfter(
            String tokenHash,
            Instant now
    );
}