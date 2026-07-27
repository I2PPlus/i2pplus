package net.i2p.router.web;

/**
 *  Application information for the router console.
 *  @since 0.9.33 moved from HomeHelper
 */
public class App {
    /** Application name */
    public final String name;
    /** Application description */
    public String desc;
    /** Application URL */
    public String url;
    /** Application icon path */
    public String icon;

    /**
     * Create a new application entry.
     *
     * @param name the application name
     * @param desc the application description
     * @param url the application URL
     * @param icon the application icon path
     */
    public App(String name, String desc, String url, String icon) {
        this.name = name;
        this.desc = desc;
        this.url = url;
        this.icon = icon;
    }
}

