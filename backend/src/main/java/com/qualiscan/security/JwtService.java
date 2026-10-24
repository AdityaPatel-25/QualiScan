package com.qualiscan.security;
import io.jsonwebtoken.*; import io.jsonwebtoken.security.Keys; import java.nio.charset.StandardCharsets; import java.time.*; import java.util.*; import javax.crypto.SecretKey; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component;
@Component public class JwtService {
 private final SecretKey key; private final long minutes;
 public JwtService(@Value("${qualiscan.jwt.secret}") String secret,@Value("${qualiscan.jwt.expiration-minutes:120}") long minutes){if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("JWT secret must be at least 32 bytes");key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));this.minutes=minutes;}
 public String issue(String username,String role){Instant now=Instant.now();return Jwts.builder().subject(username).claim("role",role).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(minutes*60))).signWith(key).compact();}
 public Jws<Claims> parse(String token){return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);}
 public Instant expiry(){return Instant.now().plusSeconds(minutes*60);}
}
