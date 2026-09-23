package com.aid.diagnostics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** 诊断报文的跨语言加密协议。 */
public final class DiagnosticCrypto {
    public static final int MAX_PLAIN_BYTES = 32 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private DiagnosticCrypto() { }

    public static String generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public static byte[] key(String value) {
        try {
            byte[] key = Base64.getDecoder().decode(value.trim());
            if (key.length == 32) return key;
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("AES 密钥必须是 32 字节的标准 Base64 编码");
    }

    public static Map<String, Object> encrypt(String json, String aesKey, String publicKey,
                                               String keyId, String reportId, String instanceId) {
        try {
            byte[] plain = json.getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_PLAIN_BYTES) throw new IllegalArgumentException("完整错误超过发送大小限制，原文已保留");
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) { gzip.write(plain); }
            if (zipped.size() > MAX_COMPRESSED_BYTES) throw new IllegalArgumentException("完整错误压缩后超过发送大小限制，原文已保留");
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            long createdAt = System.currentTimeMillis();
            String aad = "aid-diagnostics-v1\n" + reportId + "\n" + instanceId + "\n" + keyId + "\n" + createdAt;
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(aesKey), "AES"), new GCMParameterSpec(128, nonce));
            aes.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = aes.doFinal(zipped.toByteArray());
            String pem = publicKey.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(pem))),
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            Base64.Encoder b64 = Base64.getEncoder();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("protocolVersion", 1);
            result.put("reportId", reportId);
            result.put("instanceId", instanceId);
            result.put("keyId", keyId);
            result.put("createdAt", createdAt);
            result.put("encryptedKey", b64.encodeToString(rsa.doFinal(key(aesKey))));
            result.put("nonce", b64.encodeToString(nonce));
            result.put("ciphertext", b64.encodeToString(Arrays.copyOf(encrypted, encrypted.length - 16)));
            result.put("tag", b64.encodeToString(Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length)));
            return result;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("诊断报文加密失败", e); }
    }
}
