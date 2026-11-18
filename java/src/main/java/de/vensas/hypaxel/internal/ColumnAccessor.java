package de.vensas.hypaxel.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * Reads one property of an item and writes it as a cell. Each primitive type has its own
 * implementation whose method handle returns that primitive, so values are never boxed.
 */
interface ColumnAccessor {
    void write(ValueWriter writer, Object item, int style) throws Throwable;

    static ColumnAccessor of(MethodHandle getter, Class<?> type) {
        if (type == int.class || type == short.class || type == byte.class) {
            MethodHandle handle = adapt(getter, int.class);
            return (writer, item, style) -> writer.engine().writeLong((int) handle.invokeExact(item), style);
        }
        if (type == long.class) {
            MethodHandle handle = adapt(getter, long.class);
            return (writer, item, style) -> writer.engine().writeLong((long) handle.invokeExact(item), style);
        }
        if (type == double.class) {
            MethodHandle handle = adapt(getter, double.class);
            return (writer, item, style) -> writer.engine().writeDouble((double) handle.invokeExact(item), style);
        }
        if (type == float.class) {
            MethodHandle handle = adapt(getter, float.class);
            return (writer, item, style) -> writer.write((float) handle.invokeExact(item), style);
        }
        if (type == boolean.class) {
            MethodHandle handle = adapt(getter, boolean.class);
            return (writer, item, style) -> writer.engine().writeBoolean((boolean) handle.invokeExact(item), style);
        }
        if (type == String.class) {
            MethodHandle handle = adapt(getter, String.class);
            return (writer, item, style) -> writer.write((String) handle.invokeExact(item), style);
        }
        MethodHandle handle = adapt(getter, Object.class);
        return (writer, item, style) -> writer.writeObject((Object) handle.invokeExact(item), style);
    }

    private static MethodHandle adapt(MethodHandle getter, Class<?> returnType) {
        return getter.asType(MethodType.methodType(returnType, Object.class));
    }
}
