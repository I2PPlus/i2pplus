/*
 * Copyright (c) 2004 Ragnarok
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * A wrapper for addressbook to allow it to be started as a web application.
 *
 * This was a GenericServlet, we make it an HttpServlet solely to provide a
 * simple page to display status.
 *
 * @since 0.9.30 moved from addressbook to SusiDNS
 * @author Ragnarok
 *
 */
public class Servlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private transient Thread thread;

    /**
     * Simple output to verify that the addressbook servlet is running.
     *
     * (non-Javadoc)
     * see javax.servlet.Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PrintWriter out = response.getWriter();
        out.write("I2P addressbook OK");
    }

    /* (non-Javadoc)
     * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
     */
    @SuppressWarnings("unchecked")
    @Override
    public void init(ServletConfig config) {
        try {super.init(config);}
        catch (ServletException exp) {System.err.println("Addressbook init exception: " + exp);}
        String[] args = new String[1];
        args[0] = config.getInitParameter("home");
        try {
            ClassLoader cl = getServletContext().getClassLoader();
            @SuppressWarnings("rawtypes")
            Class<?> cls = Class.forName("net.i2p.addressbook.DaemonThread", true, cl);
            // We do it this way so that if we can't find addressbook, the whole thing doesn't die.
            // We do add addressbook.jar in WebAppConfiguration, so this is just in case.
            //Thread t = new DaemonThread(args);
            Thread t = (Thread) cls.getConstructor(String[].class).newInstance((Object)args);
            t.setDaemon(true);
            t.setName("Addressbook");
            t.start();
            this.thread = t;

            // Store HostChecker in servlet context for JSP access with retry mechanism
            // HostChecker is initialized asynchronously in Daemon.run(), so we need to wait
            java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(new Runnable() {
                private int retryCount = 0;
                private final int maxRetries = 30; // Try for 5 minutes (30 * 10 seconds)

                public void run() {
                    try {
                        ClassLoader cl2 = getServletContext().getClassLoader();
                        Class<?> daemonClass = Class.forName("net.i2p.addressbook.Daemon", true, cl2);
                        Object hostChecker = daemonClass.getDeclaredMethod("getHostCheckerInstance").invoke(null);

                        if (hostChecker != null) {
                            getServletContext().setAttribute("hostChecker", hostChecker);
                            scheduler.shutdown();
                            return;
                        } else {
                            retryCount++;
                            if (retryCount >= maxRetries) {
                                I2PAppContext.getGlobalContext().logManager().getLog(Servlet.class).warn("HostChecker instance still null after " + maxRetries + " attempts, giving up...");
                                scheduler.shutdown();
                            } else {
                                //I2PAppContext.getGlobalContext().logManager().getLog(Servlet.class).debug("HostChecker instance is null, retry " + retryCount + "/" + maxRetries);
                            }
                        }
                    } catch (Exception e) {
                        I2PAppContext.getGlobalContext().logManager().getLog(Servlet.class).warn("Failed to get HostChecker instance (retry " + retryCount + "): " + e.getMessage());
                        retryCount++;
                        if (retryCount >= maxRetries) {
                            scheduler.shutdown();
                        }
                    }
                }
            }, 10, 10, java.util.concurrent.TimeUnit.SECONDS); // Start after 10 seconds, retry every 10 seconds
        } catch (Throwable t) {
            // addressbook.jar may not be in the classpath
            I2PAppContext.getGlobalContext().logManager().getLog(Servlet.class).logAlways(Log.WARN, "Addressbook thread not started: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void destroy() {
        if (this.thread != null) {
            try {
                ClassLoader cl = getServletContext().getClassLoader();
                Class<?> cls = Class.forName("net.i2p.addressbook.DaemonThread", true, cl);
                Object t = cls.cast(this.thread);
                cls.getDeclaredMethod("halt").invoke(t);
            } catch (Throwable t) {}
        }
        super.destroy();
    }
}
