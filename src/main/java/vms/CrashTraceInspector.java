package vms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

// ゲーム終了後のクラッシュ痕跡解析と通知
// relaunch後の親プロセスから子（ゲーム）プロセスの異常終了時に呼ばれ、
// 子のloader状態には触れられないためMODの特定は渡されたMOD一覧とjar走査で行う
final class CrashTraceInspector {
    // ローダーがMOD解決の段階で起動を中止した場合のメッセージ
    private static final String RESOLUTION_FAILED = "Mod resolution failed";
    private static final String INCOMPATIBLE_MODS = "Incompatible mods found!";

    // 他MODに置き換えられたメソッドへのMixin注入失敗を表すメッセージ
    private static final Pattern CONFLICT = Pattern.compile(
            "cannot inject into (\\S+) merged by (\\S+) with priority");

    // Mixinエラー文面に付く、注入側MODのid装飾
    private static final Pattern FROM_MOD = Pattern.compile("\\bfrom mod ([\\w-]+)");

    // 適用に失敗したmixin設定名の抽出元
    private static final Pattern FAILED_CONFIG = Pattern.compile(
            "in config \\[([^\\]]+)\\] FAILED during");

    // 設定名が直接取れない場合の予備で、jsonトークンを総当たりする
    private static final Pattern JSON_TOKEN = Pattern.compile("([\\w./\\-]+\\.json)\\b");

    // fabric.mod.json のid・nameの簡易抽出
    private static final Pattern JSON_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    // Mixinの注入先がこのMCバージョンに存在しない場合のメッセージ
    private static final String TARGET_NOT_FOUND = "could not find any targets matching";

    // MODが前提とするクラス・メソッド・フィールドが実行環境に無い場合のエラー
    private static final Pattern LINKAGE_ERROR = Pattern.compile(
            "java\\.lang\\.(?:NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError):\\s*(.+)");

    // MC本体のクラス・メンバへの参照（mojmap名およびintermediary名）
    private static final Pattern MC_REF = Pattern.compile(
            "net[./]minecraft|com[./]mojang|\\bclass_\\d+|\\bmethod_\\d+|\\bfield_\\d+");

    // MixinがMCクラスへ合成するハンドラ名（種別$識別子$modid$元メソッド名）からのmodid抽出
    private static final Pattern MIXIN_HANDLER = Pattern.compile("^[a-z]\\w*\\$\\w+\\$([\\w-]+)\\$");

