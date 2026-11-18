package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.ExcelColumn;
import de.vensas.hypaxel.ExcelIgnore;
import de.vensas.hypaxel.XlsxException;
import de.vensas.hypaxel.XlsxException.Kind;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The columns of a mapped type: one per record component or readable bean property. Reflection
 * runs once per type; rows are written through cached method handles.
 */
public final class TypeMap {
    private static final ClassValue<TypeMap> CACHE = new ClassValue<>() {
        @Override
        protected TypeMap computeValue(Class<?> type) {
            return new TypeMap(type);
        }
    };

    private record Property(String name, Method getter, ExcelColumn column, int position) {
        int order() {
            return column == null ? Integer.MAX_VALUE : column.order();
        }
    }

    private final String[] headers;
    private final String[] formats;
    private final double[] widths;
    private final ColumnAccessor[] accessors;

    private TypeMap(Class<?> type) {
        List<Property> properties = type.isRecord() ? recordProperties(type) : beanProperties(type);
        properties.sort(Comparator.comparingInt(Property::order).thenComparingInt(Property::position));
        if (properties.isEmpty()) {
            throw new XlsxException(Kind.UNSUPPORTED_TYPE, type.getName() + " has no readable properties to export");
        }

        int count = properties.size();
        headers = new String[count];
        formats = new String[count];
        widths = new double[count];
        accessors = new ColumnAccessor[count];
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (int i = 0; i < count; i++) {
            Property property = properties.get(i);
            ExcelColumn column = property.column;
            headers[i] = column == null || column.name().isEmpty() ? property.name : column.name();
            formats[i] = column == null || column.format().isEmpty() ? null : column.format();
            Class<?> valueType = property.getter.getReturnType();
            widths[i] = column != null && column.width() > 0 ? Math.min(column.width(), 255) : estimateWidth(headers[i], valueType);
            accessors[i] = ColumnAccessor.of(unreflect(lookup, property.getter), valueType);
        }
    }

    /** Returns the cached map of a type, building it on first use. */
    public static TypeMap of(Class<?> type) {
        return CACHE.get(type);
    }

    public int columnCount() {
        return headers.length;
    }

    public String header(int column) {
        return headers[column];
    }

    /** Number format of a column, or null. */
    public String format(int column) {
        return formats[column];
    }

    public double[] widths() {
        return widths.clone();
    }

    /** Writes one cell per column for the item; {@code styles} holds one style index per column. */
    public void writeRow(ValueWriter writer, Object item, int[] styles) {
        ColumnAccessor[] columns = accessors;
        try {
            for (int i = 0; i < columns.length; i++) {
                columns[i].write(writer, item, styles[i]);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new XlsxException(Kind.UNSUPPORTED_TYPE, "reading a property of " + item.getClass().getName() + " failed", e);
        }
    }

    private static List<Property> recordProperties(Class<?> type) {
        List<Property> properties = new ArrayList<>();
        RecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            Method accessor = component.getAccessor();
            if (isIgnored(component, accessor)) {
                continue;
            }
            properties.add(new Property(component.getName(), accessor, column(component, accessor), i));
        }
        return properties;
    }

    /**
     * Fields in declaration order (superclasses first) that have a public accessor named
     * {@code getX}, {@code isX} or {@code x}, followed by getters without a field in name order.
     * This covers JavaBeans, Kotlin data classes and Scala case classes.
     */
    private static List<Property> beanProperties(Class<?> type) {
        TreeMap<String, Method> getters = new TreeMap<>();
        for (Method method : type.getMethods()) {
            if (isAccessor(method)) {
                getters.putIfAbsent(method.getName(), method);
            }
        }

        List<Property> properties = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Field field : declaredFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String name = field.getName();
            String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Method getter = getters.get("get" + capitalized);
            if (getter == null && isBoolean(field.getType())) {
                getter = getters.get("is" + capitalized);
            }
            if (getter == null) {
                getter = getters.get(name);
            }
            if (getter == null || !used.add(getter.getName())) {
                continue;
            }
            if (!isIgnored(field, getter)) {
                properties.add(new Property(name, getter, column(field, getter), properties.size()));
            }
        }
        for (Method getter : getters.values()) {
            String name = propertyName(getter);
            if (name == null || used.contains(getter.getName()) || isIgnored(getter, getter)) {
                continue;
            }
            properties.add(new Property(name, getter, getter.getAnnotation(ExcelColumn.class), properties.size()));
        }
        return properties;
    }

    private static boolean isAccessor(Method method) {
        return method.getParameterCount() == 0
                && method.getReturnType() != void.class
                && !Modifier.isStatic(method.getModifiers())
                && !method.isBridge()
                && !method.isSynthetic()
                && method.getDeclaringClass() != Object.class;
    }

    private static String propertyName(Method getter) {
        String name = getter.getName();
        int prefix = name.startsWith("get") ? 3 : name.startsWith("is") && isBoolean(getter.getReturnType()) ? 2 : 0;
        if (prefix == 0 || name.length() == prefix || !Character.isUpperCase(name.charAt(prefix))) {
            return null;
        }
        return Character.toLowerCase(name.charAt(prefix)) + name.substring(prefix + 1);
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.add(0, c);
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> c : hierarchy) {
            fields.addAll(List.of(c.getDeclaredFields()));
        }
        return fields;
    }

    private static boolean isIgnored(AnnotatedElement declaration, Method accessor) {
        return declaration.isAnnotationPresent(ExcelIgnore.class) || accessor.isAnnotationPresent(ExcelIgnore.class);
    }

    private static ExcelColumn column(AnnotatedElement declaration, Method accessor) {
        ExcelColumn column = declaration.getAnnotation(ExcelColumn.class);
        return column != null ? column : accessor.getAnnotation(ExcelColumn.class);
    }

    private static MethodHandle unreflect(MethodHandles.Lookup lookup, Method getter) {
        if (!getter.trySetAccessible()) {
            throw inaccessible(getter, null);
        }
        try {
            return lookup.unreflect(getter);
        } catch (IllegalAccessException e) {
            throw inaccessible(getter, e);
        }
    }

    private static XlsxException inaccessible(Method getter, Throwable cause) {
        Class<?> owner = getter.getDeclaringClass();
        return new XlsxException(Kind.UNSUPPORTED_TYPE, "cannot read " + owner.getName() + "." + getter.getName()
                + "(): make the type public in an exported package or open its package to module de.vensas.hypaxel", cause);
    }

    private static double estimateWidth(String header, Class<?> type) {
        int typical;
        if (type == String.class) {
            typical = 20;
        } else if (type == UUID.class) {
            typical = 38;
        } else if (type == java.time.LocalDate.class) {
            typical = 12;
        } else if (Temporal.class.isAssignableFrom(type) || Date.class.isAssignableFrom(type)) {
            typical = 18;
        } else {
            typical = 12;
        }
        return Math.min(Math.max(header.length() + 3, typical), 60);
    }
}
