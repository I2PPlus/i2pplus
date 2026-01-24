package net.i2p.router.web.helpers;

import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NewsHelper;
import net.i2p.util.RandomSource;

/**
 * simple helper to control restarts/shutdowns in the left hand nav
 *
 */
public class ConfigRestartBean {
    /** all these are tagged below so no need to _x them here.
     *  order is: form value, form class, display text.
     */
    private static final String[] SET1 = {"shutdownImmediate", "stop now", "Shutdown immediately", "cancelShutdown", "cancel", "Cancel shutdown"};
    private static final String[] SET2 = {"restartImmediate", "reload now", "Restart immediately", "cancelShutdown", "cancel", "Cancel restart"};
    private static final String[] SET3 = {"restart", "reload", "Restart", "shutdown", "stop", "Shutdown"};
    private static final String[] SET4 = {"shutdown", "stop", "Shutdown"};

    private static final String _systemNonce = Long.toString(RandomSource.getInstance().nextLong());
    private static final String PROP_ADVANCED = "routerconsole.advanced";

    public static String getNonce() {return _systemNonce;}

    public static boolean isAdvanced() {
        RouterContext ctx = ContextHelper.getContext(null);
        return ctx.getBooleanProperty(PROP_ADVANCED);
    }

    /** this also initiates the restart/shutdown based on action */
    public static String renderStatus(String urlBase, String action, String nonce) {
        RouterContext ctx = ContextHelper.getContext(null);
        String systemNonce = getNonce();
        if ( (nonce != null) && (systemNonce.equals(nonce)) && (action != null) ) {
            // Normal browsers send value, IE sends button label
            if ("shutdownImmediate".equals(action) || _t("Shutdown immediately", ctx).equals(action)) {
                if (ctx.hasWrapper()) {ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD, false);}
                ctx.router().shutdownGracefully(Router.EXIT_HARD); // give the UI time to respond
            } else if ("cancelShutdown".equals(action) || _t("Cancel shutdown", ctx).equals(action) || _t("Cancel restart", ctx).equals(action)) {
                ctx.router().cancelGracefulShutdown();
                // Also cancel delayed tunnel shutdown if in progress
                TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
                if (tcg != null) {
                    tcg.cancelDelayedShutdown();
                }
            } else if ("restartImmediate".equals(action) || _t("Restart immediately", ctx).equals(action)) {
                if (ctx.hasWrapper()) {ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_HARD_RESTART, false);}
                ctx.router().shutdownGracefully(Router.EXIT_HARD_RESTART); // give the UI time to respond
            } else if ("restart".equals(action) || _t("Restart", ctx).equals(action)) {
                if (ctx.hasWrapper()) {ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL_RESTART, false);}
                ctx.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            } else if ("shutdown".equals(action) || _t("Shutdown", ctx).equals(action)) {
                if (ctx.hasWrapper()) {ConfigServiceHandler.registerWrapperNotifier(ctx, Router.EXIT_GRACEFUL, false);}
                ctx.router().shutdownGracefully();
            }
        }

        boolean shuttingDown = isShuttingDown(ctx);
        boolean restarting = isRestarting(ctx);
        long timeRemaining = ctx.router().getShutdownTimeRemaining();
        StringBuilder buf = new StringBuilder(128);
        int transit = ctx.tunnelManager().getParticipatingCount();
        boolean serverTunnelDelay = TunnelControllerGroup.isDelayedShutdownInProgress();
        if (serverTunnelDelay) {
            int remaining = TunnelControllerGroup.getRemainingShutdownDelay();
            if (remaining > 0) {
                String delayStr = formatDelay(remaining);
                buf.append("<h4 id=sb_shutdownStatus class=volatile><span class=deferring><b>");
                if (restarting) {
                    buf.append(_t("Deferring restart for {0} until all servers have stopped", delayStr, ctx));
                } else {
                    buf.append(_t("Deferring shutdown for {0} until all servers have stopped", delayStr, ctx));
                }
                buf.append("</b></span></h4><hr>");
                // Show cancel buttons for deferred shutdown
                if (restarting) {
                    buttons(ctx, buf, urlBase, systemNonce, SET2);
                } else {
                    buttons(ctx, buf, urlBase, systemNonce, SET1);
                }
            }
        } else if ((shuttingDown || restarting) && timeRemaining <= 45*1000) {
            buf.append("<h4 id=sb_shutdownStatus class=volatile><span id=imminent><b>");
            if (restarting) {buf.append(_t("Restart imminent", ctx));}
            else {buf.append(_t("Shutdown imminent", ctx));}
            buf.append("&hellip;</b></span></h4>");
        } else if (shuttingDown) {
            buf.append("<h4 id=sb_shutdownStatus class=volatile><span>")
               .append(_t("Shutdown in {0}", DataHelper.formatDuration2(timeRemaining), ctx));
            if (transit > 0) {
                if (isAdvanced()) {
                    buf.append("&hellip;<br>").append(ngettext("{0} transit tunnel still active",
                                                               "{0} transit tunnels still active", transit, ctx));
                } else {
                    buf.append("&hellip;<br>").append(ngettext("Please wait for routing commitment to expire for {0} tunnel",
                                                     "Please wait for routing commitments to expire for {0} tunnels", transit, ctx));
                }
            }
            buf.append("</span></h4><hr>");
            buttons(ctx, buf, urlBase, systemNonce, SET1);
        } else if (restarting) {
            buf.append("<h4 id=sb_shutdownStatus class=volatile><span>")
               .append(_t("Restart in {0}", DataHelper.formatDuration2(timeRemaining), ctx));
            if (transit > 0) {
                if (isAdvanced()) {
                    buf.append("&hellip;<br>").append(ngettext("{0} transit tunnel still active",
                                                               "{0} transit tunnels still active", transit, ctx));
                } else {
                    buf.append("&hellip;<br>").append(ngettext("Please wait for routing commitment to expire for {0} tunnel",
                                                               "Please wait for routing commitments to expire for {0} tunnels",
                                                               transit, ctx));
                }
            }
            buf.append("</span></h4><hr>");
            buttons(ctx, buf, urlBase, systemNonce, SET2);
        } else {
            if (ctx.hasWrapper() || NewsHelper.isExternalRestartPending()) {buttons(ctx, buf, urlBase, systemNonce, SET3);}
            else {buttons(ctx, buf, urlBase, systemNonce, SET4);}
        }
        return buf.toString();
    }

    /**
     * Check if any i2ptunnel servers have shutdown delays configured.
     * @param ctx the router context
     * @return true if any server tunnel has shutdownDelayMin < shutdownDelayMax
     * @since 0.9.68+
     */
    private static boolean hasServerTunnelDelays(RouterContext ctx) {
        net.i2p.app.ClientAppManager mgr = ctx.clientAppManager();
        if (mgr == null) {
            return false;
        }
        net.i2p.app.ClientApp i2pt = mgr.getRegisteredApp("i2ptunnel");
        if (i2pt == null) {
            return false;
        }
        try {
            java.lang.reflect.Field f = i2pt.getClass().getDeclaredField("_instance");
            if (f != null && f.getType() == java.lang.reflect.Field.class) {
                f.setAccessible(true);
                Object instance = f.get(null);
                if (instance != null && instance.getClass().getName().equals("net.i2p.i2ptunnel.TunnelControllerGroup")) {
                    java.lang.reflect.Method m = instance.getClass().getMethod("hasServerTunnelDelays");
                    if (m != null) {
                        Object result = m.invoke(instance);
                        if (result instanceof Boolean) {
                            return (Boolean) result;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** @param s value,class,label,... triplets */
    private static void buttons(RouterContext ctx, StringBuilder buf, String url, String nonce, String[] s) {
        buf.append("<form id=sb_routerControl class=\"volatile collapse\" action=\"")
           .append(url)
           .append("\" method=POST>\n<input type=hidden name=consoleNonce value=")
           .append(nonce)
           .append(">\n");
        for (int i = 0; i < s.length; i+= 3) {
            buf.append("<button type=submit name=action value=\"")
               .append(s[i]).append("\" class=\"")
               .append(s[i+1]).append("\" title=\"")
               .append(_t(s[i+2], ctx)).append("\">")
               .append(_t(s[i+2], ctx)).append("</button>\n");
        }
        buf.append("</form>\n");
    }

    public static boolean isShuttingDown(RouterContext ctx) {
        int code = ctx.router().scheduledGracefulExitCode();
        return Router.EXIT_GRACEFUL == code || Router.EXIT_HARD == code;
    }

    public static boolean isRestarting(RouterContext ctx) {
        int code = ctx.router().scheduledGracefulExitCode();
        return Router.EXIT_GRACEFUL_RESTART == code || Router.EXIT_HARD_RESTART == code;
    }

    /** this is for sidebar.jsp */
    public static long getRestartTimeRemaining() {
        RouterContext ctx = ContextHelper.getContext(null);
        if (ctx.router().gracefulShutdownInProgress()) {return ctx.router().getShutdownTimeRemaining();}
        return Long.MAX_VALUE/2; // sidebar.jsp adds a safety factor so we don't want to overflow...
    }

    private static String _t(String s, RouterContext ctx) {
        return Messages.getString(s, ctx);
    }

    private static String _t(String s, Object o, RouterContext ctx) {
        return Messages.getString(s, o, ctx);
    }

    /** translate (ngettext) @since 0.9.10 */
    private static String ngettext(String s, String p, int n, RouterContext ctx) {
        return Messages.getString(n, s, p, ctx);
    }

    /**
     *  Format seconds as "1m 30s" or just "45s".
     *  @since 0.9.68+
     */
    private static String formatDelay(int seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        int mins = seconds / 60;
        int secs = seconds % 60;
        if (mins > 0 && secs > 0) {
            return mins + "m " + secs + "s";
        } else if (mins > 0) {
            return mins + "m";
        } else {
            return secs + "s";
        }
    }
}

