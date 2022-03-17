/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ShellCommand.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Arrays;

import net.i2p.I2PAppContext;

/**
 * Passes a command to the OS shell for execution and manages the input and
 * output.
 * <p>
 * This class must be kept <code>gcj</code>-compatible.
 *
 * @author hypercubus
 */
public class ShellCommand {

    private static final boolean CONSUME_OUTPUT    = true;
    private static final boolean NO_CONSUME_OUTPUT = false;

    private static final boolean WAIT_FOR_EXIT_STATUS    = true;
    private static final boolean NO_WAIT_FOR_EXIT_STATUS = false;

    // Following are unused, only for NO_CONSUME_OUTPUT;
    // need synchronization or volatile or something if we start using it.
    private InputStream   _errorStream;
    private InputStream   _inputStream;
    private OutputStream  _outputStream;

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
        private final boolean consumeOutput;
        private final Object shellCommand;
        private final Result result;

        /**
         *  @param shellCommand either a String or a String[] (since 0.8.3)
         *  @param consumeOutput always true, false is unused, possibly untested
         *  @param result out parameter
         */
        CommandThread(Object shellCommand, boolean consumeOutput, Result result) {
            super("ShellCommand Executor");
            this.shellCommand = shellCommand;
            this.consumeOutput = consumeOutput;
            this.result = result;
        }

        @Override
        public void run() {
            result.commandSuccessful = execute(shellCommand, consumeOutput, WAIT_FOR_EXIT_STATUS);
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
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            this.bufferedReader = new BufferedReader(inputStreamReader);
        }

