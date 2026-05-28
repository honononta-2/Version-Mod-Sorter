package vms;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code mods/fabric/<MCバージョン>/} と {@code mods/fabric/} 直下のjarを
 * Fabric Loader公式の {@code fabric.addMods} 経由で読み込ませる
 *
 * <p>{@code mods/fabric/} 直下のjarは全MCバージョン共通の共有MODとして扱う。
 * {@code fabric.addMods} のディレクトリ渡しは深さ無制限で走査するため、
 * 直下を渡すとMCバージョン別フォルダのjarまで巻き込んでしまう。
 * これを避けるため共有MODは個別のjarファイルとして列挙する
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

    private static Path logFile;

    private static void init() {
        if (System.getProperty(RELAUNCH_FLAG) != null) {
            return;
        }

        FabricLoader loader = FabricLoader.getInstance();
        logFile = loader.getGameDir().resolve("logs").resolve("version-mod-sorter.log");

        String mcVersion = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        if (mcVersion == null) {
            log("Minecraftのバージョンを特定できなかったため、読み込み先の追加を行いません");
            return;
        }

        Path modsDir = loader.getGameDir().resolve("mods").resolve("fabric");
        Path versionDir = modsDir.resolve(mcVersion);

        // 新バージョンでもMODの置き場が用意されるよう、無ければ作る
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

        String mainClass = System.getProperty("sun.java.command").split(" ")[0];

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

        String java = System.getProperty("java.home") + "/bin/java";
        if (osName.contains("win")) {
            java = java.replace('/', '\\') + ".exe";
        }

        String cp = System.getProperty("java.class.path");

        if (mainClass.equals("org.multimc.EntryPoint")) {
            try {
                Class.forName("net.fabricmc.loader.launch.knot.KnotClient");
                mainClass = "net.fabricmc.loader.launch.knot.KnotClient";
            } catch (ClassNotFoundException e) {
                mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient";
            }
        }

        String[] gameArgs = loader.getLaunchArguments(false);
        String addMods = String.join(File.pathSeparator, extraPaths);

        List<String> command = new ArrayList<>();
        command.add(java);
        // getInputArguments() は引数を要素ごとに返す
        // 値に空白を含むもの（-DFabricMcEmu= net.minecraft.client.main.Main など）があるため、
        // 連結して分割し直さず要素のまま渡す
        for (String arg : inputArgs) {
            if (arg.contains("-agentlib") || arg.contains("-javaagent")) {
                continue;
            }
            command.add(arg);
        }
        // macOSのGLFWはプロセス最初のスレッドを要求する
        if (osName.contains("mac") && !inputArgs.contains("-XstartOnFirstThread")) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-D" + RELAUNCH_FLAG + "=true");
        command.add("-Dfabric.addMods=" + addMods);
        command.add("-cp");
        command.add(cp);
        command.add(mainClass);
        command.addAll(Arrays.asList(gameArgs));

        Process process = new ProcessBuilder(command).inheritIO().start();
        System.exit(process.waitFor());
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
