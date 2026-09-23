package com.aid.diagnostics;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 使用实例本地密钥保护诊断配置和临时支持资料。 */
@Component
public class DiagnosticSecretStore {
    @Value("${aid.diagnostics.key-file:${user.home}/.aid-private/diagnostics.key}")
    private String keyFile;

    private synchronized byte[] localKey() throws Exception {
        Path path = Path.of(keyFile).toAbsolutePath();
        Files.createDirectories(path.getParent());
        try (java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignoredLock=channel.lock()) {
        if (!Files.exists(path)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            try {
                if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(path, key);
                } else {
                    Files.write(path, key, StandardOpenOption.CREATE_NEW);
                    path.toFile().setReadable(false, false);
                    path.toFile().setWritable(false, false);
                    path.toFile().setReadable(true, true);
                    path.toFile().setWritable(true, true);
                }
            } catch (FileAlreadyExistsException ignored) { }
        }
        byte[] key = Files.readAllBytes(path);
        if (key.length != 32) throw new IllegalStateException("诊断本地密钥不可用");
        return key;
        }
    }

    public String seal(String value) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey(),"AES"),new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法保护诊断资料",e); }
    }

    public String open(String value) {
        try {
            String[] parts = value.split("\\.",2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(localKey(),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("诊断本地密钥与保存资料不匹配",e); }
    }
}
