package vms.forge;

import net.minecraftforge.forgespi.locating.IModDirectoryLocatorFactory;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.IModLocator.ModFileOrException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

// mods/ 配下のサブフォルダを Forge公式の IModLocator として探索に追加する
//
// 読み込むかどうかはパス上の各フォルダ名で決まる
// IModDirectoryLocatorFactory.build は直下のみを走査するため、読み込むフォルダごとにロケータを生成する
//
// 探索前に読み込まれるよう META-INF/services で登録する
//
// 取得に使う FMLLoader・FMLPaths・modlauncherの Launcher はForge 26.xでは
// Java 25向けで、JDK17ビルドから直接参照できないためリフレクションで呼ぶ
public class VersionModSorterForgeLocator implements IModLocator {

    private static final String LOADER = "forge";
    private static final String[] LOADERS = {"fabric", "forge", "neoforge"};
    private static final String DISABLED_DIR = "Disabled.Mod";
    private static final String OWN_CLASS_ENTRY = "vms/forge/VersionModSorterForgeLocator.class";

    @Override
    public List<ModFileOrException> scanMods() {
        Path gameDir = null;
        try {
            String mcVersion = mcVersion();
            gameDir = gameDir();
            if (mcVersion == null || gameDir == null) {
                log(gameDir, "Could not determine the MC version or game directory; skipping mod folder setup");
                return Collections.emptyList();
            }

            Path modsDir = gameDir.resolve("mods");
            // 新バージョンでもMODの置き場と除外フォルダが用意されるようにする
            Files.createDirectories(modsDir.resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(LOADER).resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(mcVersion).resolve(DISABLED_DIR));
            Files.createDirectories(modsDir.resolve(LOADER).resolve(mcVersion).resolve(DISABLED_DIR));

            IModDirectoryLocatorFactory factory = directoryLocatorFactory();
            if (factory == null) {
                log(gameDir, "Could not obtain the directory locator factory; skipping mod folder setup");
                return Collections.emptyList();
            }

            List<ModFileOrException> result = new ArrayList<>();
            for (Path dir : modDirectories(modsDir, mcVersion, gameDir)) {
                result.addAll(scanWithoutOwnJars(factory, dir, locatorName(modsDir, dir)));
            }
            return result;
        } catch (Throwable t) {
            log(gameDir, "Failed to add mod folders:\n" + stackTrace(t));
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "version-mod-sorter";
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
        // 生成元は委譲先ロケータのため、こちらへは渡らない
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    @Override
    public boolean isValid(IModFile modFile) {
        return false;
    }

    // VMSのjarを走査結果から除く
    private static List<ModFileOrException> scanWithoutOwnJars(IModDirectoryLocatorFactory factory, Path dir,
            String locatorName) {
        IModLocator locator = factory.build(dir, locatorName);
        List<ModFileOrException> scanned = locator.scanMods();

        List<Path> candidates;
        try {
            Method scanCandidates = locator.getClass().getMethod("scanCandidates");
            try (Stream<?> paths = (Stream<?>) scanCandidates.invoke(locator)) {
                candidates = paths.map(p -> (Path) p).collect(Collectors.toList());
            }
        } catch (Throwable t) {
            return scanned;
        }
        if (candidates.size() != scanned.size()) {
            return scanned;
        }

        List<ModFileOrException> result = new ArrayList<>();
        for (int i = 0; i < scanned.size(); i++) {
            if (!isOwnJar(candidates.get(i))) {
                result.add(scanned.get(i));
            }
        }
        return result;
    }

    private static boolean isOwnJar(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return zip.getEntry(OWN_CLASS_ENTRY) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // mods/ 直下はForge本体が読むため含めない
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

    private String locatorName(Path root, Path dir) {
        return name() + "/" + root.relativize(dir).toString().replace('\\', '/');
    }

    private static void log(Path gameDir, String msg) {
        if (gameDir == null) {
            return;
        }
        try {
            Path logFile = gameDir.resolve("logs").resolve("version-mod-sorter.log");
            Files.createDirectories(logFile.getParent());
            Files.write(logFile, ("[VMS/Forge] " + msg + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String mcVersion() throws Exception {
        Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
        Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
        return (String) versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
    }

    private static Path gameDir() throws Exception {
        Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
        Object gamedir = null;
        for (Object constant : fmlPaths.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("GAMEDIR")) {
                gamedir = constant;
                break;
            }
        }
        if (gamedir == null) {
            return null;
        }
        return (Path) fmlPaths.getMethod("get").invoke(gamedir);
    }

    private static IModDirectoryLocatorFactory directoryLocatorFactory() throws Exception {
        Class<?> keys = Class.forName("net.minecraftforge.forgespi.Environment$Keys");
        Supplier<?> keySupplier = (Supplier<?>) keys.getField("MODDIRECTORYFACTORY").get(null);
        Object key = keySupplier.get();

        Class<?> launcher = Class.forName("cpw.mods.modlauncher.Launcher");
        Object instance = launcher.getField("INSTANCE").get(null);
        Object environment = launcher.getMethod("environment").invoke(instance);

        Class<?> keyType = Class.forName("cpw.mods.modlauncher.api.TypesafeMap$Key");
        Object property = environment.getClass().getMethod("getProperty", keyType).invoke(environment, key);
        return (IModDirectoryLocatorFactory) ((Optional<?>) property).orElse(null);
    }
}
