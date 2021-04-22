/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.apps.LingeredApp;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.dcmd.PidJcmdExecutor;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.JDKToolFinder;
import jtreg.SkippedException;
import sun.hotspot.WhiteBox;

import java.io.InputStreamReader;
import java.io.BufferedReader;

public abstract class JCmdTestDumpBase {
    private static boolean isStatic; // set first in subclass for dump type
    protected static void setIsStatic(boolean value) {
        isStatic = value;
    }
    /**************************************
     * for a subclass to do static dump
     *    public class JCmdTestStaticDump extends JCmdTestDumpBase {
     *        isStatic = true; // must, false for dynamic
     *        public static void test() throws Exception {
     *            // do test
     *        }
     *        public static void main(String[] args) throws Exception {
     *            runTest(JCmdTestStaticDump::test);
     *        }
     *    }
     ***************************************/
    public static interface JCmdTest {
        public void run() throws Exception;
    }

    public static void runTest(JCmdTest t) throws Exception {
        checkCDSEnabled();
        t.run();
    }
    private static final String TEST_CLASSES[] =
                             {"JCmdTestLingeredApp",
                              "jdk/test/lib/apps/LingeredApp",
                              "jdk/test/lib/apps/LingeredApp$1"};
    private static final String BOOT_CLASSES[]    = {"Hello"};

    protected static String testJar = null;
    protected static String bootJar = null;
    protected static String allJars = null;

    protected static void buildJars() throws Exception {
        testJar = JarBuilder.build("test", TEST_CLASSES);
        bootJar = JarBuilder.build("boot", BOOT_CLASSES);
        System.out.println("Jar file created: " + testJar);
        System.out.println("Jar file created: " + bootJar);
        allJars = testJar+ File.pathSeparator + bootJar;
    }

    private static void checkCDSEnabled() throws Exception {
        boolean cdsEnabled = WhiteBox.getWhiteBox().getBooleanVMFlag("UseSharedSpaces");
        if (!cdsEnabled) {
            throw new SkippedException("CDS is not available for this JDK.");
        }
    }

    private static boolean argsContain(String[] args, String flag) {
         for (String s: args) {
             if (s.contains(flag)) {
                 return true;
             }
         }
         return false;
    }

    private static boolean argsContainOpts(String[] args, String... opts) {
        boolean allIn = true;
        for (String f : opts) {
            allIn &= argsContain(args, f);
            if (!allIn) {
                break;
            }
        }
        return allIn;
    }

    protected static LingeredApp createLingeredApp(String... args) throws Exception {
        JCmdTestLingeredApp app  = new JCmdTestLingeredApp();
        try {
            LingeredApp.startAppExactJvmOpts(app, args);
        } catch (Exception e) {
            // Check flags used.
            if (argsContainOpts(args, new String[] {"-Xshare:off", "-XX:+RecordDynamicDumpInfo"}) ||
                argsContainOpts(args, new String[] {"-XX:+RecordDynamicDumpInfo", "-XX:ArchiveClassesAtExit="})) {
                // app exit premature due to incompactible args
                return null;
            }
            Process proc = app.getProcess();
            if (e instanceof IOException && proc.exitValue() == 0) {
                // Process started and exit normally.
                return null;
            }
            throw e;
        }
        return app;
    }

    private static int logFileCount = 0;
    private static void runWithArchiveFile(String archiveName, boolean useBoot,  String... messages) throws Exception {
        List<String> args = new ArrayList<String>();
        if (useBoot) {
            args.add("-Xbootclasspath/a:" + bootJar);
        }
        args.add("-cp");
        if (useBoot) {
            args.add(testJar);
        } else {
            args.add(allJars);
        }
        args.add("-Xshare:on");
        args.add("-XX:SharedArchiveFile=" + archiveName);
        args.add("-Xlog:class+load");

        LingeredApp app = createLingeredApp(args.toArray(new String[0]));
        app.setLogFileName("JCmdTestDynamicDump.log." + (logFileCount++));
        app.stopApp();
        String output = app.getOutput().getStdout();
        if (messages != null) {
            for (String msg : messages) {
                if (!output.contains(msg)) {
                    throw new RuntimeException(msg + " missed from output");
                }
            }
        }
    }

    protected static void test(String archiveFile, long pid,
                             boolean useBoot, boolean expectOK, String... messages) throws Exception {
        System.out.println("Expected: " + (expectOK ? "SUCCESS" : "FAIL"));
        String fileName = archiveFile != null ? archiveFile :
            ("java_pid" + pid + (isStatic ? "_static.jsa" : "_dynamic.jsa"));
        File file = new File(fileName);
        if (file.exists()) {
            file.delete();
        }

        String jcmd = "VM.cds " + (isStatic ? "static_dump" : "dynamic_dump");
        if (archiveFile  != null) {
          jcmd +=  " " + archiveFile;
        }

        PidJcmdExecutor cmdExecutor = new PidJcmdExecutor(String.valueOf(pid));
        OutputAnalyzer output = cmdExecutor.execute(jcmd, true/*silent*/);

        if (expectOK) {
            output.shouldHaveExitValue(0);
            if (!file.exists()) {
                throw new RuntimeException("Could not create shared archive: " + fileName);
            } else {
                runWithArchiveFile(fileName, useBoot, messages);
                file.delete();
            }
        } else {
            if (file.exists()) {
                throw new RuntimeException("Should not create shared archive " + fileName);
            }
        }
    }

    protected static void print2ln(String arg) {
        System.out.println("\n" + arg + "\n");
    }
}
