package mods.quiddity;

import javassist.*;
import javassist.bytecode.*;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.InvocationTargetException;

public class ServerHandler {

    private final Object minecraftServerObject;

    public ServerHandler(Object serverObject) {
        this.minecraftServerObject = serverObject;
        Loader.getInstance().addServerHandler(this);
    }

    @SuppressWarnings("unused") // Suppress unused warning, it get used in injected code
    public void onTick() {
        //System.out.println("Server running!");
    }

    /**
     * Internal static code for hooking into the client
     */

    /**
     * Classes
     */
    private static Class<?> minecraftServerClass = null;

    // TODO: The main server class was always this right?
    private static final String serverClassName = "net.minecraft.server.MinecraftServer";

    public static boolean doTransform() {
        CtClass serverClass = Loader.getInstance().getPool().getOrNull(serverClassName);
        try {
            if (serverClass.getDeclaredMethod("run") != null) {
                CtMethod runMethod = serverClass.getDeclaredMethod("run");
                MethodInfo runMethodInfo = runMethod.getMethodInfo();
                CodeAttribute ca = runMethodInfo.getCodeAttribute();

                /* Code adapted from StackOverflow Answer
                 * http://stackoverflow.com/a/2102552
                 */
                for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
                    int address = ci.next();
                    int op = ci.byteAt(address);
                    if (op != Opcode.INVOKESTATIC) {
                        continue;
                    }

                    int a1 = ci.s16bitAt(address + 1);
                    if (serverClass.getClassFile().getConstPool().getMethodrefClassName(a1).equals(Thread.class.getName())
                            && serverClass.getClassFile().getConstPool().getMethodrefName(a1).equals("sleep")) {
                        serverClass.addField(CtField.make("private mods.quiddity.ServerHandler quiddityServerHandler = null;", serverClass));
                        int invokeLine = ((LineNumberAttribute)ca.getAttribute(LineNumberAttribute.tag)).toLineNumber(address);
                        runMethod.insertAt(invokeLine,
                                    "if (quiddityServerHandler == null) {" +
                                        "quiddityServerHandler = new mods.quiddity.ServerHandler($0);" +
	                                "} quiddityServerHandler.onTick();");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Unable to transform the server class.");
            e.printStackTrace();
            return false;
        }
        return false;
    }

    public static void transformsDone() {
        try {
            minecraftServerClass = Loader.getInstance().getClassLoader().loadClass(serverClassName);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Unable to load the transformed Minecraft " +
                    (Loader.getInstance().hasInternalServer() ? "internal" : StringUtils.EMPTY) + " server class!\nThis is fatal.", e);
        }
    }

    public static void startDedicatedServer() {
	    try {
		    String[] params = { "nogui" };
		    minecraftServerClass.getMethod("main", String[].class).invoke(null, (Object) params);
		    new AdminHandler().call();
	    } catch (Exception ignored) { ignored.printStackTrace(); }
    }

    public static String getMinecraftServerClassName() {
        return serverClassName;
    }
}
