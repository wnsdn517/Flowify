package com.flowify.ettea;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.flowify.ettea.xposed.XpLog;
import com.flowify.ettea.xposed.XpRes;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Verifies the module APK's signing certificate against the pinned official release key. */
public final class AppSigningIdentity {
    // Update this fingerprint only when intentionally rotating the release signing key.
    private static final String OFFICIAL_CERT_SHA256 =
            "a3eba3842128556a00d0406c0f007487a256bc239470c32bb7574133d3504b25";

    private AppSigningIdentity() {
    }

    @SuppressWarnings("deprecation")
    public static boolean isOfficial(Context context) {
        String apkPath = XpRes.moduleSourceDir();
        if (apkPath == null || apkPath.isEmpty()) {
            XpLog.log("[SpotifyPlus] module APK path unavailable; treating build as unofficial");
            return false;
        }

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apkPath, flags);
        if (info == null) {
            XpLog.log("[SpotifyPlus] unable to inspect module APK signature; treating build as unofficial");
            return false;
        }

        Signature[] signers;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signers = info.signingInfo.getApkContentsSigners();
        } else {
            signers = info.signatures;
        }
        if (signers == null || signers.length == 0) return false;
        for (Signature signer : signers) {
            if (OFFICIAL_CERT_SHA256.equals(sha256(signer))) return true;
        }
        return false;
    }

    private static String sha256(Signature signature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by Android", impossible);
        }
    }
}
