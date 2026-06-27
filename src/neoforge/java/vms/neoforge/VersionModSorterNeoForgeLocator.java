package vms.neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code mods/neoforge/<MCバージョン>/} と {@code mods/neoforge/} 直下を
 * NeoForge公式の {@link IModFileCandidateLocator} として探索に追加する
 *
 * <p>{@code mods/neoforge/} 直下のjarは全MCバージョン共通の共有MODとして扱う。
 * {@code IModFileCandidateLocator.forFolder} は直下のみを走査するため、共有MOD用の
 * ロケータをルートに対して1つだけ生成すれば、配下のバージョン別フォルダは巻き込まれない
 *
 * <p>探索前に読み込まれるよう {@code META-INF/services} で登録する
 *
 * <p>{@code ILaunchContext} のバージョン・ゲームディレクトリ取得メソッドはNeoForgeのバージョンで変わるため、
 * 新しい系のメソッドを試し、無ければ {@code FMLLoader}・{@code FMLPaths} へフォールバックする
 */
public class VersionModSorterNeoForgeLocator implements IModFileCandidateLocator {

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        // 想定外のNeoForgeバージョンでもNeoForgeを巻き込まないよう、失敗時は何もしない
        Path gameDir = null;
        try {
            String mcVersion = mcVersion(context);
            gameDir = gameDir(context);
            if (mcVersion == null || gameDir == null) {
                log(gameDir, "Could not determine the MC version or game directory; skipping mod folder setup");
                return;
            }

            Path modsDir = gameDir.resolve("mods").resolve("neoforge");
            Path versionDir = modsDir.resolve(mcVersion);
            // 新バージョンでもMODの置き場が用意されるようにする
            Files.createDirectories(versionDir);

            if (Files.isDirectory(modsDir)) {
                IModFileCandidateLocator.forFolder(modsDir.toFile(), "version-mod-sorter/shared")
                        .findCandidates(context, pipeline);
            }

            try (Stream<Path> paths = Files.walk(versionDir)) {
                for (Path dir : paths.filter(Files::isDirectory).collect(Collectors.toList())) {
                    IModFileCandidateLocator.forFolder(dir.toFile(), locatorName(versionDir, dir))
                            .findCandidates(context, pipeline);
                }
            }
        } catch (Throwable t) {
            log(gameDir, "Failed to add mod folders:\n" + stackTrace(t));
        }
    }

    private static void log(Path gameDir, String msg) {
        if (gameDir == null) {
            return;
        }
        try {
            Path logFile = gameDir.resolve("logs").resolve("version-mod-sorter.log");
            Files.createDirectories(logFile.getParent());
            Files.write(logFile, ("[VMS/NeoForge] " + msg + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public String toString() {
        return "version-mod-sorter";
    }

    private static String locatorName(Path root, Path dir) {
        if (dir.equals(root)) {
            return "version-mod-sorter";
        }
        return "version-mod-sorter/" + root.relativize(dir).toString().replace('\\', '/');
    }

    private static String mcVersion(ILaunchContext context) {
        try {
            return context.getVersions().mcVersion();
        } catch (Throwable ignore) {
            // getVersions を持たない古いNeoForge
        }
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
            return (String) versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Path gameDir(ILaunchContext context) {
        try {
            return context.gameDirectory();
        } catch (Throwable ignore) {
            // gameDirectory を持たない古いNeoForge
        }
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            for (Object constant : fmlPaths.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("GAMEDIR")) {
                    return (Path) fmlPaths.getMethod("get").invoke(constant);
                }
            }
            return null;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
