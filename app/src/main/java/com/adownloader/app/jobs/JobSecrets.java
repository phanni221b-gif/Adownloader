package com.adownloader.app.jobs;

import android.security.keystore.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned AES-GCM envelope: random IV, job-bound AAD, bounded compressed plaintext. */
public final class JobSecrets {
  private static final String ALIAS = "adownloader.download.context.v1";

  private static synchronized javax.crypto.SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (javax.crypto.SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
    return generator.generateKey();
  }

  public byte[] seal(long job, String text) throws Exception {
    byte[] plain = text.getBytes(StandardCharsets.UTF_8);
    if (plain.length > 8 * 1024 * 1024) throw new GeneralSecurityException("CONTEXT_TOO_LARGE");
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
      gzip.write(plain);
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(Long.toString(job).getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(compressed.toByteArray()), iv = cipher.getIV();
    return ByteBuffer.allocate(2 + iv.length + encrypted.length)
        .put((byte) 1)
        .put((byte) iv.length)
        .put(iv)
        .put(encrypted)
        .array();
  }

  public String open(long job, byte[] bytes) throws Exception {
    if (bytes == null || bytes.length < 30 || bytes[0] != 1 || bytes[1] != 12)
      throw new GeneralSecurityException("CONTEXT_INVALID");
    byte[] iv = java.util.Arrays.copyOfRange(bytes, 2, 14);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
    cipher.updateAAD(Long.toString(job).getBytes(StandardCharsets.UTF_8));
    byte[] compressed = cipher.doFinal(bytes, 14, bytes.length - 14);
    ByteArrayOutputStream plain = new ByteArrayOutputStream();
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      byte[] buffer = new byte[32768];
      int n;
      while ((n = gzip.read(buffer)) != -1) {
        if (plain.size() + n > 8 * 1024 * 1024)
          throw new GeneralSecurityException("CONTEXT_TOO_LARGE");
        plain.write(buffer, 0, n);
      }
    }
    return new String(plain.toByteArray(), StandardCharsets.UTF_8);
  }
}
