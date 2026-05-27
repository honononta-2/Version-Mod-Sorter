package vms.neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code mods/neoforge/<MCバージョン>/} を NeoForge公式の {@link IModFileCandidateLocator} として探索に追加する
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
        try {
            String mcVersion = mcVersion(context);
            Path gameDir = gameDir(context);
            if (mcVersion == null || gameDir == null) {
                return;
            }

            Path versionDir = gameDir.resolve("mods").resolve("neoforge").resolve(mcVersion);
            // 新バージョンでもMODの置き場が用意されるよう、無ければ作る
            Files.createDirectories(versionDir);

            IModFileCandidateLocator.forFolder(versionDir.toFile(), "version-mod-sorter")
                    .findCandidates(context, pipeline);
        } catch (Throwable t) {
            // 何もしない
        }
    }

    @Override
    public String toString() {
        return "version-mod-sorter";
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
