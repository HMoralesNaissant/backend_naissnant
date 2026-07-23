package com.tenantos.registrar.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT provider using JJWT library for token generation and validation.
 */
public class JwtProvider {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtProvider(String secret, long ttlSeconds) {
        // Ensure secret is at least 32 bytes for HS256
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            StringBuilder padded = new StringBuilder(secret);
            while (padded.toString().getBytes(StandardCharsets.UTF_8).length < 32) {
                padded.append(secret);
            }
            secretBytes = padded.toString().getBytes(StandardCharsets.UTF_8);
        }
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMs = ttlSeconds * 1000;
    }

    /**
     * Generate a JWT token for the given subject (typically username or email).
     */
    public String generateToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validate and parse a JWT token.
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // Any signature, expiration, or parse error
            return null;
        }
    }

    /**
     * Extract subject from a valid token.
     */
    public String getSubject(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * Check if a token is valid (not expired and properly signed).
     */
    public boolean isTokenValid(String token) {
        return validateToken(token) != null;
    }
}
