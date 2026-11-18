package de.vensas.hypaxel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Customizes how a record component or bean property is exported by the object-mapping methods.
 * Place it on a record component, a field or a getter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ExcelColumn {
    /** Header text. Defaults to the property name. */
    String name() default "";

    /** Sort key for the column order. Columns without one keep declaration order, after ordered ones. */
    int order() default Integer.MAX_VALUE;

    /** Excel number format for the column's cells, e.g. {@code "#,##0.00"} or {@code "yyyy-mm-dd"}. */
    String format() default "";

    /** Column width in characters; 0 estimates it from the header and the property type. */
    double width() default 0;
}
