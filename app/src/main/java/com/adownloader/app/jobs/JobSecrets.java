package com.adownloader.app.jobs;

import android.security.keystore.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Non-exportable app key. Fresh random GCM IV, authenticated job identity, no plaintext fallback.
 */
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
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(Long.toString(job).getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
    byte[] iv = cipher.getIV();
    return ByteBuffer.allocate(1 + iv.length + encrypted.length)
        .put((byte) iv.length)
        .put(iv)
        .put(encrypted)
        .array();
  }

  public String open(long job, byte[] bytes) throws Exception {
    if (bytes == null || bytes.length < 29 || bytes[0] != 12)
      throw new GeneralSecurityException("CONTEXT_INVALID");
    byte[] iv = java.util.Arrays.copyOfRange(bytes, 1, 13);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
    cipher.updateAAD(Long.toString(job).getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8);
  }
}
