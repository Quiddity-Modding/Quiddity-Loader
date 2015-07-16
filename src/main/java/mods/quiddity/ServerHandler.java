package mods.quiddity;

import javassist.*;
import javassist.bytecode.*;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ServerHandler {

    private final Object minecraftServerObject;
	private final AdminHandler adminHandlerInstance;

    public ServerHandler(Object serverObject) {
        this.minecraftServerObject = serverObject;
        Loader.getInstance().addServerHandler(this);
	    adminHandlerInstance = new AdminHandler();

	    try {
		    adminHandlerInstance.call();
	    } catch (Exception e) {
		    System.err.println("Unable to start administration server!");
	    }
    }

    @SuppressWarnings("unused") // Suppress unused warning, it get used in injected code
    public void onTick() {
	    //System.out.println("Server running!");
    }

	public void issueCommand(String command) {
		if (issueCommandMethod != null) {
			if (minecraftServerClass.isAssignableFrom(this.minecraftServerObject.getClass())) {
				try {
					issueCommandMethod.invoke(this.minecraftServerObject, command, this.minecraftServerObject);
				} catch (IllegalAccessException | InvocationTargetException e) {
					System.err.println("Unable to issue minecraft command!");
				}
			}
		}
	}

    /**
     * Internal static code for hooking into the client
     */

	private static boolean afterClientServerMerge = false;

	/**
     * Classes
     */
    private static Class<?> minecraftServerClass = null;

	/**
	 * Methods
	 */
	private static Method issueCommandMethod = null;

    // TODO: The main server class was always this right?
    private static String serverClassName = "net.minecraft.server.MinecraftServer";

	private static String getServerCommandSenderMethodName = null;
	private static String initServerMethodName = null;

	private static String issueCommandMethodName = null;
	private static String[] issueCommandMethodParamaterTypeNames = null;

    public static boolean doTransform() {
        CtClass serverClass = Loader.getInstance().getPool().getOrNull(serverClassName);
	    if (serverClass == null) {
		    return false;
	    }

	    if (serverClass.getClassFile().isAbstract()) {
		    afterClientServerMerge = true;
	    }

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
					    break;
				    }
			    }
		    }
	    } catch (Exception e) {
		    System.err.println("Unable to transform the server class.");
		    e.printStackTrace();
		    return false;
	    }

	    boolean issueCommandMethodResult = true;
	    // Do not run on the client's internal server, I do not beleive they have these classes
	    if (Loader.getInstance().isDedicatedServer()) {
		    issueCommandMethodResult = getRunCommandMethod(serverClass);
	    }

        return issueCommandMethodResult;
    }

	private static boolean getRunCommandMethod(CtClass server) {
		CtMethod initMethod = getInitMethod(server);
		if (initMethod == null) {
			return false;
		}
		CtClass serverClass = server;
		if (afterClientServerMerge) {
			serverClass = getDedicatedServerClass(server);
			if (serverClass == null) {
				return false;
			}

			try {
				initMethod = serverClass.getMethod(initMethod.getName(), initMethod.getSignature());
			} catch (NotFoundException e) {
				return false;
			}
		}

		CtClass consoleThread = null;
		try {
			MethodInfo methodInfo = initMethod.getMethodInfo();
			CodeAttribute ca = methodInfo.getCodeAttribute();
			for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
				int address = ci.next();
				int op = ci.byteAt(address);
				if (op == Opcode.NEW) {
					int a1 = ci.s16bitAt(address + 1);
					CtClass testClass = Loader.getInstance().getPool().getOrNull(serverClass.getClassFile().getConstPool().getClassInfo(a1));
					if (testClass.getSuperclass().getName().equals(Thread.class.getName())) {
						consoleThread = testClass;
						break;
					}
				}
			}
		} catch (Exception e) {
			System.err.println("Error while trying to find the console input thread");
			e.printStackTrace();
			return false;
		}
		if (consoleThread == null) {
			return false;
		}

		try {
			CtMethod runMethod = consoleThread.getMethod("run", "()V");
			MethodInfo methodInfo = runMethod.getMethodInfo();
			CodeAttribute ca = methodInfo.getCodeAttribute();
			for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
				int address = ci.next();
				int op = ci.byteAt(address);
				if (op == Opcode.INVOKEVIRTUAL) {
					int a1 = ci.s16bitAt(address + 1);
					try {
						CtMethod testMethod = serverClass.getMethod(consoleThread.getClassFile().getConstPool().getMethodrefName(a1), consoleThread.getClassFile().getConstPool().getMethodrefType(a1));
						if (testMethod.getParameterTypes().length >= 2 && testMethod.getParameterTypes()[0].getName().equals(String.class.getName())) {
							issueCommandMethodName = testMethod.getName();
							issueCommandMethodParamaterTypeNames = new String[testMethod.getParameterTypes().length];
							for (int index = 0; index < testMethod.getParameterTypes().length; index++) {
								issueCommandMethodParamaterTypeNames[index] = testMethod.getParameterTypes()[index].getName();
							}
							return true;
						}
					} catch (NotFoundException ignored) { }
				}
			}
		} catch (Exception e) {
			System.err.println("Error while trying to find the console input thread");
			e.printStackTrace();
			return false;
		}
		return false;
	}

	private static CtClass getDedicatedServerClass(CtClass abstractMinecraft) {
		if (!afterClientServerMerge) {
			return null;
		}

		CtMethod mainMethod = null;
		try {
			mainMethod = abstractMinecraft.getDeclaredMethod("main");
		} catch (NotFoundException ignored) { }
		if (mainMethod == null) {
			return null;
		}
		MethodInfo methodInfo = mainMethod.getMethodInfo();
		CodeAttribute ca = methodInfo.getCodeAttribute();

		try {
			for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
				int address = ci.next();
				int op = ci.byteAt(address);
				if (op == Opcode.NEW) {
					int a1 = ci.s16bitAt(address + 1);
					CtClass testClass = Loader.getInstance().getPool().getOrNull(abstractMinecraft.getClassFile().getConstPool().getClassInfo(a1));
					if (testClass.subclassOf(abstractMinecraft)) {
						serverClassName = testClass.getName();
						return testClass;
					}
				}
			}
		} catch (Exception e) {
			System.err.println("Error while trying to find the dedicated server class.");
			e.printStackTrace();
		}
		return null;
	}

	private static CtMethod getInitMethod(CtClass mainServer) {
		try {
			CtMethod runMethod = mainServer.getMethod("run", "()V");
			MethodInfo methodInfo = runMethod.getMethodInfo();
			CodeAttribute ca = methodInfo.getCodeAttribute();
			for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
				int address = ci.next();
				int op = ci.byteAt(address);

				if (op == (afterClientServerMerge ? Opcode.INVOKEVIRTUAL : Opcode.INVOKESPECIAL)) {
					int a1 = ci.s16bitAt(address + 1);
					CtMethod initMethod = mainServer.getMethod(mainServer.getClassFile().getConstPool().getMethodrefName(a1), mainServer.getClassFile().getConstPool().getMethodrefType(a1));
					initServerMethodName = initMethod.getName();
					return initMethod;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

    public static void transformsDone() {
        try {
            minecraftServerClass = Loader.getInstance().getClassLoader().loadClass(serverClassName);
	        if (Loader.getInstance().isDedicatedServer()) {
		        Class<?>[] paramTypes = new Class<?>[issueCommandMethodParamaterTypeNames.length];
		        for (int index = 0; index < issueCommandMethodParamaterTypeNames.length; index++) {
			        paramTypes[index] = Loader.getInstance().getClassLoader().loadClass(issueCommandMethodParamaterTypeNames[index]);
		        }
		        issueCommandMethod = minecraftServerClass.getDeclaredMethod(issueCommandMethodName, paramTypes);
	        }
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Unable to load the transformed Minecraft " +
                    (Loader.getInstance().hasInternalServer() ? "internal" : StringUtils.EMPTY) + " server class!\nThis is fatal.", e);
        } catch (NoSuchMethodException e) {
	        throw new AssertionError("Unable to load the command sender method \nThis is fatal.", e);
        }
    }

    public static void startDedicatedServer() {
	    try {
		    String[] params = { "nogui" };
		    minecraftServerClass.getMethod("main", String[].class).invoke(null, (Object) params);
	    } catch (Exception ignored) { ignored.printStackTrace(); }
    }

    public static String getMinecraftServerClassName() {
        return serverClassName;
    }
}
