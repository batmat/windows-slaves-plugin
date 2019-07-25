package hudson.os.windows;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.util.StreamCopyThread;
import org.jinterop.dcom.common.JIException;
import org.jvnet.hudson.remcom.WindowsRemoteProcessLauncher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import static java.util.Arrays.asList;

/**
 * Pseudo-{@link Launcher} implementation that uses {@link WindowsRemoteProcessLauncher}
 *
 * @author Kohsuke Kawaguchi
 */
public class WindowsRemoteLauncher extends Launcher {
    private final WindowsRemoteProcessLauncher launcher;

    public WindowsRemoteLauncher(TaskListener listener, WindowsRemoteProcessLauncher launcher) {
        super(listener, null);
        this.launcher = launcher;
    }

    private String buildCommandLine(ProcStarter ps) {
        StringBuilder b = new StringBuilder();
        for (String cmd : ps.cmds()) {
            if (b.length() > 0) b.append(' ');
            if (cmd.indexOf(' ') >= 0)
                b.append('"').append(cmd).append('"');
            else
                b.append(cmd);
        }
        return b.toString();
    }

    @Override
    public Proc launch(ProcStarter ps) throws IOException {
        FilePath pwd = ps.pwd();
        if (pwd == null) {
            throw new IOException();
        }
        maskedPrintCommandLine(ps.cmds(), ps.masks(), pwd);

        // TODO: environment variable handling

        String name = ps.cmds().toString();

        final Process proc;
        try {
            proc = launcher.launch(buildCommandLine(ps), pwd.getRemote());
        } catch (JIException | InterruptedException e) {
            throw new IOException(e);
        }
        final Thread t1 = new StreamCopyThread("stdout copier: " + name, proc.getInputStream(), ps.stdout(), false);
        t1.start();
        final Thread t2 = new StreamCopyThread("stdin copier: " + name, ps.stdin(), proc.getOutputStream(), true);
        t2.start();

        return new Proc() {

            @Override
            public boolean isAlive() throws IOException, InterruptedException {
                try {
                    proc.exitValue();
                    return false;
                } catch (IllegalThreadStateException e) {
                    return true;
                }
            }

            @Override
            public void kill() throws IOException, InterruptedException {
                t1.interrupt();
                t2.interrupt();
                proc.destroy();
            }

            @Override
            public int join() throws IOException, InterruptedException {
                try {
                    t1.join();
                    t2.join();
                    return proc.waitFor();
                } finally {
                    proc.destroy();
                }
            }

            @Override
            public InputStream getStdout() {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream getStderr() {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream getStdin() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public Channel launchChannel(String[] cmd, OutputStream out, FilePath _workDir, Map<String, String> envVars) throws IOException, InterruptedException {
        if (_workDir == null) {
            throw new IOException();
        }
        printCommandLine(cmd, _workDir);

        try {
            Process proc = launcher.launch(Util.join(asList(cmd), " "), _workDir.getRemote());

            return new ChannelBuilder("channel over named pipe to " + launcher.getHostName(), Computer.threadPoolForRemoting)
                    .withMode(Channel.Mode.BINARY)
                    .build(proc.getInputStream(), new BufferedOutputStream(proc.getOutputStream()));
        } catch (JIException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
        // no way to do this
    }
}
