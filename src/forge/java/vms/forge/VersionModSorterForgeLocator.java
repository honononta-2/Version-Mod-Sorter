package vms.forge;

import net.minecraftforge.forgespi.locating.IModDirectoryLocatorFactory;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.IModLocator.ModFileOrException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code mods/forge/<MCバージョン>/} を Forge公式の {@link IModLocator} として探索に追加する
 *
 * <p>探索前に読み込まれるよう {@code META-INF/services} で登録する
 *
 * <p>取得に使う {@code FMLLoader}・{@code FMLPaths}・modlauncherの {@code Launcher} はForge 26.xでは
 * Java 25向けで、JDK17ビルドから直接参照できないためリフレクションで呼ぶ
 */
public class VersionModSorterForgeLocator implements IModLocator {

    @Override
    public List<ModFileOrException> scanMods() {
        try {
            String mcVersion = mcVersion();
            Path gameDir = gameDir();
            if (mcVersion == null || gameDir == null) {
                return Collections.emptyList();
            }

            Path versionDir = gameDir.resolve("mods").resolve("forge").resolve(mcVersion);
            // 新バージョンでもMODの置き場が用意されるよう、無ければ作る
            Files.createDirectories(versionDir);

            IModDirectoryLocatorFactory factory = directoryLocatorFactory();
            if (factory == null) {
                return Collections.emptyList();
            }
            return factory.build(versionDir, name()).scanMods();
        } catch (Throwable t) {
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