        @Override
        public void run() {
            try {
                while ((bufferedReader.readLine()) != null) {
                    // Just like a Hoover.
                }
            } catch (IOException e) {
                // Don't bother.
            }
        }
    }

    private final static int BUFFER_SIZE = 1024;

    /**
     * Reads data from a <code>java.io.InputStream</code> and writes it to
     * <code>STDOUT</code>.
     *
     * UNUSED, only for NO_CONSUME_OUTPUT
     *
     * @author hypercubus
     */
    private static class StreamReader extends I2PAppThread {
        private final BufferedReader bufferedReader;

        public StreamReader(InputStream inputStream) {
            super("ShellCommand Reader");
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            this.bufferedReader = new BufferedReader(inputStreamReader);
        }

        @Override
        public void run() {
            char[] buffer    = new char[BUFFER_SIZE];
            int    bytesRead;

            try {
                while (true)
                    while ((bytesRead = bufferedReader.read(buffer, 0, BUFFER_SIZE)) != -1)
                        for (int i = 0; i < bytesRead; i++)
                            System.out.print(buffer[i]);  // TODO Pipe this to the calling thread instead of STDOUT

            } catch (IOException e) {
                // Don't bother.
            }
        }
    }

    /**
     * Reads data from <code>STDIN</code> and writes it to a
     * <code>java.io.OutputStream</code>.
     *
     * UNUSED, only for NO_CONSUME_OUTPUT
     *
     * @author hypercubus
     */
    private static class StreamWriter extends I2PAppThread {
        private final BufferedWriter bufferedWriter;

        public StreamWriter(OutputStream outputStream) {
            super("ShellCommand Writer");
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            this.bufferedWriter = new BufferedWriter(outputStreamWriter);
        }

        @Override
        public void run() {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            try {
                while (true) {
                    bufferedWriter.write(in.readLine());
                    bufferedWriter.write("\r\n");
                    bufferedWriter.flush();
                }
            } catch (IOException e) {
                try {
                    bufferedWriter.flush();
                } catch (IOException e1) {
                    // Eat it.
                }
            }
        }
    }

    /**
     * Passes a command to the shell for execution and returns immediately
     * without waiting for an exit status. All output produced by the
     * executed command will go to <code>STDOUT</code> and <code>STDERR</code>
     * as appropriate, and can be read via {@link #getOutputStream()} and
     * {@link #getErrorStream()}, respectively. Input can be passed to the
     * <code>STDIN</code> of the shell process via {@link #getInputStream()}.
     *
     * Warning, no good way to quote or escape spaces in arguments with this method.
     * @deprecated unused
     *
     * @param shellCommand The command for the shell to execute.
     */
    @Deprecated
    public void execute(String shellCommand) {
        execute(shellCommand, NO_CONSUME_OUTPUT, NO_WAIT_FOR_EXIT_STATUS);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. All output
     * produced by the executed command will go to <code>STDOUT</code> and
     * <code>STDERR</code> as appropriate, and can be read via
     * {@link #getOutputStream()} and {@link #getErrorStream()}, respectively.
     * Input can be passed to the <code>STDIN</code> of the shell process via
     * {@link #getInputStream()}.
     *
     * Warning, no good way to quote or escape spaces in arguments with this method.
     * @deprecated unused
     *
     * @param  shellCommand The command for the shell to execute.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    @Deprecated
    public boolean executeAndWait(String shellCommand) {
        return execute(shellCommand, NO_CONSUME_OUTPUT, WAIT_FOR_EXIT_STATUS);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed, unless a
     * specified number of seconds has elapsed first. All output produced by the
     * executed command will go to <code>STDOUT</code> and <code>STDERR</code>
     * as appropriate, and can be read via {@link #getOutputStream()} and
     * {@link #getErrorStream()}, respectively. Input can be passed to the
     * <code>STDIN</code> of the shell process via {@link #getInputStream()}.
     *
     * Warning, no good way to quote or escape spaces in arguments with this method.
     * @deprecated unused
     *
     * @param  shellCommand The command for the shell to execute.
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    @Deprecated
    public boolean executeAndWaitTimed(String shellCommand, int seconds) {
        Result result = new Result();
        Thread commandThread = new CommandThread(shellCommand, NO_CONSUME_OUTPUT, result);
        commandThread.start();
        try {
            if (seconds > 0) {
                commandThread.join(seconds * 1000);
                if (commandThread.isAlive())
                    return true;
            }
        } catch (InterruptedException e) {
            // Wake up, time to die.
        }
        return result.commandSuccessful;
    }

    /**
     * Passes a command to the shell for execution and returns immediately
     * without waiting for an exit status. Any output produced by the executed
     * command will not be displayed.
     *
     * Warning, no good way to quote or escape spaces in arguments with this method.
     * @deprecated unused
     *
     * @param  shellCommand The command for the shell to execute.
     * @throws IOException
     */
    @Deprecated
    public void executeSilent(String shellCommand) throws IOException {
        Runtime.getRuntime().exec(shellCommand, null);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. Any output
     * produced by the executed command will not be displayed.
     *
     * Warning, no good way to quote or escape spaces in arguments with this method.
     *
     * @param  shellCommand The command for the shell to execute.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     * @deprecated Use the String[] method
     */
    @Deprecated
    public boolean executeSilentAndWait(String shellCommand) {
        return execute(shellCommand, CONSUME_OUTPUT, WAIT_FOR_EXIT_STATUS);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. Any output
     * produced by the executed command will not be displayed.
     *
     * @param  commandArray The command for the shell to execute,
     *                      as a String[].
     *                      See Runtime.exec(String[]) for more info.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     * @since 0.9.38
     */
    public boolean executeSilentAndWait(String[] commandArray) {
        return execute(commandArray, CONSUME_OUTPUT, WAIT_FOR_EXIT_STATUS);
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
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
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
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      OR if the time expires,
     *                      else <code>false</code>.
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
        Thread commandThread = new CommandThread(shellCommand, CONSUME_OUTPUT, result);
        commandThread.start();
        try {
            if (seconds > 0) {
                commandThread.join(seconds * 1000);
                if (commandThread.isAlive()) {
                    if (log.shouldDebug())
                        log.debug("ShellCommand gave up waiting for \"" + name + "\" after " + seconds + " seconds");
                    return true;
                }
            }
        } catch (InterruptedException e) {
            // Wake up, time to die.
        }
        if (log.shouldDebug())
            log.debug("ShellCommand returning " + result.commandSuccessful + " for \"" + name + "\" after " + (System.currentTimeMillis() - begin) + " ms");
        return result.commandSuccessful;
    }

    /** @deprecated unused */
    @Deprecated
    public InputStream getErrorStream() {
        return _errorStream;
    }

    /** @deprecated unused */
    @Deprecated
    public InputStream getInputStream() {
        return _inputStream;
    }

    /** @deprecated unused */
    @Deprecated
    public OutputStream getOutputStream() {
        return _outputStream;
    }

    /**
     *  Just does exec, this is NOT a test of ShellCommand.
     */
    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: ShellCommand commandline");
            return;
        }
	try {
            Runtime.getRuntime().exec(args);
	} catch (IOException ioe) { ioe.printStackTrace(); }
        return;
    }

    /**
     *  @param shellCommand either a String or a String[] (since 0.8.3) - quick hack
     *  @param consumeOutput always true, false is unused, possibly untested
     */
    private boolean execute(Object shellCommand, boolean consumeOutput, boolean waitForExitStatus) {
        Process process;
        String name = null;  // for debugging only
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ShellCommand.class);
        try {
            // easy way so we don't have to copy this whole method
            if (shellCommand instanceof String) {
                name = (String) shellCommand;
                if (log.shouldDebug())
                    log.debug("ShellCommand exec \"" + name + "\" consume? " + consumeOutput + " wait? " + waitForExitStatus);
                process = Runtime.getRuntime().exec(name);
            } else if (shellCommand instanceof String[]) {
                String[] arr = (String[]) shellCommand;
                if (log.shouldDebug()) {
                    name = Arrays.toString(arr);
                    log.debug("ShellCommand exec \"" + name + "\" consume? " + consumeOutput + " wait? " + waitForExitStatus);
                }
                process = Runtime.getRuntime().exec(arr);
            } else {
               throw new ClassCastException("shell command must be a String or a String[]");
            }
            if (consumeOutput) {
                Thread processStderrConsumer = new StreamConsumer(process.getErrorStream());
                processStderrConsumer.start();
                Thread processStdoutConsumer = new StreamConsumer(process.getInputStream());
                processStdoutConsumer.start();
            } else {
                // unused, consumeOutput always true
                _errorStream = process.getErrorStream();
                _inputStream = process.getInputStream();
                _outputStream = process.getOutputStream();
                Thread processStderrReader = new StreamReader(_errorStream);
                processStderrReader.start();
                Thread processStdinWriter = new StreamWriter(_outputStream);
                processStdinWriter.start();
                Thread processStdoutReader = new StreamReader(_inputStream);
                processStdoutReader.start();
            }
            if (waitForExitStatus) {
                if (log.shouldDebug())
                    log.debug("ShellCommand waiting for \"" + name + '\"');
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    if (log.shouldWarn()) {
                        log.warn("ShellCommand exception waiting for \"" + name + '"', e);
                    }
                    if (!consumeOutput)
                        killStreams();
                    return false;
                }

                if (!consumeOutput)
                    killStreams();

                if (log.shouldDebug())
                    log.debug("ShellCommand exit value is " + process.exitValue() + " for \"" + name + '\"');
                if (process.exitValue() > 0)
                    return false;
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

    /** unused, only for NO_CONSUME_OUTPUT */
    private void killStreams() {
        try {
            _errorStream.close();
        } catch (IOException e) {
            // Fall through.
        }
        try {
            _inputStream.close();
        } catch (IOException e1) {
            // Fall through.
        }
        try {
            _outputStream.close();
        } catch (IOException e2) {
            // Fall through.
        }
        _errorStream = null;
        _inputStream = null;
        _outputStream = null;
    }
}
