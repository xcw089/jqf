/*
 * Copyright (c) 2017-2018 The Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.berkeley.cs.jqf.fuzz.repro;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.util.Coverage;
import edu.berkeley.cs.jqf.instrument.tracing.events.BranchEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

/**
 * A front-end that provides a specified set of inputs for test
 * case reproduction,
 *
 * This class enables reproduction of a test case with an input file
 * generated by a guided fuzzing front-end such as AFL.
 *
 * @author Rohan Padhye
 */
public class ReproGuidance implements Guidance {
    private final File[] inputFiles;
    private final File traceDir;
    private int nextFileIdx = 0;
    private List<PrintStream> traceStreams = new ArrayList<>();
    private InputStream inputStream;
    private Coverage coverage = new Coverage();

    private Set<String> branchesCoveredInCurrentRun;
    private Set<String> allBranchesCovered;
    private boolean ignoreInvalidCoverage;

    HashMap<Integer, String> branchDescCache = new HashMap<>();


    /**
     * Constructs an instance of ReproGuidance with a list of
     * input files to replay and a directory where the trace
     * events may be logged.
     *
     * @param inputFiles a list of input files
     * @param traceDir an optional directory, which if non-null will
     *                 be the destination for log files containing event
     *                 traces
     */
    public ReproGuidance(File[] inputFiles, File traceDir) {
        this.inputFiles = inputFiles;
        this.traceDir = traceDir;
        if (Boolean.getBoolean("jqf.repro.logUniqueBranches")) {
            allBranchesCovered = new HashSet<>();
            branchesCoveredInCurrentRun = new HashSet<>();
            ignoreInvalidCoverage = Boolean.getBoolean("jqf.repro.ignoreInvalidCoverage");

        }
    }

    /**
     * Constructs an instance of ReproGuidance with a single
     * input file to replay and a directory where the trace
     * events may be logged.
     *
     * @param inputFile an input file
     * @param traceDir an optional directory, which if non-null will
     *                 be the destination for log files containing event
     *                 traces
     */
    public ReproGuidance(File inputFile, File traceDir) {
        this(new File[]{inputFile}, traceDir);
    }

    /**
     * Returns an input stream corresponding to the next input file.
     *
     * @return an input stream corresponding to the next input file
     */
    @Override
    public InputStream getInput() {
        try {
            File inputFile = inputFiles[nextFileIdx];
            this.inputStream = new BufferedInputStream(new FileInputStream(inputFile));

            if (allBranchesCovered != null) {
                branchesCoveredInCurrentRun.clear();
            }

            return this.inputStream;
        } catch (IOException e) {
            throw new GuidanceException(e);
        }
    }

    /**
     * Returns <tt>true</tt> if there are more input files to replay.
     * @return <tt>true</tt> if there are more input files to replay
     */
    @Override
    public boolean hasInput() {
        return nextFileIdx < inputFiles.length;
    }

    /**
     * Logs the end of run in the log files, if any.
     *
     * @param result   the result of the fuzzing trial
     * @param error    the error thrown during the trial, or <tt>null</tt>
     */
    @Override
    public void handleResult(Result result, Throwable error) {
        // Print footer in log files
        String footer = String.format("# End %s\n", inputFiles[nextFileIdx].toString());

        // Close the open input file
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            throw new GuidanceException(e);
        }


        // Show errors for invalid tests
        if (result == Result.INVALID && error != null) {
            File inputFile = inputFiles[nextFileIdx];
            System.err.println(inputFile.getName() + ": Test run was invalid");
            error.printStackTrace();
        }

        // Possibly accumulate coverage
        if (allBranchesCovered != null && (ignoreInvalidCoverage == false || result == Result.SUCCESS)) {
            assert branchesCoveredInCurrentRun != null;
            allBranchesCovered.addAll(branchesCoveredInCurrentRun);
        }

        // Increment file
        nextFileIdx++;


    }

    /**
     * Returns a callback that can log trace events or code coverage info.
     *
     * <p>If the system property <tt>jqf.repro.logUniqueBranches</tt> was
     * set to <tt>true</tt>, then the callback collects coverage info into
     * the set {@link #branchesCoveredInCurrentRun}, which can be accessed using
     * {@link #getBranchesCovered()}.</p>
     *
     * <p>Otherwise, if the <tt>traceDir</tt> was non-null during the construction of
     * this Guidance instance, then one log file per thread of
     * execution is created in this directory. The callbacks generated
     * by this method write trace event descriptions in sequence to
     * their own thread's log files.</p>
     *
     * <p>If neither of the above are true, the returned callback simply updates
     * a total coverage map (see {@link #getCoverage()}.</p>
     *
     * @param thread the thread whose events to handle
     * @return a callback to log code coverage or execution traces
     */
    @Override
    public Consumer<TraceEvent> generateCallBack(Thread thread) {
        if (branchesCoveredInCurrentRun != null) {
            return (e) -> {
                coverage.handleEvent(e);
                if (e instanceof BranchEvent) {
                    BranchEvent b = (BranchEvent) e;
                    int hash = b.getIid() * 31 + b.getArm();
                    String str = branchDescCache.get(hash);
                    if (str == null) {
                        str = String.format("(%09d) %s#%s():%d [%d]", b.getIid(), b.getContainingClass(), b.getContainingMethodName(),
                                b.getLineNumber(), b.getArm());
                        branchDescCache.put(hash, str);
                    }
                    branchesCoveredInCurrentRun.add(str);
                } else if (e instanceof CallEvent) {
                    CallEvent c = (CallEvent) e;
                    String str = branchDescCache.get(c.getIid());
                    if (str == null) {
                        str = String.format("(%09d) %s#%s():%d --> %s", c.getIid(), c.getContainingClass(), c.getContainingMethodName(),
                                c.getLineNumber(), c.getInvokedMethodName());
                        branchDescCache.put(c.getIid(), str);
                    }
                    branchesCoveredInCurrentRun.add(str);
                }
            };
        } else if (traceDir != null) {
            File traceFile = new File(traceDir, thread.getName() + ".log");
            try {
                PrintStream out = new PrintStream(traceFile);
                traceStreams.add(out);

                // Return an event logging callback
                return (e) -> {
                    coverage.handleEvent(e);
                    out.println(e);
                };
            } catch (FileNotFoundException e) {
                // Note the exception, but ignore trace events
                System.err.println("Could not open trace file: " + traceFile.getAbsolutePath());
            }
        }

        // If none of the above work, just update coverage
        return coverage::handleEvent;

    }

    /**
     * Returns a reference to the coverage statistics.
     * @return a reference to the coverage statistics
     */
    public Coverage getCoverage() {
        return coverage;
    }


    /**
     * Retyrns the set of branches covered by this repro.
     *
     * <p>This set will only be non-empty if the system
     * property <tt>jqf.repro.logUniqueBranches</tt> was
     * set to <tt>true</tt> before the guidance instance
     * was constructed.</p>
     *
     * <p>The format of each element in this set is a
     * custom format that strives to be both human and
     * machine readable.</p>
     *
     * <p>A branch is only logged for inputs that execute
     * successfully. In particular, branches are not recorded
     * for failing runs or for runs that violate assumptions.</p>
     *
     * @return the set of branches covered by this repro
     */
    public Set<String> getBranchesCovered() {
        return allBranchesCovered;
    }

}
