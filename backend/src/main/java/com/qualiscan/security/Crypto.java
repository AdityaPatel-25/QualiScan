package com.qualiscan.security;
import java.nio.charset.StandardCharsets; import java.security.*; import java.util.*;
public final class Crypto {private Crypto(){} public static String sha256(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}} public static String newApiKey(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}}
