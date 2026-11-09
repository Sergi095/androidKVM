package org.androidkvm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saved KVMs. Only encrypted passwords are written to app preferences. */
final class ProfileStore {
    static final class Profile {
        final String id, address, user, pin, iv, encryptedPassword;

        Profile(String id, String address, String user, String pin, String iv, String encryptedPassword) {
            this.id = id;
            this.address = address;
            this.user = user;
            this.pin = pin;
            this.iv = iv;
            this.encryptedPassword = encryptedPassword;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("address", address).put("user", user)
                    .put("pin", pin).put("iv", iv).put("password", encryptedPassword);
        }
    }

    private static final String ALIAS = "org.androidkvm.app.profiles.v1";
    private final SharedPreferences preferences;

    ProfileStore(Context context) {
        preferences = context.getSharedPreferences("saved_kvms", Context.MODE_PRIVATE);
    }

    List<Profile> list() {
        List<Profile> profiles = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString("profiles", "[]"));
            for (int n = 0; n < data.length(); n++) {
                JSONObject item = data.getJSONObject(n);
                profiles.add(new Profile(item.getString("id"), item.getString("address"),
                        item.getString("user"), item.optString("pin"), item.getString("iv"),
                        item.getString("password")));
            }
        } catch (JSONException ignored) {
            // Invalid saved data is never used as a credential.
        }
        return profiles;
    }

    synchronized void save(String address, String user, String pin, String password) throws Exception {
        List<Profile> profiles = list();
        String id = UUID.randomUUID().toString();
        for (int n = 0; n < profiles.size(); n++) {
            Profile existing = profiles.get(n);
            if (existing.address.equals(address) && existing.user.equals(user)) {
                id = existing.id;
                profiles.remove(n);
                break;
            }
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(aad(id, address, user));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String encrypted = Base64.encodeToString(
                cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        profiles.add(0, new Profile(id, address, user, pin, iv, encrypted));
        write(profiles);
    }

    String password(Profile profile) throws Exception {
        byte[] iv = Base64.decode(profile.iv, Base64.DEFAULT);
        if (iv.length != 12) throw new IOException("Saved password is unavailable.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad(profile.id, profile.address, profile.user));
        return new String(cipher.doFinal(Base64.decode(profile.encryptedPassword, Base64.DEFAULT)),
                StandardCharsets.UTF_8);
    }

    synchronized void remove(String id) throws Exception {
        List<Profile> profiles = list();
        profiles.removeIf(profile -> profile.id.equals(id));
        write(profiles);
    }

    private void write(List<Profile> profiles) throws JSONException, IOException {
        JSONArray data = new JSONArray();
        for (Profile profile : profiles) data.put(profile.toJson());
        if (!preferences.edit().putString("profiles", data.toString()).commit()) {
            throw new IOException("Could not save KVM profiles.");
        }
    }

    private static byte[] aad(String id, String address, String user) {
        return (id + "\n" + address + "\n" + user).getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
