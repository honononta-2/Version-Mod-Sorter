package vms;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * {@code mods/fabric/<MCバージョン>/} と {@code mods/fabric/} 直下のjarを
 * Fabric Loader公式の {@code fabric.addMods} 経由で読み込ませる
 *
 * <p>{@code mods/fabric/} 直下のjarは全MCバージョン共通の共有MODとして扱う。
 * {@code fabric.addMods} のディレクトリ渡しは深さ無制限で走査するため、
 * 共有MODはディレクトリではなく個別のjarとして列挙する
 *
 * <p>言語アダプタとして登録するのは、MOD探索より前に静的初期化を走らせるための手段で、
 * オブジェクト生成用途では使わない
 *
 * <p>{@code fabric.addMods} は探索段階で読まれるため、
 * 同一JVMでは間に合わず、値を仕込んだ子プロセスとして起動し直す
 */
@SuppressWarnings("unused")
public class VersionModSorter implements LanguageAdapter {
    private static final String RELAUNCH_FLAG = "vms.relaunched";
    private static final String PARENT_PID_FLAG = "vms.parentPid";
    private static final String ADDMODS_FILE_FLAG = "vms.addModsFile";
    // fabric.addMods をコマンドラインへ直接渡せる上限
    private static final int ADDMODS_INLINE_LIMIT = 8000;

    private static Path logFile;

