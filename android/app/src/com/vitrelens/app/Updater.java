package com.vitrelens.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 从 GitHub Release 检查、下载并安装更新。
 *
 * 刻意保守：自动检查失败一律静默（国内直连 GitHub 经常超时），
 * 只有用户主动点版本号时才把错误说出来。自动检查每天最多一次。
 */
final class Updater {

    private static final String TAG = "VitreLens";
    private static final String API =
            "https://api.github.com/repos/vmware001/VitreLens/releases/latest";
    private static final String PREFS = "vl_update";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    private Updater() {
    }

    /** 一次检查的结果 */
    static class Info {
        String version;   // "1.40"，去掉 v 前缀
        String notes;     // Release 正文
        String apkUrl;    // 直链
        String apkName;
    }

    interface Progress {
        void onProgress(int percent, long got, long total);
    }

    /* ── 检查 ── */

    /** 距离上次自动检查是否已超过一天 */
    static boolean shouldAutoCheck(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(KEY_LAST_CHECK, 0);
        return System.currentTimeMillis() - last > AUTO_INTERVAL_MS;
    }

    static void markChecked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    static Info fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "VitreLens-Updater");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            String json = readAll(c.getInputStream());

            String tag = jsonString(json, "tag_name");
            if (tag == null || tag.isEmpty()) throw new RuntimeException("响应里没有 tag_name");

            Info info = new Info();
            info.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            info.notes = jsonString(json, "body");

            // assets 里取第一个 .apk
            int at = json.indexOf("\"assets\"");
            if (at > 0) {
                String sub = json.substring(at);
                int nameAt = sub.indexOf(".apk");
                if (nameAt > 0) {
                    info.apkUrl = jsonString(sub, "browser_download_url");
                    info.apkName = jsonString(sub, "name");
                }
            }
            if (info.apkUrl == null) throw new RuntimeException("这个 Release 没有 APK 附件");
            return info;
        } finally {
            c.disconnect();
        }
    }

    /** 远端版本是否比本地新 */
    static boolean isNewer(String remote, String local) {
        int[] a = parseVer(remote);
        int[] b = parseVer(local);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parseVer(String v) {
        String s = v == null ? "" : v.replaceAll("[^0-9.]", "");
        if (s.isEmpty()) return new int[]{0};
        String[] p = s.split("\\.");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                r[i] = Integer.parseInt(p[i]);
            } catch (Exception e) {
                r[i] = 0;
            }
        }
        return r;
    }

    /* ── 下载 ── */

    static File download(Context ctx, Info info, Progress cb) throws Exception {
        File dir = new File(ctx.getCacheDir(), ApkProvider.DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new RuntimeException("无法创建下载目录");
        // 清掉上一次的残留，避免 cache 越攒越大
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) f.delete();
        }

        String name = (info.apkName != null && info.apkName.toLowerCase().endsWith(".apk"))
                ? info.apkName : ("VitreLens-" + info.version + ".apk");
        // 文件名只留安全字符，Provider 那边也会再挡一次
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File out = new File(dir, name);

        Log.i(TAG, "开始下载 " + info.apkUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(info.apkUrl).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "VitreLens-Updater");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            // GitHub 的直链常走 chunked，拿不到 Content-Length；界面要能退化成"已下载 X KB"
            long total = c.getContentLengthLong();
            Log.i(TAG, "响应 " + code + "，总长度 " + total);
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            try {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    got += n;
                    if (cb != null) {
                        int pct = total > 0 ? (int) Math.min(100, got * 100 / total) : -1;
                        cb.onProgress(pct, got, total);
                    }
                }
                if (got <= 0) throw new RuntimeException("下载到 0 字节");
                Log.i(TAG, "下载完成 " + got + " 字节 → " + out.getName());
            } finally {
                try { fo.close(); } catch (Exception ignored) { }
                try { in.close(); } catch (Exception ignored) { }
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /* ── 安装 ── */

    /**
     * @return true 已交给系统安装器；false 需要用户先授权「安装未知应用」
     */
    static boolean install(Activity act, File apk) {
        PackageManager pm = act.getPackageManager();

        // 校验包名：万一链接被换掉，也不能把别的应用装上
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null || !act.getPackageName().equals(pi.packageName)) {
            throw new RuntimeException("下载到的文件不是本应用的安装包");
        }

        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Throwable t) {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return false;
        }

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.uriFor(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
        return true;
    }

    /* ── 工具 ── */

    /**
     * 当前安装的是不是「本地版」——含官方品牌图标的那种。
     * GitHub 上的发布版不含图标，本地版更新后将不再显示品牌图标，得提前说明。
     */
    static boolean hasBundledLogos(Context ctx) {
        try {
            Bitmap b = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.logo_leica);
            boolean big = b != null && b.getWidth() > 1;
            if (b != null) b.recycle();
            return big;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[1 << 13];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 取 JSON 里某个字符串字段的值（够用就行，不引第三方解析库） */
    private static String jsonString(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int p = i + pat.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return null;
        p++;
        StringBuilder sb = new StringBuilder();
        while (p < json.length()) {
            char ch = json.charAt(p++);
            if (ch == '\\') {
                if (p >= json.length()) break;
                char e = json.charAt(p++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                        if (p + 4 <= json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                            } catch (Exception ignored) { }
                            p += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static void log(String msg) {
        Log.i(TAG, msg);
    }
}
