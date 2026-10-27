package vms.neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

// mods/ 配下のサブフォルダを NeoForge公式の IModFileCandidateLocator として探索に追加する
//
// 読み込むかどうかはパス上の各フォルダ名で決まる
// IModFileCandidateLocator.forFolder は直下のみを走査するため、読み込むフォルダごとにロケータを生成する
//
// 探索前に読み込まれるよう META-INF/services で登録する
//
// ILaunchContext のバージョン・ゲームディレクトリ取得メソッドはNeoForgeのバージョンで変わるため、
// 新しい系のメソッドを試し、無ければ FMLLoader・FMLPaths へフォールバックする
public class VersionModSorterNeoForgeLocator implements IModFileCandidateLocator {

    private static final String LOADER = "neoforge";
    private static final String[] LOADERS = {"fabric", "forge", "neoforge"};
    private static final String DISABLED_DIR = "Disabled.Mod";
    private static final String OWN_CLASS_ENTRY = "vms/neoforge/VersionModSorterNeoForgeLocator.class";

    // NeoForgeのmodsフォルダ走査より先に実行させる
    @Override
    public int getPriority() {
        return 100;
    }

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

            Path modsDir = gameDir.resolve("mods");
            // 新バージョンでもMODの置き場と除外フォルダが用意されるようにする
            Files.createDirectories(modsDir.resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(LOADER).resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(mcVersion).resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(LOADER).resolve(mcVersion).resolve(DISABLED_DIR));

            // NeoForgeが走査するパスと同じ形に揃える
            markOwnJarsLocated(context, modsDir.toAbsolutePath().normalize(), gameDir);

            for (Path dir : modDirectories(modsDir, mcVersion, gameDir)) {
                markOwnJarsLocated(context, dir, gameDir);
                IModFileCandidateLocator.forFolder(dir.toFile(), locatorName(modsDir, dir))
                        .findCandidates(context, pipeline);
            }
        } catch (Throwable t) {
            log(gameDir, "Failed to add mod folders:\n" + stackTrace(t));
        }
    }

    // VMSのjarをMOD候補から除く
    private static void markOwnJarsLocated(ILaunchContext context, Path dir, Path gameDir) {
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path jar : paths.filter(VersionModSorterNeoForgeLocator::isOwnJar).collect(Collectors.toList())) {
                context.addLocated(jar);
            }
        } catch (Throwable t) {
            log(gameDir, "Failed to exclude Version Mod Sorter jars in " + dir + ":\n" + stackTrace(t));
        }
    }

    private static boolean isOwnJar(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return zip.getEntry(OWN_CLASS_ENTRY) != null;
        } catch (Exception e) {
            return false;
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

    // mods/ 直下はNeoForge本体が読むため含めない
    private static List<Path> modDirectories(Path root, String mcVersion, Path gameDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!isLoadableDir(dir.getFileName().toString(), mcVersion)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dirs.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log(gameDir, "Failed to scan: " + file + " (" + exc + ")");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    log(gameDir, "Failed to scan folder: " + dir + " (" + exc + ")");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return dirs;
    }

    // ローダー名のフォルダは起動中のローダー、`.` を含むフォルダは起動中のMCバージョンに一致するときだけ読む
    private static boolean isLoadableDir(String name, String mcVersion) {
        for (String loaderName : LOADERS) {
            if (loaderName.equalsIgnoreCase(name)) {
                return loaderName.equals(LOADER);
            }
        }
        if (name.indexOf('.') >= 0) {
            return name.equals(mcVersion);
        }
        return true;
    }

    private static String locatorName(Path root, Path dir) {
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