    // MODに帰属しないクラスのパッケージ
    private static final String[] NON_MOD_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "com.mojang.",
            "net.fabricmc.loader.", "net.fabricmc.api.",
            "org.spongepowered.asm.", "org.objectweb.asm.",
            "com.llamalad7.mixinextras.",
            // vanilla同梱ライブラリ
            "org.lwjgl.", "io.netty.", "com.google.", "it.unimi.dsi.",
            "org.apache.", "org.joml.", "org.slf4j.", "oshi.",
    };

    // スタックフレームから逆引きするクラス数・表示するMOD数の上限
    private static final int MAX_FRAME_CLASSES = 30;
    private static final int MAX_INVOLVED_MODS = 5;

    // latest.logの解析範囲（末尾からのバイト数）
    private static final int LOG_TAIL_LIMIT = 256 * 1024;

    // 解析対象MODの情報
    static final class ModEntry {
        final String id;
        final String name;
        final Path jar;

        ModEntry(String id, String name, Path jar) {
            this.id = id;
            this.name = name;
            this.jar = jar;
        }
    }

    // jar走査で特定したMODの表示情報
    private static final class ModHit {
        final String id;
        final String name;
        // 内包MODの場合の外側MOD
        final ModEntry container;
        // id不明のMODの重複排除に使うjarファイル名
        final String jarLabel;

        ModHit(String id, String name, ModEntry container, String jarLabel) {
            this.id = id;
            this.name = name;
            this.container = container;
            this.jarLabel = jarLabel;
        }
    }

    // jarごとの走査結果のキャッシュ
    private static final class ModIndex {
        final List<ModEntry> mods;
        private final JarScan[] scans;

        ModIndex(List<ModEntry> mods) {
            this.mods = mods;
            this.scans = new JarScan[mods.size()];
        }

        JarScan scan(int i) {
            if (scans[i] == null) {
                scans[i] = scanJar(mods.get(i).jar);
            }
            return scans[i];
        }
    }

    // 外側jar1つ分の走査結果
    private static final class JarScan {
        // fabric.mod.json の {id, name}
        final String[] meta;
        final Set<String> entries;
        // 全深さの内包jar
        final List<NestedScan> nested;

        JarScan(String[] meta, Set<String> entries, List<NestedScan> nested) {
            this.meta = meta;
            this.entries = entries;
            this.nested = nested;
        }
    }

    // 内包jar1つ分の走査結果
    private static final class NestedScan {
        final String[] meta;
        final Set<String> entries;
        // 内包jarのファイル名
        final String label;

        NestedScan(String[] meta, Set<String> entries, String label) {
            this.meta = meta;
            this.entries = entries;
            this.label = label;
        }
    }

    private CrashTraceInspector() {
    }

    // クラッシュ痕跡を解析して通知する
    static void run(Path gameDir, long launchTime, String mcVersion, List<ModEntry> mods) throws Exception {
        String text = crashText(gameDir, launchTime);
        if (text == null) {
            return;
        }
        String message = analyze(new ModIndex(mods), text, mcVersion);
        if (message != null) {
            showDialog(message);
        }
    }

    // jarのfabric.mod.jsonからModEntryを作る
    static ModEntry entryFromJar(Path jar) {
        String[] meta = readJarMeta(jar);
        if (meta == null || meta[0] == null) {
            return null;
        }
        return new ModEntry(meta[0], meta[1], jar);
    }

    // macOSの-XstartOnFirstThread下でAWTがハングするため、ダイアログは別JVMで表示する
    private static void showDialog(String message) throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            java = java.replace('/', '\\') + ".exe";
        }
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx100M",
                "-cp", ownJar().toString(), CrashDialog.class.getName());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();
        OutputStream in = process.getOutputStream();
        in.write(message.getBytes(StandardCharsets.UTF_8));
        in.close();
        process.waitFor();
    }

    // このクラスを含むVMSのjarパス
    private static Path ownJar() throws Exception {
        URL url = CrashDialog.class.getProtectionDomain().getCodeSource().getLocation();
        String s = url.toString();
        if (s.startsWith("jar:")) {
            int sep = s.indexOf("!/");
            url = new URL(sep >= 0 ? s.substring(4, sep) : s.substring(4));
        }
        return Paths.get(url.toURI());
    }

    // 解析対象の文面を集める
    private static String crashText(Path gameDir, long launchTime) {
        Path report = newestFileSince(gameDir.resolve("crash-reports"), launchTime);
        if (report != null) {
            return readText(report);
        }
        Path log = gameDir.resolve("logs").resolve("latest.log");
        try {
            // 解析対象セッションより古いログは対象外
            if (Files.isRegularFile(log)
                    && Files.getLastModifiedTime(log).toMillis() >= launchTime - 5000) {
                // 全文を見ると序盤の無害な警告トレースを誤検出するため、末尾だけ読む
                return readTail(log, LOG_TAIL_LIMIT);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // クラッシュ文面を解析し、ダイアログ用メッセージを返す
    private static String analyze(ModIndex index, String text, String mcVersion) {
        // MOD解決失敗はローダー自身がエラーGUIを表示するため、重ねて通知しない
        if (text.contains(RESOLUTION_FAILED) || text.contains(INCOMPATIBLE_MODS)) {
            return null;
        }

        List<ModHit> involved = involvedMods(index, text);

        String diagnosis = diagnoseMixinConflict(index, text);
        if (diagnosis == null) {
            diagnosis = diagnoseVersionMismatch(index, text, involved, mcVersion);
        }
        if (diagnosis == null && involved.isEmpty()) {
            return null;
        }

        StringBuilder message = new StringBuilder();
        if (diagnosis != null) {
            message.append("Version Mod Sorter detected why the game crashed:\n\n").append(diagnosis);
        } else {
            message.append("Version Mod Sorter detected that the game crashed.\n");
        }
        if (!involved.isEmpty()) {
            message.append("\nMods involved in the crash:\n");
            for (ModHit mod : involved) {
                message.append("  - ").append(describe(mod, "?")).append('\n');
            }
        }
        return message.toString();
    }

    // 2MODが同じメソッドを書き換えようとして衝突したケース
    private static String diagnoseMixinConflict(ModIndex index, String text) {
        Matcher conflict = CONFLICT.matcher(text);
        if (!conflict.find()) {
            return null;
        }
        String mergedByClass = conflict.group(2);
        String failedConfig = findFailedConfig(index, text);

        ModHit injecting = null;
        Matcher fromMod = FROM_MOD.matcher(text);
        if (fromMod.find()) {
            injecting = findById(index, fromMod.group(1));
        }
        if (injecting == null) {
            injecting = findByResource(index, failedConfig);
        }
        ModHit merging = findByResource(index, mergedByClass.replace('.', '/') + ".class");

        // 同一MOD内の問題は組み合わせ起因ではないため対象外
        if (injecting != null && merging != null && injecting.id != null
                && injecting.id.equals(merging.id)) {
            return null;
        }

        String injectingText = describe(injecting, failedConfig != null ? failedConfig : "unknown mixin config");
        String mergingText = describe(merging, mergedByClass);

        return "Mod " + injectingText + " and mod " + mergingText + " cannot be used together:\n"
                + "they modify the same part of the game and conflict with each other.\n"
                + "Fix: remove either mod, or check for updated versions that resolve the conflict.\n";
    }

    // MODが現在のMCバージョンに存在しないコードを前提としているケース
    private static String diagnoseVersionMismatch(ModIndex index, String text,
            List<ModHit> involved, String mcVersion) {
        ModHit mod = null;
        String evidence = null;
        if (text.contains(TARGET_NOT_FOUND)) {
            evidence = "a Mixin in the mod targets code that does not exist in this Minecraft version";
            Matcher fromMod = FROM_MOD.matcher(text);
            if (fromMod.find()) {
                mod = findById(index, fromMod.group(1));
            }
            if (mod == null) {
                mod = findByResource(index, findFailedConfig(index, text));
            }
        } else {
            Matcher linkage = LINKAGE_ERROR.matcher(text);
            if (linkage.find() && MC_REF.matcher(linkage.group(1)).find()) {
                evidence = "the mod references Minecraft code that does not exist in this version";
                // エラーはMODの呼び出し箇所で発生するため、最上位の関与MODを原因とみなす
                if (!involved.isEmpty()) {
                    mod = involved.get(0);
                }
            }
        }
        if (mod == null) {
            return null;
        }

        return "Mod " + describe(mod, "unknown")
                + " is not compatible with Minecraft " + mcVersion + ":\n"
                + evidence + ".\n"
                + "Fix: move it to the matching mods/fabric/<version>/ folder, update it, or remove it.\n";
    }

    // スタックフレームのクラスをMODへ逆引きする
    private static List<ModHit> involvedMods(ModIndex index, String text) {
        List<ModHit> involved = new ArrayList<ModHit>();
        Set<String> seenClasses = new HashSet<String>();
        Set<String> seenMods = new HashSet<String>();
        for (String line : text.split("\r?\n")) {
            String frame = line.trim();
            if (!frame.startsWith("at ")) {
                continue;
            }
            String desc = frame.substring(3).trim();
            int paren = desc.indexOf('(');
            if (paren > 0) {
                desc = desc.substring(0, paren);
            }
            // モジュール名・クラスローダ名の前置き（java.base/ や knot//）を除去
            int slash = desc.lastIndexOf('/');
            if (slash >= 0) {
                desc = desc.substring(slash + 1);
            }
            int lastDot = desc.lastIndexOf('.');
            if (lastDot <= 0) {
                continue;
            }
            String className = desc.substring(0, lastDot);
            String methodName = desc.substring(lastDot + 1);

            ModHit hit = null;
            // MixinがMCクラスへ合成したハンドラはメソッド名にmodidを含むため、逆引きできる
            Matcher handler = MIXIN_HANDLER.matcher(methodName);
            if (handler.find()) {
                hit = findByIdResolved(index, handler.group(1), true);
            }
            if (hit == null) {
                if (isNonModClass(className) || !seenClasses.add(className)) {
                    continue;
                }
                if (seenClasses.size() > MAX_FRAME_CLASSES) {
                    break;
                }
                hit = findByResource(index, className.replace('.', '/') + ".class");
            }
            if (hit == null) {
                continue;
            }
            String key = hit.id != null ? hit.id : String.valueOf(hit.jarLabel);
            if (seenMods.add(key)) {
                involved.add(hit);
                if (involved.size() >= MAX_INVOLVED_MODS) {
                    break;
                }
            }
        }
        return involved;
    }

    private static boolean isNonModClass(String className) {
        for (String prefix : NON_MOD_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ログ・ダイアログ用の表示名
    private static String describe(ModHit hit, String fallback) {
        if (hit == null) {
            return "(unidentified: " + fallback + ")";
        }
        StringBuilder s = new StringBuilder();
        if (hit.id != null) {
            s.append('\'').append(hit.id).append('\'');
            if (hit.name != null) {
                s.append(" (").append(hit.name).append(')');
            }
        } else {
            s.append("(unidentified: ").append(fallback).append(')');
        }
        if (hit.container != null) {
            s.append(" [bundled in '").append(hit.container.id).append("']");
        }
        return s.toString();
    }

    private static String findFailedConfig(ModIndex index, String text) {
        Matcher m = FAILED_CONFIG.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        Matcher tokens = JSON_TOKEN.matcher(text);
        while (tokens.find()) {
            String token = tokens.group(1);
            if (token.contains("refmap")) {
                continue;
            }
            if (findByResource(index, token) != null) {
                return token;
            }
        }
        return null;
    }

    private static ModHit findById(ModIndex index, String id) {
        ModHit hit = findByIdResolved(index, id);
        return hit != null ? hit : new ModHit(id, null, null, null);
    }

    private static ModHit findByIdResolved(ModIndex index, String id) {
        return findByIdResolved(index, id, false);
    }

    // handlerFormではハンドラ名に埋め込まれた加工済みID表記とも照合する
    private static ModHit findByIdResolved(ModIndex index, String id, boolean handlerForm) {
        for (int i = 0; i < index.mods.size(); i++) {
            ModEntry mod = index.mods.get(i);
            if (idMatches(id, mod.id, handlerForm)) {
                return new ModHit(mod.id, mod.name, null, mod.jar.getFileName().toString());
            }
        }
        for (int i = 0; i < index.mods.size(); i++) {
            for (NestedScan nested : index.scan(i).nested) {
                if (nested.meta != null && nested.meta[0] != null
                        && idMatches(id, nested.meta[0], handlerForm)) {
                    return new ModHit(nested.meta[0], nested.meta[1], index.mods.get(i),
                            nested.label);
                }
            }
        }
        return null;
    }

    // sponge-mixin 0.15以降のハンドラ名は、modidを英字のみ12文字に切り詰めた形で埋め込む
    private static boolean idMatches(String token, String modId, boolean handlerForm) {
        if (modId.equals(token)) {
            return true;
        }
        if (!handlerForm) {
            return false;
        }
        String clean = modId.replaceAll("[^A-Za-z]", "");
        if (clean.length() > 12) {
            clean = clean.substring(0, 12);
        }
        return !clean.isEmpty() && clean.equals(token);
    }

    // 指定リソースを内包するMODを探す
    private static ModHit findByResource(ModIndex index, String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        for (int i = 0; i < index.mods.size(); i++) {
            JarScan scan = index.scan(i);
            ModEntry mod = index.mods.get(i);
            if (scan.entries.contains(resourcePath)) {
                return new ModHit(mod.id, mod.name, null, mod.jar.getFileName().toString());
            }
            for (NestedScan nested : scan.nested) {
                if (nested.entries.contains(resourcePath)) {
                    String[] meta = nested.meta;
                    return new ModHit(meta != null ? meta[0] : null, meta != null ? meta[1] : null,
                            mod, nested.label);
                }
            }
        }
        return null;
    }

    // 検索クエリはクラス（スタックフレーム）とmixin設定（.json）に限られるため、索引もそこへ絞る
    private static boolean isSearchableEntry(String name) {
        return name.endsWith(".class") || name.endsWith(".json");
    }

    // jarを1パスで走査する
    private static JarScan scanJar(Path jar) {
        Set<String> entries = new HashSet<String>();
        List<NestedScan> nested = new ArrayList<NestedScan>();
        String[] meta = null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry entry = en.nextElement();
                String name = entry.getName();
                if (isSearchableEntry(name)) {
                    entries.add(name);
                }
                if (name.equals("fabric.mod.json")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        meta = parseModJson(new String(readFully(in, 1 << 20), StandardCharsets.UTF_8));
                    }
                } else if (name.endsWith(".jar")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        scanNestedJar(in, name, nested);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new JarScan(meta, entries, nested);
    }

    private static void scanNestedJar(InputStream in, String entryName, List<NestedScan> out) throws IOException {
        // closeすると親ストリームまで閉じるため閉じない
        ZipInputStream zin = new ZipInputStream(in);
        Set<String> entries = new HashSet<String>();
        String[] meta = null;
        for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
            String name = entry.getName();
            if (isSearchableEntry(name)) {
                entries.add(name);
            }
            if (name.equals("fabric.mod.json")) {
                meta = parseModJson(new String(readFully(zin, 1 << 20), StandardCharsets.UTF_8));
            } else if (name.endsWith(".jar")) {
                scanNestedJar(zin, name, out);
            }
        }
        String label = entryName;
        int slash = label.lastIndexOf('/');
        if (slash >= 0) {
            label = label.substring(slash + 1);
        }
        out.add(new NestedScan(meta, entries, label));
    }

    // jar直下のfabric.mod.jsonを読む
    private static String[] readMeta(ZipFile zip) {
        try {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parseModJson(new String(readFully(in, 1 << 20), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] readJarMeta(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return readMeta(zip);
        } catch (Exception e) {
            return null;
        }
    }

    // {id, name} を抽出する
    private static String[] parseModJson(String json) {
        String[] meta = new String[2];
        Matcher id = JSON_ID.matcher(json);
        if (id.find()) {
            meta[0] = id.group(1);
        }
        Matcher name = JSON_NAME.matcher(json);
        if (name.find()) {
            meta[1] = name.group(1);
        }
        return meta;
    }

    private static byte[] readFully(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1 && out.size() < limit; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ファイル末尾のlimitバイトを読む
    private static String readTail(Path file, int limit) {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            long start = Math.max(0, size - limit);
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
            while (buf.hasRemaining() && ch.read(buf) != -1) {
            }
            String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                text = nl >= 0 ? text.substring(nl + 1) : "";
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    // 指定時刻以降に更新されたファイルのうち最新のものを返す
    private static Path newestFileSince(Path dir, long sinceMillis) {
        // ゲーム側との時計誤差の余裕を持たせる
        long threshold = sinceMillis - 5000;
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            Path newest = null;
            long newestTime = 0;
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(p).toMillis();
                if (modified >= threshold && modified > newestTime) {
                    newest = p;
                    newestTime = modified;
                }
            }
            return newest;
        } catch (Exception e) {
            return null;
        }
    }
}
