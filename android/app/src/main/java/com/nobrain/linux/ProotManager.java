package com.nobrain.linux;

import android.content.Context;
import android.util.Log;
import java.io.*;

public class ProotManager {

    private static final String DATA_DIR_NAME = "nobrain";
    private static final String GUEST_USER = "nobrain";
    private static final String GUEST_HOME = "/home/nobrain";
    private static final String GUEST_DISPLAY = "127.0.0.1:4";

    public static File getDataDir(Context ctx) {
        return new File(ctx.getFilesDir(), DATA_DIR_NAME);
    }

    // nativeLibraryDir = /data/app/<pkg>/lib/arm64 — boleh di-exec (SELinux OK)
    public static File getBinDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    // Bump versi ini setiap kali rootfs.tar.gz diganti agar app re-extract otomatis
    private static final String ROOTFS_VERSION = "nobrain-runtime-toolchain-v126";

    public static boolean isRootfsExtracted(Context ctx) {
        File osRelease  = new File(getDataDir(ctx), "etc/os-release");
        File versionFile = new File(getDataDir(ctx), "etc/nobrain-rootfs-version");
        if (!osRelease.exists()) return false;
        try {
            BufferedReader r = new BufferedReader(new FileReader(versionFile));
            String v = r.readLine(); r.close();
            return ROOTFS_VERSION.equals(v != null ? v.trim() : "");
        } catch (IOException e) {
            return false;  // version file missing → re-extract
        }
    }

    private static void writeRootfsVersion(Context ctx) {
        try {
            PrintWriter pw = new PrintWriter(new File(getDataDir(ctx), "etc/nobrain-rootfs-version"));
            pw.println(ROOTFS_VERSION); pw.close();
        } catch (IOException ignored) {}
    }

