package net.i2p.servlet.filters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Pins that the XSS filter's names and values describe the same request.
 *
 * <p>The wrapper drops a parameter value that fails its character whitelist and
 * returns null for it, which every caller already has to cope with. What it did not
 * do was take the name out of the enumeration, so a caller that listed the
 * parameters and then read them - the usual way of scanning a form - met a name with
 * no value behind it. One such scan in the webmail died of the null.
 *
 * @since 0.9.72+
 */
public class XSSRequestWrapperNamesTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public XSSRequestWrapperNamesTest() {}

    /**
     * A request with three parameters: one clean, one whose value the whitelist
     * rejects, and one marked nofilter_ so its value passes through untouched.
     *
     * @return the request to wrap
     */
    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn("/mail");
        // a fresh enumeration per call, because an Enumeration is used up
        when(req.getParameterNames()).thenAnswer(
            inv -> Collections.enumeration(Arrays.asList("check1", "folder", "nofilter_x")));
        when(req.getParameterValues("check1")).thenReturn(new String[] {"<b>"});
        when(req.getParameter("check1")).thenReturn("<b>");
        when(req.getParameterValues("folder")).thenReturn(new String[] {"INBOX"});
        when(req.getParameter("folder")).thenReturn("INBOX");
        when(req.getParameterValues("nofilter_x")).thenReturn(new String[] {"<b>"});
        when(req.getParameter("nofilter_x")).thenReturn("<b>");
        return req;
    }

    /**
     * The defect: a rejected value left its name in the list, so enumerating the
     * parameters and reading each one gave a null for a name that was just named.
     */
    @Test
    public void testARejectedValueIsNotEnumerated() {
        XSSRequestWrapper wrapper = new XSSRequestWrapper(request());
        List<String> names = Collections.list(wrapper.getParameterNames());
        assertFalse("a dropped value was still enumerated: " + names, names.contains("check1"));
        assertTrue(names.containsAll(Arrays.asList("folder", "nofilter_x")));
        assertNull(wrapper.getParameter("check1"));
    }

    /**
     * Every enumerated name has a value, which is the contract the scan relies on.
     */
    @Test
    public void testEveryEnumeratedNameHasAValue() {
        XSSRequestWrapper wrapper = new XSSRequestWrapper(request());
        for (String name : Collections.list(wrapper.getParameterNames())) {
            String value = wrapper.getParameter(name);
            assertNotNull("no value behind the enumerated name " + name, value);
            assertNotNull("no values behind the enumerated name " + name,
                          wrapper.getParameterValues(name));
        }
    }

    /**
     * The parameter map is built from the same enumeration, so it agrees with it.
     */
    @Test
    public void testTheParameterMapAgreesWithTheNames() {
        XSSRequestWrapper wrapper = new XSSRequestWrapper(request());
        Map<String, String[]> map = wrapper.getParameterMap();
        Set<String> names = new HashSet<>(Collections.list(wrapper.getParameterNames()));
        assertEquals(names, map.keySet());
        assertEquals("INBOX", map.get("folder")[0]);
        assertEquals("<b>", map.get("nofilter_x")[0]);
    }

    /**
     * A parameter that submitted two values and lost one to the whitelist keeps the
     * other, and its name stays enumerated.
     */
    @Test
    public void testAPartiallyRejectedParameterKeepsItsName() {
        HttpServletRequest req = request();
        when(req.getParameterValues("folder")).thenReturn(new String[] {"<b>", "INBOX"});
        XSSRequestWrapper wrapper = new XSSRequestWrapper(req);
        List<String> names = Collections.list(wrapper.getParameterNames());
        assertTrue(names.contains("folder"));
        assertArrayEquals(new String[] {"INBOX"}, wrapper.getParameterValues("folder"));
    }
}
