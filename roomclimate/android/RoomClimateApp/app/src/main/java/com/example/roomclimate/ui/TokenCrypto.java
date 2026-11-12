package com.example.roomclimate.ui;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Шифрование токена сессии ключом из Android Keystore (AES-256-GCM).
 * Ключ создаётся внутри защищённого хранилища и не извлекается из него, поэтому
 * копия файла настроек (root, резервная копия, отладка) не даёт токен в открытом виде.
 * Формат хранения: "v1:" + base64(iv || ciphertext).
 */
final class TokenCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "roomclimate_token_key";
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;

    private TokenCrypto() {}

    static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Зашифровать; при сбое Keystore (редкие прошивки) возвращает null — токен не сохраняем. */
    static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Расшифровать; если ключ потерян (сброс блокировки экрана и т.п.) — пустая строка = выйти. */
    static String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored == null ? "" : stored;
        }
        try {
            byte[] data = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setUnlockedDeviceRequired(false);
        }
        generator.init(spec.build());
        return generator.generateKey();
    }
}
