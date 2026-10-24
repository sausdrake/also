package com.example.autoauction.auth.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtService {

    /**
     * JWT-TYPE-01: access and refresh tokens are signed with the same key, so before this claim a refresh
     * token (7 days) was accepted as a Bearer access token (24 h) on every protected endpoint - and kept
     * working after logout, since logout only revokes the refresh token's database row. Every token now
     * names its type; only {@value #ACCESS} authenticates API and WebSocket requests. Tokens issued before
     * the claim existed carry no type and are refused (users sign in again once).
     */
    static final String TOKEN_TYPE_CLAIM = "token_type";
    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put(TOKEN_TYPE_CLAIM, ACCESS);
        return buildToken(claims, userDetails, jwtExpiration);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, REFRESH);
        return buildToken(claims, userDetails, refreshExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        // jti (JWT ID) гарантирует уникальность каждого токена,
        // даже если два токена сгенерированы в одну и ту же миллисекунду.
        claims.put("jti", UUID.randomUUID().toString());
        return Jwts
                .builder()
                .setClaims(claims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** An ACCESS token of this user that has not expired - the only kind that authenticates a request. */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final Claims claims = extractAllClaims(token);
        return userDetails.getUsername().equals(claims.getSubject())
                && claims.getExpiration() != null && claims.getExpiration().after(new Date())
                && ACCESS.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
    }

    private Claims extractAllClaims(String token) {
        return Jwts
                .parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSignInKey() {
        byte[] keyBytes = secretKey.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }
}