    public static File getNativeLibsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "native-libs");
    }

    public static String getLdLibraryPath(Context ctx) {
        return getBinDir(ctx).getAbsolutePath()
            + ":" + getNativeLibsDir(ctx).getAbsolutePath();
    }

    /** Extract versioned .so deps dari assets/native-libs/ ke filesDir/native-libs/. */
    public static void extractNativeLibs(Context ctx) {
        File dest = getNativeLibsDir(ctx);
        dest.mkdirs();
        try {
            String[] libs = ctx.getAssets().list("native-libs");
            if (libs == null) { Log.e("ProotManager", "native-libs asset dir not found"); return; }
            Log.d("ProotManager", "extractNativeLibs: " + libs.length + " files");
            for (String lib : libs) {
                File out = new File(dest, lib);
                if (!out.exists()) {
                    Log.d("ProotManager", "extracting " + lib);
                    InputStream in = ctx.getAssets().open("native-libs/" + lib);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    in.close(); os.close();
                    out.setReadable(true);
                    Log.d("ProotManager", "extracted " + lib + " (" + out.length() + " bytes)");
                } else {
                    Log.d("ProotManager", "skip " + lib + " (exists)");
                }
            }
        } catch (IOException e) {
            Log.e("ProotManager", "extractNativeLibs failed: " + e.getMessage());
        }
    }

    /**
     * Hanya extract native-libs dari assets. Tidak copy binary ke filesDir —
     * binary dijalankan langsung dari nativeLibraryDir agar tidak kena
     * Android W^X block (SELinux melarang exec dari filesDir).
     */
    public static void setupBinaries(Context ctx) {
        File dataDir = getDataDir(ctx);
        dataDir.mkdirs();
        new File(dataDir, "proot_tmp").mkdirs();
        new File(dataDir, "tmp").mkdirs();
        new File(dataDir, "sdcard").mkdirs();
        extractNativeLibs(ctx);
        extractWebsockify(ctx);
        extractPulseAudio(ctx);
    }

    /** Extract assets/pulseaudio/* ke filesDir/pulseaudio/ (module-opensles.so + libpulse*.so). */
    public static void extractPulseAudio(Context ctx) {
        File paDir = new File(ctx.getFilesDir(), "pulseaudio");
        paDir.mkdirs();
        new File(ctx.getFilesDir(), "pulseaudio_tmp").mkdirs();
        try {
            String[] files = ctx.getAssets().list("pulseaudio");
            if (files == null) { Log.e("ProotManager", "pulseaudio asset dir not found"); return; }
            for (String file : files) {
                File out = new File(paDir, file);
                if (out.exists()) continue;
                InputStream in = ctx.getAssets().open("pulseaudio/" + file);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                in.close(); os.close();
                out.setReadable(true, false);
                out.setExecutable(true, false);
            }
        } catch (IOException e) {
            Log.e("ProotManager", "extractPulseAudio failed: " + e.getMessage());
        }
    }

    /** Extract websockify bundle ke /usr/share/novnc/utils/websockify/ dalam rootfs. */
    public static void extractWebsockify(Context ctx) {
        File wsDir = new File(getDataDir(ctx), "usr/share/novnc/utils/websockify");
        if (new File(wsDir, "run").exists()) return; // sudah ada
        wsDir.mkdirs();
        File binDir = getBinDir(ctx);
        File tar    = new File(binDir, "libexec_tar.so");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                tar.getAbsolutePath(), "xzf", "-", "-C", wsDir.getAbsolutePath(), "--strip-components=2"
            );
            pb.environment().put("LD_LIBRARY_PATH", getLdLibraryPath(ctx));
            Process proc = pb.start();
            InputStream in  = ctx.getAssets().open("websockify.tar.gz");
            OutputStream out = proc.getOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close(); in.close();
            proc.waitFor();
            new File(wsDir, "run").setExecutable(true);
            Log.d("ProotManager", "websockify extracted to " + wsDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e("ProotManager", "extractWebsockify failed: " + e.getMessage());
        }
    }

    public static void extractRootfs(Context ctx) throws Exception {
        File dataDir  = getDataDir(ctx);
        File binDir   = getBinDir(ctx);
        // libexec_tar.so = standalone tar binary (tidak butuh busybox applet dispatch)
        // libiconv.so sudah ada di nativeLibraryDir (jniLibs)
        File tar = new File(binDir, "libexec_tar.so");

        String ldPath = getLdLibraryPath(ctx);
        Log.d("ProotManager", "extractRootfs tar=" + tar.getAbsolutePath());
        Log.d("ProotManager", "LD_LIBRARY_PATH=" + ldPath);
        Log.d("ProotManager", "dataDir=" + dataDir.getAbsolutePath() + " exists=" + dataDir.exists());

        // --warning=no-hard-link: filesDir tidak support hard link (SELinux), abaikan saja
        // --no-same-owner: tidak perlu set owner di non-root
        ProcessBuilder pb = new ProcessBuilder(
            tar.getAbsolutePath(), "xzf", "-", "-C", dataDir.getAbsolutePath(),
            "--no-same-owner"
        );
        pb.environment().put("LD_LIBRARY_PATH", ldPath);
        pb.redirectErrorStream(true); // merge stderr into stdout so we can read it
        Process proc = pb.start();

        final StringBuilder procOut = new StringBuilder();
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.e("ProotManager", "tar: " + line);
                    procOut.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });
        stderrThread.start();

        InputStream in  = ctx.getAssets().open("rootfs.tar.gz");
        OutputStream out = proc.getOutputStream();
        byte[] buf = new byte[65536];
        int n;
        try {
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException pipe) {
            Log.e("ProotManager", "pipe error during write: " + pipe.getMessage());
        } finally {
            out.close();
            in.close();
        }

        stderrThread.join();
        int exit = proc.waitFor();
        Log.d("ProotManager", "tar exited with code " + exit);
        // Hard link tidak bisa dibuat di Android filesDir (SELinux) → tar exit 2,
        // tapi rootfs tetap terekstrak. Cek os-release sebagai indikator sukses.
        File osRelease = new File(dataDir, "etc/os-release");
        if (!osRelease.exists()) throw new RuntimeException(
            "tar exited " + exit + " dan rootfs tidak lengkap: " + procOut.toString().trim());
        writeRootfsVersion(ctx);
    }

    /** Buat fake /proc/stat agar htop dan tools lain tidak crash. */
    private static File createFakeProcStat(File dataDir) {
        File f = new File(dataDir, "proot_proc/stat");
        f.getParentFile().mkdirs();
        if (!f.exists()) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new FileOutputStream(f))) {
                w.println("cpu  0 0 0 999999 0 0 0 0 0 0");
                w.println("cpu0 0 0 0 999999 0 0 0 0 0 0");
                w.println("intr 0");
                w.println("ctxt 0");
                w.println("btime 1000000000");
                w.println("processes 100");
                w.println("procs_running 1");
                w.println("procs_blocked 0");
                w.println("softirq 0 0 0 0 0 0 0 0 0 0 0");
            } catch (IOException ignored) {}
        }
        return f;
    }

    private static File createFakeProcFile(File dataDir, String name, String contents) {
        File f = new File(dataDir, "proot_proc/" + name);
        f.getParentFile().mkdirs();
        try (java.io.PrintWriter w = new java.io.PrintWriter(new FileOutputStream(f, false))) {
            w.print(contents);
        } catch (IOException ignored) {}
        return f;
    }

    private static String resolveSdcardSource(Context ctx, File dataDir) {
        if (android.os.Build.VERSION.SDK_INT >= 30
                && android.os.Environment.isExternalStorageManager()) {
            return "/storage/emulated/0";
        }
        java.io.File extDir = ctx.getExternalFilesDir(null);
        java.io.File sharedDir = (extDir != null)
            ? new java.io.File(extDir, "shared")
            : new java.io.File(dataDir, "shared");
        sharedDir.mkdirs();
        return sharedDir.getAbsolutePath();
    }

    private static ProcessBuilder buildProotProcess(Context ctx, File scriptFile,
                                                    boolean keepAlive) throws IOException {
        File dataDir  = getDataDir(ctx);
        File binDir   = getBinDir(ctx);
        File proot    = new File(binDir, "libexec_proot.so");
        File loader   = new File(binDir, "libproot-loader.so");
        File prootTmp = new File(dataDir, "proot_tmp");
        prootTmp.mkdirs();

        File fakeStat = createFakeProcStat(dataDir);
        File fakeUptime = createFakeProcFile(dataDir, "uptime", "100000.00 99999.00\n");
        File fakeLoadavg = createFakeProcFile(dataDir, "loadavg", "0.00 0.01 0.05 1/100 1000\n");
        File fakeVersion = createFakeProcFile(dataDir, "version",
            "Linux version 6.6.0-nobrain (nobrain@android) #1 SMP PREEMPT aarch64 GNU/Linux\n");

        String sdcardSrc = resolveSdcardSource(ctx, dataDir);
        File x11UnixDir = new File(dataDir, "tmp/.X11-unix");
        x11UnixDir.mkdirs();

        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("-H");
        args.add("--change-id=0:0");
        args.add("--pwd=" + GUEST_HOME);
        args.add("--rootfs=" + dataDir.getAbsolutePath());
        if (!keepAlive) args.add("--kill-on-exit");
        args.add("--mount=/proc");
        args.add("--mount=/dev");
        args.add("--mount=/sys");
        args.add("--mount=/system");
        args.add("--mount=/apex");
        args.add("--mount=/data");
        args.add("--mount=/storage");
        args.add("--mount=" + sdcardSrc + ":/sdcard");
        args.add("--mount=" + dataDir.getAbsolutePath() + "/tmp:/dev/shm");
        args.add("--mount=" + dataDir.getAbsolutePath() + "/tmp:/tmp");
        args.add("--mount=" + x11UnixDir.getAbsolutePath() + ":/tmp/.X11-unix");
        args.add("--mount=/dev/urandom:/dev/random");
        args.add("--mount=/dev/null:/dev/tty0");
        args.add("--mount=/dev/null:/proc/sys/kernel/cap_last_cap");
        args.add("--mount=" + fakeStat.getAbsolutePath() + ":/proc/stat");
        args.add("--mount=" + fakeUptime.getAbsolutePath() + ":/proc/uptime");
        args.add("--mount=" + fakeLoadavg.getAbsolutePath() + ":/proc/loadavg");
        args.add("--mount=" + fakeVersion.getAbsolutePath() + ":/proc/version");
        args.add("--sysvipc");
        args.add("-L");
        args.add("--link2symlink");
        args.add("/bin/bash");
        args.add(scriptFile.getAbsolutePath().replace(dataDir.getAbsolutePath(), ""));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", getLdLibraryPath(ctx));
        pb.environment().remove("LD_PRELOAD");
        pb.environment().put("HOME", GUEST_HOME);
        pb.environment().put("USER", GUEST_USER);
        pb.environment().put("SHELL", "/bin/bash");
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        pb.environment().put("DISPLAY", GUEST_DISPLAY);
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("GALLIUM_DRIVER", "virpipe");
        pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
        pb.redirectErrorStream(true);
        return pb;
    }

    public static Process startProot(Context ctx, String command) throws IOException {
        File dataDir  = getDataDir(ctx);
        // Session utama punya script sendiri agar command kecil navbar/menu
        // tidak pernah menimpa startup desktop.
        File scriptFile = new File(dataDir, "tmp/nobrain_session_start.sh");
        scriptFile.getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(scriptFile))) {
            bw.write("#!/bin/bash\n");
            bw.write(command);
            bw.newLine();
        }
        ProcessBuilder pb = buildProotProcess(ctx, scriptFile, true);
        Process proc = pb.start();
        // Log stderr ke logcat
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null)
                    Log.d("NoBrainProot", line);
            } catch (IOException ignored) {}
        }).start();
        return proc;
    }

    private static Process runCommandWithMode(Context ctx, String command, boolean keepAlive) throws IOException {
        File dataDir  = getDataDir(ctx);
        File scriptFile = new File(dataDir, "tmp/nobrain_cmd_" + System.nanoTime() + ".sh");
        scriptFile.getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(scriptFile))) {
            bw.write("#!/bin/bash\n");
            bw.write("export TMPDIR=/tmp\n");
            bw.write("unset PROOT_TMP_DIR\n");
            bw.write("export DISPLAY=${DISPLAY:-" + GUEST_DISPLAY + "}\n");
            bw.write("export HOME=" + GUEST_HOME + "\n");
            bw.write("export USER=" + GUEST_USER + "\n");
            bw.write("if command -v su >/dev/null 2>&1 && id " + GUEST_USER + " >/dev/null 2>&1; then\n");
            bw.write("  exec su " + GUEST_USER + " -c ");
            bw.write(shellQuote(command));
            bw.write("\nelse\n");
            bw.write("  ");
            bw.write(command);
            bw.write("\nfi");
            bw.newLine();
        }
        ProcessBuilder pb = buildProotProcess(ctx, scriptFile, keepAlive);
        Process proc = pb.start();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null)
                    Log.d("NoBrainProot", line);
            } catch (IOException ignored) {}
        }).start();
        return proc;
    }

    public static Process runCommand(Context ctx, String command) throws IOException {
        return runCommandWithMode(ctx, command, false);
    }

    public static Process startProotNoKill(Context ctx, String command) throws IOException {
        return runCommandWithMode(ctx, command, true);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    public static boolean checkRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = new java.util.Scanner(p.getInputStream()).useDelimiter("\\A").next();
            p.waitFor();
            return out.contains("uid=0");
        } catch (Exception e) { return false; }
    }
}
