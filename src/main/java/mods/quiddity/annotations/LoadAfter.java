package mods.quiddity.annotations;

import java.lang.annotation.*;

/**
 * Purely helper annotation to help me remember if an order is needed in loading.
 */
@Documented
@Target({ElementType.TYPE})
public @interface LoadAfter {
    public Class<?> type();
}
