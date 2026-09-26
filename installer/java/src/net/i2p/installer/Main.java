package net.i2p.installer;

/**
 * Entry point for the installer's utility jar: dispatches to one of the
 * other classes in this package, because the installer can only start a jar
 * with a single main class.
 * Usage: <code>java -jar utility.jar copy|delete|exec|fixwinpaths args...</code><br>
 *
 * @since 0.9.6
 */
public class Main {

    private static final String USAGE = "Usage: {copy|delete|exec|fixwinpaths} [args...]";

    /**
     * Run the command named by the first argument, passing it the rest.
     *
     * @param args command and its arguments
     * @throws IllegalArgumentException if no command was given, or it is not
     *                                  one of copy, delete, exec, fixwinpaths
     */
    public static void main(String[] args) {
        if (args.length == 0) {throw new IllegalArgumentException(USAGE);}
        String cmd = args[0];
        String[] shift = new String[args.length - 1];
        if (shift.length > 0) {System.arraycopy(args, 1, shift, 0, shift.length);}
        if (cmd.equals("copy")) {Copy.main(shift);}
        else if (cmd.equals("delete")) {Delete.main(shift);}
        else if (cmd.equals("exec")) {Exec.main(shift);}
        else if (cmd.equals("fixwinpaths")) {FixWinPaths.main(shift);}
        else {throw new IllegalArgumentException(USAGE);}
    }
}
