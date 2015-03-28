package mods.quiddity.world;

import javassist.CtClass;
import javassist.CtField;
import javassist.NotFoundException;
import javassist.bytecode.*;
import mods.quiddity.Loader;
import mods.quiddity.ServerHandler;
import mods.quiddity.annotations.LoadAfter;
import org.apache.commons.lang3.StringUtils;

@LoadAfter(type = ServerHandler.class)
public class WorldServerProxy {

    /**
     * Internal static code for hooking into the client
     */

    private static String worldServerClassName = StringUtils.EMPTY;
    private static Class<?> worldServerClass = null;

    public static boolean doTransform() {
        String minecraftServerName = ServerHandler.getMinecraftServerClassName();
        if (minecraftServerName == null ||minecraftServerName.isEmpty()) {
            return false;
        }

        CtClass minecraftServerClass = Loader.getInstance().getPool().getOrNull(minecraftServerName);
        if (minecraftServerClass == null) {
            return false;
        }

        /**
         * Now we parse through the field array for the WorldServer array
         */
        try {
            for (CtField field : minecraftServerClass.getFields()) {
                if (Descriptor.arrayDimension(field.getFieldInfo().getDescriptor()) == 1 &&
                        !field.getType().isPrimitive()) {
                    /**
                     * field.getType().getName() will return the type name of the <b>Array of World Servers</b> not the array type
                     */
                    worldServerClassName = Descriptor.toClassName(Descriptor.toArrayComponent(field.getFieldInfo().getDescriptor(), 1));
                    return true;
                }
            }
        } catch (NotFoundException e) {
            e.printStackTrace(System.err);
            return false;
        }

        /** <b>Ignore Below, The bytecode Mojang compiled optimized out the following explanation. Leaving the code for now, still might be of use.</b>
         * TODO: Retool the following code to make sure that the field I grab above is for sure World Server
         * Non-Static initializer values do not get set until the constructor gets called
         * In the Java Bytecode, this means all initialization is appended to any constructor call.
         *
         * (I would imagine that they wouldn't add it in the case of calling another constructor of the
         * same class in a chain, however this doesn't matter in our case)
         */
        /*CtConstructor[] possibleConstructors = minecraftServerClass.getConstructors();
        for (CtConstructor constructor : possibleConstructors) {
            CodeAttribute codeAttribute = constructor.getMethodInfo().getCodeAttribute();
            CodeIterator iterator = codeAttribute.iterator();
            boolean lastOpWasNewArray = false;
            String newArraysBaseClass = StringUtils.EMPTY;
            while (iterator.hasNext()) {
                try {
                    int address = iterator.next();
                    int opCode = iterator.byteAt(address);
                    if (opCode == Opcode.PUTFIELD) {
                        if (lastOpWasNewArray) {
                            String arrayClassName = constructor.getMethodInfo().getConstPool().getFieldrefClassName(iterator.s16bitAt(address + 1));
                            String arrayTypeClassName = Descriptor.toClassName(arrayClassName);
                            for (CtField f : possibleFields) {
                                *//**
                                 * Most likely really overkill...
                                 *//*
                                if (newArraysBaseClass.equals(arrayTypeClassName) && Descriptor.arrayDimension(f.getFieldInfo().getDescriptor()) == 1
                                        && Descriptor.toClassName(f.getFieldInfo().getDescriptor()).equals(newArraysBaseClass)) {
                                    // We hit a winner!
                                    worldServerClassName = newArraysBaseClass;

                                    // Call out to another method just due to how deep the stack is...
                                    addWorldHooks(worldServerClassName);
                                }
                            }

                        } else {
                            lastOpWasNewArray = false;
                        }
                    } else if (opCode == Opcode.ANEWARRAY || opCode == Opcode.NEWARRAY) {
                        *//**
                         * The opcodes' paramaters are stored in the method's constant pool.
                         * The index is a signed short that is contained in the next 16bits.
                         *//*
                        newArraysBaseClass = constructor.getMethodInfo().getConstPool().getMethodrefClassName(iterator.s16bitAt(address + 1));
                        lastOpWasNewArray = true;
                    }
                } catch (BadBytecode badBytecode) {
                    badBytecode.printStackTrace();
                }
            }
        }*/
        return false;
    }

    private static boolean addWorldHooks(String worldServerClassName) {
        /**
         * TODO: This is where we can start hooking into world stuff.
         *
         * For now I'll hook into the main world loop
         */

        return false;
    }
}