package mods.quiddity;

import javassist.*;
import javassist.bytecode.*;
import org.apache.commons.lang3.StringUtils;
import sun.security.krb5.internal.crypto.Des;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

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

	public void issueCommand(String command) {
		if (issueCommandMethod != null) {
			if (minecraftDedicatedServerClass.isAssignableFrom(this.minecraftServerObject.getClass())) {
				try {
					if (issueCommandMethodParamaterTypeNames.length > 2) {
						Object[] placeholders = new Object[issueCommandMethodParamaterTypeNames.length - 2];
						issueCommandMethod.invoke(this.minecraftServerObject, command, this.minecraftServerObject, placeholders);
					} else {
						issueCommandMethod.invoke(this.minecraftServerObject, command, this.minecraftServerObject);
					}
				} catch (IllegalAccessException | InvocationTargetException e) {
					System.err.println("Unable to issue minecraft command!");
				}
			}
		}
	}

    /**
     * Internal static code for hooking into the client
     */

	/**
     * Classes
     */
    private static Class<?> minecraftServerClass = null;
	// Only on dedicated servers
	private static Class<?> minecraftDedicatedServerClass = null;
	private static Class<?> serverCommandSenderClass = null;

	/**
	 * Methods
	 */
	private static Method getServerCommandSenderMethod = null;
	private static Method issueCommandMethod = null;

    // TODO: The main server class was always this right?
    private static final String serverClassName = "net.minecraft.server.MinecraftServer";
	private static String serverCommandSenderClassName = null;
	private static String minecraftDedicatedServerClassName = null;

	private static String getServerCommandSenderMethodName = null;
	private static String initServerMethodName = null;
	private static String issueCommandMethodName = null;
	private static String[] issueCommandMethodParamaterTypeNames = null;

    public static boolean doTransform() {
        CtClass serverClass = Loader.getInstance().getPool().getOrNull(serverClassName);
	    boolean commandSenderResult = getServerCommandSenderMethod(serverClass);
	    boolean issueCommandMethodResult = true;
	    // Do not run on the client's internal server, I do not beleive they have these classes
	    if (Loader.getInstance().isDedicatedServer()) {
		    issueCommandMethodResult = getRunCommandMethod(serverClass);
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
                        return commandSenderResult && issueCommandMethodResult;
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

	private static boolean getServerCommandSenderMethod(CtClass serverClass) {
		for (CtConstructor constructor : serverClass.getDeclaredConstructors()) {
			try {
				MethodInfo constructorMethodInfo = constructor.getMethodInfo();
				CodeAttribute ca = constructorMethodInfo.getCodeAttribute();

				/* Code adapted from StackOverflow Answer
                 * http://stackoverflow.com/a/2102552
                 */

				boolean foundVirtual = false;
				String potentialMethodName = "";
				String potentialTypeName = "";

				for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
					int address = ci.next();
					int op = ci.byteAt(address);
					if (foundVirtual && op == Opcode.PUTFIELD) {
						getServerCommandSenderMethodName = potentialMethodName;
						serverCommandSenderClassName = potentialTypeName;
						return true;
					}

					if (op != Opcode.INVOKEVIRTUAL) {
						if (foundVirtual)
							foundVirtual = false;
					} else {
						foundVirtual = !foundVirtual;
						if (foundVirtual) {
							int a1 = ci.s16bitAt(address + 1);

							potentialMethodName = serverClass.getClassFile().getConstPool().getMethodrefName(a1);
							potentialTypeName = serverClass.getClassFile().getConstPool().getMethodrefClassName(a1);
						}
					}
				}
			} catch (Exception e) {
				System.err.println("Unable to find server command sender class.");
				e.printStackTrace();
				return false;
			}
		}
		return false;
	}

	private static boolean getRunCommandMethod(CtClass serverClass) {
		CtMethod mainMethod = null;
		try {
			mainMethod = serverClass.getDeclaredMethod("main");
		} catch (NotFoundException ignored) { }
		if (mainMethod == null) {
			return false;
		}

		try {
			MethodInfo methodInfo = mainMethod.getMethodInfo();
			CodeAttribute ca = methodInfo.getCodeAttribute();

			/* Code adapted from StackOverflow Answer
             * http://stackoverflow.com/a/2102552
             */

			boolean foundSpecial = false;

			for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
				int address = ci.next();
				int op = ci.byteAt(address);
				if (foundSpecial && op == Opcode.INVOKESPECIAL) {
					int a1 = ci.s16bitAt(address + 1);
					minecraftDedicatedServerClassName = serverClass.getClassFile().getConstPool().getMethodrefClassName(a1);
					CtClass dedicatedServerClass = Loader.getInstance().getPool().getOrNull(minecraftDedicatedServerClassName);
					if (dedicatedServerClass == null) {
						return false;
					}

					CtMethod initMethod = getInitMethod(dedicatedServerClass);
					if (initMethod == null) {
						return false;
					}

					for (CtClass innerClass : dedicatedServerClass.getNestedClasses()) {
						if (innerClass.getEnclosingBehavior() != null && innerClass.getEnclosingBehavior().getSignature().equals(initMethod.getSignature())) {
							// Took long enough... we found the anonymous Runnable for the console handler
							MethodInfo innerMethodInfo = innerClass.getDeclaredMethod("run").getMethodInfo();
							CodeAttribute innerCa = innerMethodInfo.getCodeAttribute();

							for (CodeIterator innerCi = innerCa.iterator(); ci.hasNext(); ) {
								int innerAddress = innerCi.next();
								int innerOp = innerCi.byteAt(innerAddress);

								if (innerOp == Opcode.INVOKEVIRTUAL) {
									int a2 = innerCi.s16bitAt(innerAddress + 1);
									CtMethod[] possibleIssueMethods = dedicatedServerClass.getDeclaredMethods(innerClass.getClassFile().getConstPool().getMethodrefName(a2));
									if (possibleIssueMethods == null || possibleIssueMethods.length <= 0)
										continue;
									for (CtMethod possibleMethod : possibleIssueMethods) {
										String descriptor = possibleMethod.getSignature();
										if (Descriptor.numOfParameters(descriptor) >= 1
											&& Descriptor.getParameterTypes(descriptor, Loader.getInstance().getPool())[0].getSimpleName().equals(String.class.getSimpleName())) {
											// OMG We're actually done! We got the method!
											issueCommandMethodName = possibleMethod.getName();
											CtClass paramTypes[] = Descriptor.getParameterTypes(descriptor, Loader.getInstance().getPool());
											issueCommandMethodParamaterTypeNames = new String[paramTypes.length];
											for (int index = 0; index < paramTypes.length; index++) {
												issueCommandMethodParamaterTypeNames[index] = paramTypes[index].getName();
											}
											return true;
										}
									}
								}
							}
						}
					}
					break;
				}

				if (op != Opcode.INVOKESPECIAL) {
					if (foundSpecial)
						foundSpecial = false;
				} else {
					foundSpecial = true;
				}
			}
		} catch (Exception e) {
			System.err.println("Error while trying to find the invoke command method.");
			e.printStackTrace();
			return false;
		}

		return false;
	}

	private static CtMethod getInitMethod(CtClass dedicatedServer) {
		for (CtMethod method : dedicatedServer.getDeclaredMethods()) {
			try {
				if (method.getReturnType().isPrimitive() && Descriptor.numOfParameters(method.getMethodInfo().getDescriptor()) <= 0) {
					CtPrimitiveType returnType = (CtPrimitiveType) method.getReturnType();
					if (Objects.equals(returnType.getWrapperName(), ((CtPrimitiveType) CtClass.booleanType).getWrapperName())) {
						MethodInfo methodInfo = method.getMethodInfo();
						CodeAttribute ca = methodInfo.getCodeAttribute();

						for (CodeIterator ci = ca.iterator(); ci.hasNext(); ) {
							int address = ci.next();
							int op = ci.byteAt(address);

							if (op == Opcode.LDC) {
								int a1 = ci.byteAt(address + 1);
								String testString = (String)dedicatedServer.getClassFile().getConstPool().getLdcValue(a1);
								if (testString.equalsIgnoreCase("Server console handler")) {
									initServerMethodName = testString;
									return method;
								}
							}
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}

    public static void transformsDone() {
        try {
            minecraftServerClass = Loader.getInstance().getClassLoader().loadClass(serverClassName);
	        if (serverCommandSenderClassName != null && getServerCommandSenderMethodName != null) {
		        serverCommandSenderClass = Loader.getInstance().getClassLoader().loadClass(serverCommandSenderClassName);
				getServerCommandSenderMethod = serverCommandSenderClass.getDeclaredMethod(getServerCommandSenderMethodName, null);
	        }
	        if (Loader.getInstance().isDedicatedServer()) {
		        minecraftDedicatedServerClass = Loader.getInstance().getClassLoader().loadClass(minecraftDedicatedServerClassName);
		        Class<?> paramTypes[] = new Class<?>[issueCommandMethodParamaterTypeNames.length];
		        for (int index = 0; index < issueCommandMethodParamaterTypeNames.length; index++) {
			        paramTypes[index] = Loader.getInstance().getClassLoader().loadClass(issueCommandMethodParamaterTypeNames[index]);
		        }
		        issueCommandMethod = minecraftDedicatedServerClass.getDeclaredMethod(issueCommandMethodName, paramTypes);
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
		    new AdminHandler().call();
	    } catch (Exception ignored) { ignored.printStackTrace(); }
    }

    public static String getMinecraftServerClassName() {
        return serverClassName;
    }
}
