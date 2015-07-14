package mods.quiddity;

import javassist.*;
import javassist.bytecode.*;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class ClientInstance implements Runnable, Callable<Void> {
    private final Object minecraftObject;

    public ClientInstance(Object minecraftClientObject) {
        this.minecraftObject = minecraftClientObject;
        if (addFutureTaskMethod != null) {
            /**
             * We need to be on our own thread.
             * The method we call is meant to delegate code blocks back to the main thread.
             * Because of that, Minecraft checks if the caller thread is the same as the main thread
             * and executes it immediately.
             */
            Thread reduxThread = new Thread(this, "Loader Thread");
            reduxThread.start();
        }
    }

    @Override
    public void run() {
        synchronized (Loader.getInstance()) {
            while (Loader.getInstance().isRunning()) {
                try {
                    ((Future) addFutureTaskMethod.invoke(getMinecraftInstance(), (Callable)this)).get();
                    // This is lock stepped with the tick rate now
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    @Override
    public Void call() throws Exception {
        return null;
    }

    public Object getMinecraftInstance() throws InvocationTargetException, IllegalAccessException {
        return minecraftObject;
    }

    /**
     * Internal static code for hooking into the client
     */

    private static final String minecraftClassName;

    /**
     * Classes
     */
    private static Class<?> minecraftClientClass;

    /**
     * Methods
     */
    private static Method addFutureTaskMethod;
    private static Method getMinecraftInstanceMethod;

    /**
     * Fields
     */
    private static Field staticMinecraftField;

    static {
        if (!Loader.getInstance().isDedicatedServer()) {
            String mcClientClass;
            try {
                mcClientClass = tryFindMainMcClassViaRealms();
                if (mcClientClass == null || mcClientClass.isEmpty()) {
                    mcClientClass = tryFindMainMcClassViaMain();
                }
                if (mcClientClass == null || mcClientClass.isEmpty()) {
                    mcClientClass = tryFindPlainMCClass();
                }
                minecraftClassName = mcClientClass;
            } catch (Exception e) {
                throw new AssertionError("Unable to find the Minecraft client class!\nThis is fatal.", e);
            }
        } else {
            minecraftClassName = StringUtils.EMPTY;
        }
    }

    public static boolean doTransform() {
        CtClass minecraftClass = Loader.getInstance().getPool().getOrNull(minecraftClassName);
        try {
            minecraftClass.addField(CtField.make("private mods.quiddity.ClientInstance quiddityClientHandler = null;", minecraftClass));
        } catch (CannotCompileException e) {
            System.err.println("Unable to add field to Minecraft client class.");
            e.printStackTrace();
            return false;
        }

        try {
            for (CtClass ctClass : minecraftClass.getInterfaces()) {
                if (ctClass.getName().equals(Runnable.class.getName())) {
                    /**
                     * If the main Minecraft client class implements runnable
                     * the only place to inject is via the game loop as of now
                     */
                    return tryHookIntoGameLoop(minecraftClass);
                }
            }
        } catch (NotFoundException e) {
            return false;
        }
        return tryHookViaCallable(minecraftClass);
    }

    public static void transformsDone() {
        try {
            minecraftClientClass = Loader.getInstance().getClassLoader().loadClass(minecraftClassName);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Unable to load the transformed Minecraft client class!\nThis is fatal.", e);
        }

        /**
         * Try to get the static Minecraft singleton accessor method
         */
        for (Method m : minecraftClientClass.getDeclaredMethods()) {
            if (minecraftClientClass.isAssignableFrom(m.getReturnType())) {
                getMinecraftInstanceMethod = m;
            }
        }

        /**
         * Older versions of Minecraft didn't have a static singleton accessor function
         */
        if (getMinecraftInstanceMethod == null) {
            for (Field f : minecraftClientClass.getDeclaredFields()) {
                if (minecraftClientClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    staticMinecraftField = f;
                }
            }
        }

        if (getMinecraftInstanceMethod == null && staticMinecraftField == null)
            throw new AssertionError("Could not get a pointer to the Minecraft singleton!\nThis is fatal.");
    }

    public static void startGame() {
        try {
            Method mainMethod = null;
            if (Loader.getInstance().getPool().getOrNull("net.minecraft.client.main.Main") != null) {
                Class<?> mainMCClass = Loader.getInstance().getClassLoader().loadClass("net.minecraft.client.main.Main");
                mainMethod = mainMCClass.getDeclaredMethod("main", String[].class);
            }
            if (mainMethod == null && minecraftClientClass != null && minecraftClientClass.getDeclaredMethod("main", String[].class) != null) {
                mainMethod = minecraftClientClass.getDeclaredMethod("main", String[].class);
            }
            if (mainMethod != null) {
                mainMethod.invoke(null, (Object) new String[]{"--demo", "--userProperties", "{}", "--accessToken", "FML", "--version", "1.7.10"});
            } else {
                throw new AssertionError("Unable to find the main method to start Minecraft!");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not start Minecraft!\nThis is fatal.", e);
        }
    }

    /**
     * <b>DO NOT CALL</b>
     * <i>This is a internal callback method to setup the loader!</i>
     *
     * Callback from the Minecraft client constructor to start the callback loop.
     * The callback loop gets called every tick. Only gets called when Minecraft
     * has a method to delegate a Callable block back to the main thread.
     */
    @SuppressWarnings("unused") // Suppress unused warning, it get used in injected code
    public static void callableInit() {
        for (Method m : minecraftClientClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].getName().equals(Callable.class.getName())) {
                addFutureTaskMethod = m;
            }
        }
    }

    private static boolean tryHookViaCallable(CtClass minecraftClass) {
        for (CtConstructor constructor : minecraftClass.getDeclaredConstructors()) {
            try {
                if (constructor.getParameterTypes().length >= 1) {
                    minecraftClass.getDeclaredConstructors()[0].insertAfter(
                                    "mods.quiddity.ClientInstance.callableInit();" +
                                    "$0.quiddityClientHandler = new mods.quiddity.ClientInstance($0);", false);
                    return true;
                }
            } catch (Exception e) {
                System.err.println("Quiddity Loader: Failed to inject into client via callable blocks.");
                e.printStackTrace();
            }
        }
        return false;
    }

    private static boolean tryHookIntoGameLoop(CtClass minecraftClass) {
        try {
            CtMethod runMethod = minecraftClass.getDeclaredMethod("run");
            CodeAttribute ca = runMethod.getMethodInfo().getCodeAttribute();
                /* Code adapted from StackOverflow Answer
                 * http://stackoverflow.com/a/2102552
                 */
            for (int i = 0; i < ca.getExceptionTable().size(); i++) {
                int constPoolIndex = ca.getExceptionTable().catchType(i);
                if (runMethod.getMethodInfo().getConstPool().getClassInfo(constPoolIndex).equals(OutOfMemoryError.class.getName())) {
                    int startPc = ca.getExceptionTable().startPc(i);
                    int startLine = ((LineNumberAttribute)ca.getAttribute(LineNumberAttribute.tag)).toLineNumber(startPc);
                    runMethod.insertAt(startLine,
                                "if (quiddityClientHandler == null) {" +
                                    "quiddityClientHandler = new mods.quiddity.ClientInstance($0);" +
                                "}" +
                                "quiddityClientHandler.run();");
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("Quiddity Loader: Failed to inject into client via main game loop.");
        }
        return false;
    }

    private static String tryFindMainMcClassViaRealms() throws NotFoundException, CannotCompileException {
        CtClass realmsScreen = Loader.getInstance().getPool().getOrNull("net.minecraft.realms.RealmsScreen");
        if (realmsScreen == null)
            return StringUtils.EMPTY;
        return realmsScreen.getDeclaredField("minecraft").getType().getName();
    }

    private static String tryFindMainMcClassViaMain() throws NotFoundException, BadBytecode {
        CtClass mainClass = Loader.getInstance().getPool().getOrNull("net.minecraft.client.main.Main");
        if (mainClass == null)
            return StringUtils.EMPTY;
        CtMethod method = mainClass.getDeclaredMethod("main");
        if (method == null)
            return StringUtils.EMPTY;
        MethodInfo mainInfo = method.getMethodInfo();
        CodeAttribute ca = mainInfo.getCodeAttribute();
        String lastInvoke = StringUtils.EMPTY;

        /* Code adapted from StackOverflow Answer
         * http://stackoverflow.com/a/2102552
         */
        for (CodeIterator ci = ca.iterator(); ci.hasNext();) {
            int address = ci.next();
            int op = ci.byteAt(address);
            if (op != Opcode.INVOKEVIRTUAL) {
                continue;
            }

            int a1 = ci.s16bitAt(address + 1);
            String methodClassRef = mainClass.getClassFile().getConstPool().getMethodrefClassName(a1);
            if (!methodClassRef.contains(".")) {
                lastInvoke = methodClassRef;
            }
        }
        return lastInvoke;
    }

    private static String tryFindPlainMCClass() {
        if (Loader.getInstance().getPool().getOrNull("net.minecraft.client.Minecraft") != null)
            return "net.minecraft.client.Minecraft";
        return StringUtils.EMPTY;
    }
}
