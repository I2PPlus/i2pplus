package net.i2p.installer;

import java.io.File;
import java.io.IOException;

/**
 * <p>Launch a program in the background from the installer, then terminate
 * this JVM without waiting for it.</p>
 * Usage: <code>Exec dir command [args ...]</code><br>
 *
 * The program is started directly by {@link Runtime#exec}, not through a
 * command shell, so the first word is resolved with the platform's usual
 * search path rules and shell syntax (quoting, redirection, pipes, variable
 * expansion, globbing) is not interpreted. To run a shell command, invoke the
 * shell explicitly, for example <code>Exec dir cmd /c dir</code>.
 *
 * See also {@link net.i2p.util.ShellCommand}, which does interpret the command
 * line and waits for the result.
 *
 * @since 0.4.1.4, moved to {@link net.i2p.installer} in 0.9.5
 */
public class Exec {
    /**
     * Default constructor.
     */
    public Exec() {}

    /**
     * Start a program in the specified directory and exit immediately.
     *
     * The program is not waited for, and its output streams are not read, so
     * it must not write enough to fill a pipe buffer.
     *
     * @param args working directory, then the program and its arguments
     *             (dir command [args...])
     */
    public static void main(String[] args) {
        try {
            String[] cmd = new String[args.length - 1];
            System.arraycopy(args, 1, cmd, 0, cmd.length);
            Process proc = Runtime.getRuntime().exec(cmd, (String[])null, new File(args[0]));

            // Nudge the process reaper into existence before we bail out. The
            // JVM starts that non-daemon thread lazily, on the first waitFor()
            // or exitValue() call, and it cannot reap the child once we halt.
            // Without this the child is left as a zombie when the parent dies
            // first. The IllegalThreadStateException that exitValue() throws
            // for a still-running process is expected, hence the catch-all.
            try {proc.exitValue();}
            catch (Throwable t) {}
            // exit, not System.exit(): this JVM is an izpack executable run for
            // its side effect only, and halting skips the shutdown hooks that
            // would otherwise wait on the reaper thread we cannot join anyway.
            Runtime.getRuntime().halt(0);
        } catch (IOException e) {e.printStackTrace();}
        catch (RuntimeException e) {e.printStackTrace();}
    }
}
