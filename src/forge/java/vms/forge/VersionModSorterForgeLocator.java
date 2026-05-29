package vms.forge;

import net.minecraftforge.forgespi.locating.IModDirectoryLocatorFactory;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.IModLocator.ModFileOrException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code mods/forge/<MCバージョン>/} と {@code mods/forge/} 直下を Forge公式の {@link IModLocator}
 * として探索に追加する
 *
 * <p>{@code mods/forge/} 直下のjarは全MCバージョン共通の共有MODとして扱う。
 * {@code IModDirectoryLocatorFactory.build} は直下のみを走査するため、共有MOD用の
 * ロケータをルートに対して1つだけ生成すれば、配下のバージョン別フォルダは巻き込まれない
 *
 * <p>探索前に読み込まれるよう {@code META-INF/services} で登録する
 *
 * <p>取得に使う {@code FMLLoader}・{@code FMLPaths}・modlauncherの {@code Launcher} はForge 26.xでは
 * Java 25向けで、JDK17ビルドから直接参照できないためリフレクションで呼ぶ
 */
public class VersionModSorterForgeLocator implements IModLocator {

    @Override
    public List<ModFileOrException> scanMods() {
        Path gameDir = null;
        try {
            String mcVersion = mcVersion();
            gameDir = gameDir();
            if (mcVersion == null || gameDir == null) {
                log(gameDir, "MCバージョンまたはゲームディレクトリを特定できず、読み込み先を追加しません");
                return Collections.emptyList();
            }

            Path modsDir = gameDir.resolve("mods").resolve("forge");
            Path versionDir = modsDir.resolve(mcVersion);
            // 新バージョンでもMODの置き場が用意されるよう、無ければ作る
            Files.createDirectories(versionDir);

            IModDirectoryLocatorFactory factory = directoryLocatorFactory();
            if (factory == null) {
                log(gameDir, "ディレクトリロケータのファクトリを取得できず、読み込み先を追加しません");
                return Collections.emptyList();
            }

            List<ModFileOrException> result = new ArrayList<>();
            if (Files.isDirectory(modsDir)) {
                result.addAll(factory.build(modsDir, name() + "/shared").scanMods());
            }
            for (Path dir : modDirectories(versionDir)) {
                result.addAll(factory.build(dir, locatorName(versionDir, dir)).scanMods());
            }
            return result;
        } catch (Throwable t) {
            log(gameDir, "読み込み先の追加に失敗しました:\n" + stackTrace(t));
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

    private static List<Path> modDirectories(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    private String locatorName(Path root, Path dir) {
        if (dir.equals(root)) {
            return name();
        }
        return name() + "/" + root.relativize(dir).toString().replace('\\', '/');
    }

    // メッセージをログファイルに残す
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
