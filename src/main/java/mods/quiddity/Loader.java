package mods.quiddity;

import com.google.common.collect.ImmutableMap;
import javassist.*;
import mods.quiddity.world.WorldServerProxy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Loader {

    private final boolean isDedicatedServer, hasInternalServer;

    private static Loader instance = null;
    private static String minecraftFile;

    private volatile boolean running = true;
    private final Map<ServerHandler, Boolean> serverInstances = new HashMap<ServerHandler, Boolean>();

    private final javassist.Loader poolLoader;
    private final ClassPool pool;

    public static Loader getInstance() {
        if (instance == null) {
            instance = new Loader(minecraftFile);
        }
        return instance;
    }

    private Loader(String mcFile) {
        instance = this;

        Set<URL> classpath = new HashSet<URL>();
        try {
            classpath.add(new File(mcFile).toURI().toURL());
            File mcDir;

            /**
             * Ugly LWJGL natives + other class path library loading code
             * If we decide to make a full launcher this can be much cleaner and simpler.
             * TODO: See if there is a better way to do this. JSON pack configs like the standard MC launcher?
             */
            if (SystemUtils.IS_OS_WINDOWS) {
                mcDir = new File(System.getenv("APPDATA") + File.separator + ".minecraft" + File.separator + "libraries");
            } else if (SystemUtils.IS_OS_MAC_OSX) {
                mcDir = new File(System.getProperty("user.home") + File.separator + "Library" + File.separator +
                        "Application Support" + File.separator + "minecraft" + File.separator +"libraries");
            } else {
                mcDir = new File(System.getProperty("user.home") + File.separator + ".minecraft" + File.separator + "libraries");
            }
            if (mcDir.exists() && mcDir.isDirectory()) {
                Iterator<File> fileIterator = FileUtils.iterateFiles(mcDir, new String[]{"jar", "jnilib", "so", "dll", "dylib"}, true);
                while (fileIterator.hasNext()) {
                    File f = fileIterator.next();
                    if (f.getName().endsWith(".so") || f.getName().endsWith(".dll") || f.getName().endsWith(".dylib") || f.getName().endsWith(".jnilib")) {
                        if (!classpath.contains(f.getParentFile().toURI().toURL()))
                            classpath.add(f.getParentFile().toURI().toURL());
                    } else {
                        if (f.getName().startsWith("lwjgl-platform")) {
                            ZipFile zipFile = new ZipFile(f);
                            Enumeration<? extends ZipEntry> entries = zipFile.entries();
                            while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                File entryDestination = new File(f.getParentFile(), entry.getName());
                                //noinspection ResultOfMethodCallIgnored
                                entryDestination.getParentFile().mkdirs();
                                if (entry.isDirectory())
                                    //noinspection ResultOfMethodCallIgnored
                                    entryDestination.mkdirs();
                                else {
                                    InputStream in = zipFile.getInputStream(entry);
                                    OutputStream out = new FileOutputStream(entryDestination);
                                    IOUtils.copy(in, out);
                                    IOUtils.closeQuietly(in);
                                    IOUtils.closeQuietly(out);
                                }
                            }
                            classpath.add(f.getParentFile().toURI().toURL());
                        } else {
                            classpath.add(f.toURI().toURL());
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        String paths = System.getProperty("java.library.path");

        for (URL url : classpath) {
            if (paths == null || paths.isEmpty())
                paths = url.getPath();
            else
                paths += File.pathSeparator + url.getPath();
        }

        System.setProperty("java.library.path", paths);

        /**
         * Hack the ClassLoader now.
         */
        try {
            final Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set(null, null);
        } catch(Throwable ignored) {}

        ClassLoader loader = new URLClassLoader(classpath.toArray(new URL[classpath.size()]), Loader.class.getClassLoader());

        pool = ClassPool.getDefault();
        pool.insertClassPath(new LoaderClassPath(loader));
        poolLoader = new javassist.Loader(loader, pool);
        poolLoader.delegateLoadingOf("mods.quiddity.");

        isDedicatedServer = poolLoader.getResource("pack.png") == null;
        hasInternalServer = pool.getOrNull("net.minecraft.server.MinecraftServer") != null && !isDedicatedServer;
    }

    public void doLoading() {
        if (!isDedicatedServer) {
            if (!ClientInstance.doTransform()) {
                throw new AssertionError("Unable to transform the Client. This is fatal.");
            }
        }
        if (isDedicatedServer || hasInternalServer) {
            if (!ServerHandler.doTransform()) {
                throw new AssertionError("Unable to transform the " +
                        (isDedicatedServer ? "Server" : "Internal Server") + ". This is fatal.");
            }

            if (!WorldServerProxy.doTransform()) {
                throw new AssertionError("Unable to transform the World Server. This is fatal.");
            }
        }
    }

    public void finishLoading() {
        if (!isDedicatedServer) {
            ClientInstance.transformsDone();
        }
        if (isDedicatedServer || hasInternalServer) {
            ServerHandler.transformsDone();
        }
    }

    public void go() {
        if (!isDedicatedServer) {
            ClientInstance.startGame();
        }
    }

    public ClassPool getPool() {
        return pool;
    }

    public javassist.Loader getClassLoader() {
        return poolLoader;
    }

    public boolean isDedicatedServer() {
        return isDedicatedServer;
    }

    public boolean hasInternalServer() {
        return hasInternalServer;
    }

    public boolean isRunning() {
        return running;
    }

    public void addServerHandler(ServerHandler handler) {
        synchronized (serverInstances) {
            serverInstances.put(handler, hasInternalServer());
        }
    }

    public ImmutableMap<ServerHandler, Boolean> getServerHandlers() {
        synchronized (serverInstances) {
            return ImmutableMap.copyOf(serverInstances);
        }
    }

    public static void main(String[] args) {
        if (args.length <= 0)
            return;
        minecraftFile = args[0];
        try {
            Loader.getInstance().doLoading();
            Loader.getInstance().finishLoading();
            Loader.getInstance().go();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        synchronized (Loader.getInstance()) {
            Loader.getInstance().running = false;
        }
    }
}