/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ShellCommand.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.util;

import net.i2p.I2PAppContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Passes a command to the OS shell for execution and manages the input and
 * output.
 * @since 0.9.3
 *
 * @author hypercubus
 */
@SuppressWarnings("PMD.CloseResource")
public class ShellCommand {

    private static final boolean WAIT_FOR_EXIT_STATUS = true;

    /** @since 0.9.3 */
    private static class Result {
        public volatile boolean commandSuccessful;
    }

    /**
     * Executes a shell command in its own thread.
     *
     * @author hypercubus
     */
    private class CommandThread extends I2PAppThread {
        private final Object shellCommand;
        private final Result result;

        /**
         *  @param shellCommand either a String or a String[] (since 0.8.3)
         *  @param result out parameter
         */
        CommandThread(Object shellCommand, Result result) {
            super("ShellCommand Executor");
            this.shellCommand = shellCommand;
            this.result = result;
        }

        @Override
        public void run() {
            result.commandSuccessful = execute(shellCommand, WAIT_FOR_EXIT_STATUS);
        }
    }

    /**
     * Consumes stream data. Instances of this class, when given the
     * <code>STDOUT</code> and <code>STDERR</code> input streams of a
     * <code>Runtime.exec()</code> process for example, will prevent blocking
     * during a <code>Process.waitFor()</code> loop and thereby allow the
     * process to exit properly. This class makes no attempt to preserve the
     * consumed data.
     *
     * @author hypercubus
     */
    private static class StreamConsumer extends I2PAppThread {
        private final BufferedReader bufferedReader;

        public StreamConsumer(InputStream inputStream) {
            super("ShellCommand Consumer");
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            this.bufferedReader = new BufferedReader(inputStreamReader);
        }

        @Override
        public void run() {
            try {
                while (bufferedReader.readLine() != null) {
                    // Just like a Hoover.
                }
            } catch (IOException e) {
                // Don't bother.
            }
        }
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. Any output
     * produced by the executed command will not be displayed.
     *
     * @param  commandArray The command for the shell to execute,
     *                      as a String[].
     *                      See Runtime.exec(String[]) for more info.
     *
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     *
     * @since 0.9.38
     */
    public boolean executeSilentAndWait(String[] commandArray) {
        return execute(commandArray, WAIT_FOR_EXIT_STATUS);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed unless a
     * specified number of seconds has elapsed first. Any output produced by the
     * executed command will not be displayed.
     *
     * Warning, no good way to quote or escape spaces in arguments when shellCommand is a String.
     * Use a String array for best results, especially on Windows.
     *
     * @param  shellCommand The command for the shell to execute, as a String.
     *                      You can't quote arguments successfully.
     *                      See Runtime.exec(String) for more info.
     *
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     *
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      OR if the time expires,
     *                      else <code>false</code>.
     */
    public boolean executeSilentAndWaitTimed(String shellCommand, int seconds) {
        return executeSAWT(shellCommand, seconds);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed unless a
     * specified number of seconds has elapsed first. Any output produced by the
     * executed command will not be displayed.
     *
     * @param  commandArray The command for the shell to execute,
     *                      as a String[].
     *                      See Runtime.exec(String[]) for more info.
     *
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     *
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      OR if the time expires,
     *                      else <code>false</code>.
     *
     * @since 0.8.3
     */
    public boolean executeSilentAndWaitTimed(String[] commandArray, int seconds) {
        return executeSAWT(commandArray, seconds);
    }

    /** @since 0.8.3 */
    private boolean executeSAWT(Object shellCommand, int seconds) {
        String name = null;
        long begin = 0;
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ShellCommand.class);
        if (log.shouldDebug()) {
            if (shellCommand instanceof String) {
                name = (String) shellCommand;
            } else if (shellCommand instanceof String[]) {
                String[] arr = (String[]) shellCommand;
                name = Arrays.toString(arr);
            }
            begin = System.currentTimeMillis();
        }
        Result result = new Result();
        Thread commandThread = new CommandThread(shellCommand, result);
        commandThread.start();
        try {
            if (seconds > 0) {
                commandThread.join((long) seconds * 1000);
                if (commandThread.isAlive()) {
                    if (log.shouldDebug()) log.debug("ShellCommand gave up waiting for \"" + name + "\" after " + seconds + " seconds");
                    return true;
                }
            }
        } catch (InterruptedException e) {
            // Wake up, time to die.
        }
        if (log.shouldDebug()) log.debug("ShellCommand returning " + result.commandSuccessful + " for \"" + name + "\" after " + (System.currentTimeMillis() - begin) + " ms");
        return result.commandSuccessful;
    }

    /**
     *  Just does exec, this is NOT a test of ShellCommand.
     */
    public static void main(String[] args) {
        if (args.length <= 0) {
            System.err.println("Usage: ShellCommand commandline");
            return;
        }
        try {
            Runtime.getRuntime().exec(args);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return;
    }

    /**
     *  @param shellCommand either a String or a String[] (since 0.8.3) - quick hack
     */
    private boolean execute(Object shellCommand, boolean waitForExitStatus) {
        Process process;
        String name = null; // for debugging only
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ShellCommand.class);
        try {
            // easy way so we don't have to copy this whole method
            if (shellCommand instanceof String) {
                name = (String) shellCommand;
                if (log.shouldDebug()) log.debug("ShellCommand exec \"" + name + "\" wait? " + waitForExitStatus);
                process = Runtime.getRuntime().exec(name);
            } else if (shellCommand instanceof String[]) {
                String[] arr = (String[]) shellCommand;
                if (log.shouldDebug()) {
                    name = Arrays.toString(arr);
                    log.debug("ShellCommand exec \"" + name + "\" wait? " + waitForExitStatus);
                }
                process = Runtime.getRuntime().exec(arr);
            } else {
                throw new ClassCastException("shell command must be a String or a String[]");
            }
            Thread processStderrConsumer = new StreamConsumer(process.getErrorStream());
            processStderrConsumer.start();
            Thread processStdoutConsumer = new StreamConsumer(process.getInputStream());
            processStdoutConsumer.start();
            if (waitForExitStatus) {
                if (log.shouldDebug()) log.debug("ShellCommand waiting for \"" + name + '\"');
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    if (log.shouldWarn()) {
                        log.warn("ShellCommand exception waiting for \"" + name + '"', e);
                    }
                    return false;
                }

                if (log.shouldDebug()) log.debug("ShellCommand exit value is " + process.exitValue() + " for \"" + name + '\"');
                if (process.exitValue() > 0) return false;
            }
        } catch (IOException e) {
            // probably IOException, file not found from exec()
            if (log.shouldWarn()) {
                log.warn("ShellCommand execute exception for \"" + name + '"', e);
            }
            return false;
        }
        return true;
    }
}
