package org.rrd4j.core;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Description of a {@link RrdBackendFactory}
 *
 * @author Fabrice Bacchella
 * @since 3.4
 */
@Documented
@Retention(RUNTIME)
@Target(TYPE)
public @interface RrdBackendAnnotation {
    /** Default caching allowed flag. */
    boolean DEFAULT_CACHING_ALLOWED = true;

    /** Backend name.
     *
     * @return the backend name
     */
    String name();

    /** Whether caching is allowed.
     *
     * @return true if caching is allowed
     */
    boolean cachingAllowed() default DEFAULT_CACHING_ALLOWED;

    /** URI scheme.
     *
     * @return the URI scheme
     */
    String scheme() default "";

    /** Whether to validate the RRD header.
     *
     * @return true if header validation is required
     */
    boolean shouldValidateHeader();
}