    private static void init() {
        FabricLoader loader = FabricLoader.getInstance();
        logFile = loader.getGameDir().resolve("logs").resolve("version-mod-sorter.log");

        if (System.getProperty(RELAUNCH_FLAG) != null) {
            deleteAddModsFile();
            startParentWatch();
            return;
        }

        String mcVersion = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        if (mcVersion == null) {
            log("Minecraftのバージョンを特定できなかったため、読み込み先の追加を行いません");
            return;
        }

        Path modsDir = loader.getGameDir().resolve("mods").resolve("fabric");
        Path versionDir = modsDir.resolve(mcVersion);

        // 新バージョンでもMODの置き場が用意されるようにする
        ensureDir(versionDir);

        // 空フォルダのための無駄な再起動を避ける
        List<String> extraPaths = new ArrayList<>();
        addIfHasMods(extraPaths, versionDir);
        addSharedJars(extraPaths, modsDir);

        if (extraPaths.isEmpty()) {
            return;
        }

        try {
            relaunch(loader, extraPaths);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            log("再起動に失敗しました:\n" + sw);
            throw new RuntimeException("Version Mod Sorter の再起動に失敗しました", t);
        }
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log("フォルダの作成に失敗: " + dir + " (" + e + ")");
        }
    }

    private static void addIfHasMods(List<String> list, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            boolean hasJar = paths.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (hasJar) {
                list.add(dir.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            log("フォルダの走査に失敗: " + dir + " (" + e + ")");
        }
    }

    private static void addSharedJars(List<String> list, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(p -> list.add(p.toAbsolutePath().toString()));
        } catch (Exception e) {
            log("フォルダの走査に失敗: " + dir + " (" + e + ")");
        }
    }

    private static void relaunch(FabricLoader loader, List<String> extraPaths) throws Exception {
        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        String javaCommand = System.getProperty("sun.java.command");
        if (javaCommand == null || javaCommand.isEmpty()) {
            log("sun.java.command を取得できないため、再起動を行いません");
            return;
        }

        String mainClass = javaCommand.split(" ")[0];
        String launchJar = findLaunchJar(javaCommand);

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

        String java = System.getProperty("java.home") + "/bin/java";
        if (osName.contains("win")) {
            java = java.replace('/', '\\') + ".exe";
        }

        String cp = System.getProperty("java.class.path");

        // 一部のランチャー経由の起動では、メインクラスをKnotClientに差し替える
        if (mainClass.equals("org.multimc.EntryPoint")
                || mainClass.equals("org.polymc.EntryPoint")
                || mainClass.equals("org.prismlauncher.EntryPoint")) {
            try {
                Class.forName("net.fabricmc.loader.launch.knot.KnotClient");
                mainClass = "net.fabricmc.loader.launch.knot.KnotClient";
            } catch (ClassNotFoundException e) {
                mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient";
            }
        }

        String[] gameArgs = loader.getLaunchArguments(false);

        List<String> allMods = new ArrayList<>();
        String existing = System.getProperty("fabric.addMods");
        if (existing != null && !existing.isEmpty()) {
            for (String p : existing.split(File.pathSeparator)) {
                if (!p.isEmpty()) {
                    allMods.add(p);
                }
            }
        }
        allMods.addAll(extraPaths);
        String addMods = buildAddModsArg(allMods);

        List<String> command = new ArrayList<>();
        command.add(java);
        // 値に空白を含む引数があるため、連結せず要素のまま引き継ぐ
        for (String arg : inputArgs) {
            if (arg.startsWith("-agentlib")) {
                continue;
            }
            // fabric.addMods はVMSの分を含めて組み直すので、元の指定は持ち込まない
            if (arg.startsWith("-Dfabric.addMods=")) {
                continue;
            }
            command.add(arg);
        }
        // macOSのGLFWはプロセス最初のスレッドを要求する
        if (osName.contains("mac") && !inputArgs.contains("-XstartOnFirstThread")) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-D" + RELAUNCH_FLAG + "=true");
        // 古いJVMのポーリング監視用に親PIDを渡す
        String pid = currentPid();
        if (pid != null) {
            command.add("-D" + PARENT_PID_FLAG + "=" + pid);
        }
        command.add("-Dfabric.addMods=" + addMods);
        // 子が削除できるよう、リストファイルのパスを渡す
        if (addMods.startsWith("@")) {
            command.add("-D" + ADDMODS_FILE_FLAG + "=" + addMods.substring(1));
        }
        if (launchJar != null) {
            command.add("-jar");
            command.add(launchJar);
        } else {
            command.add("-cp");
            command.add(cp);
            command.add(mainClass);
        }
        command.addAll(Arrays.asList(gameArgs));

        Process process = new ProcessBuilder(command).inheritIO().start();
        System.exit(process.waitFor());
    }

    // 親プロセスの終了を検知して、この子プロセスを終了させる
    private static void startParentWatch() {
        // 新しめのJVMは ProcessHandle で親を直接監視する（OS非依存）
        if (watchParentViaProcessHandle()) {
            return;
        }
        // 古いJVMは親PIDのポーリングで監視する
        watchParentViaPolling(System.getProperty(PARENT_PID_FLAG));
    }

    private static boolean watchParentViaProcessHandle() {
        try {
            Class<?> phClass = Class.forName("java.lang.ProcessHandle");
            Object current = phClass.getMethod("current").invoke(null);
            Optional<?> parent = (Optional<?>) phClass.getMethod("parent").invoke(current);
            if (!parent.isPresent()) {
                return false;
            }
            CompletableFuture<?> onExit = (CompletableFuture<?>) phClass.getMethod("onExit").invoke(parent.get());
            onExit.thenRun(() -> Runtime.getRuntime().halt(0));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void watchParentViaPolling(String parentPid) {
        if (parentPid == null || parentPid.isEmpty()) {
            return;
        }
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (!isProcessAlive(parentPid)) {
                    Runtime.getRuntime().halt(0);
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "vms-parent-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    // 判定できないときは生存扱いとし、誤って終了させない
    private static boolean isProcessAlive(String pid) {
        try {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/NH", "/FO", "CSV")
                    : new ProcessBuilder("kill", "-0", pid);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            if (windows) {
                // tasklistは常に成功するため、CSVのPID列に該当PIDが現れるかで判断する
                return out.toString().contains("\"" + pid + "\"");
            }
            return code == 0;
        } catch (Exception e) {
            return true;
        }
    }

    private static String currentPid() {
        try {
            Class<?> phClass = Class.forName("java.lang.ProcessHandle");
            Object current = phClass.getMethod("current").invoke(null);
            long pid = (long) phClass.getMethod("pid").invoke(current);
            return Long.toString(pid);
        } catch (Throwable ignore) {
            // ProcessHandle の無い古いJVM
        }
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) {
                return name.substring(0, at);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // -jar 起動のjarパスを起動コマンドから探す
    private static String findLaunchJar(String javaCommand) {
        String[] tokens = javaCommand.split(" ");
        StringBuilder candidate = new StringBuilder();
        for (String token : tokens) {
            if (candidate.length() > 0) {
                candidate.append(' ');
            }
            candidate.append(token);
            String path = candidate.toString();
            if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            try {
                if (Files.isRegularFile(Paths.get(path))) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // 読み込み先を fabric.addMods へ渡す引数に組み立てる
    private static String buildAddModsArg(List<String> paths) {
        String joined = String.join(File.pathSeparator, paths);
        if (joined.length() <= ADDMODS_INLINE_LIMIT) {
            return joined;
        }
        try {
            File tmp = File.createTempFile("vms-addmods", ".txt");
            tmp.deleteOnExit();
            Path listFile = tmp.toPath();
            Files.write(listFile, paths, StandardCharsets.UTF_8);
            return "@" + listFile.toAbsolutePath();
        } catch (Exception e) {
            log("addModsリストファイルの作成に失敗したため、パスを直接指定します: " + e);
            return joined;
        }
    }

    private static void deleteAddModsFile() {
        String path = System.getProperty(ADDMODS_FILE_FLAG);
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception ignored) {
        }
    }

    private static void log(String msg) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile, ("[VMS] " + msg + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException("Version Mod Sorter はオブジェクト生成用の言語アダプタではありません");
    }

    static {
        init();
    }
}
