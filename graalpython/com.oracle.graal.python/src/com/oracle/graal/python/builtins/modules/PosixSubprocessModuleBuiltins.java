/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.modules;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;

import static com.oracle.graal.python.runtime.exception.PythonErrorType.NotImplementedError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.ValueError;

@CoreFunctions(defineModule = "_posixsubprocess")
public class PosixSubprocessModuleBuiltins extends PythonBuiltins {
    private static ArrayList<Process> childProcesses = new ArrayList<Process>();

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinNode>> getNodeFactories() {
        return PosixSubprocessModuleBuiltinsFactory.getFactories();
    }

    /** Since getting the PID of a process in Java is only availble since Java 9
     *
     *  https://stackoverflow.com/a/33171840
     */

    public static long getPidOfProcess(Process p) {
        long pid = -1;

        try {
            if (p.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = p.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                pid = f.getLong(p);
                f.setAccessible(false);
            }
        } catch (Exception e) {
            pid = -1;
        }
        return pid;
    }


    public static Process getProcessByPid(long pid) {

        for (Process p : childProcesses) {
            if (getPidOfProcess(p) == pid) {
                return p;
            }
        }
        return null;
    }


    public static boolean removeProcessByPid(long pid) {
        int i = 0;
        for (Process p : childProcesses) {
            if (getPidOfProcess(p) == pid) {
                childProcesses.remove(i);
                return true;
            }
            i++;
        }
        return false;
    }


    @Builtin(name = "fork_exec", fixedNumOfPositionalArgs = 17, keywordArguments = {
            "args", "executable_list", "close_fds", "fds_to_keep", "cwd", "env", "p2cread", "p2cwrite", "c2pread", "c2pwrite",
            "erread", "errwrite", "errpipe_read", "errpipe_write", "restore_signals", "call_setsid", "preexec"})
    @GenerateNodeFactory
    abstract static class PythonForkExecNode extends PythonBuiltinNode {
        //TODO check if fds are always Integers, still possible to have long type but in this value range?

        /**
         * @param fdsToKeep
         * @return Returns true if there is a problem with the fds
         */
        private boolean sanityCheckPythonFdSequence(PList fdsToKeep) {

            long prevFd = -1;

            //TODO iterator?
            for (int seqIdx = 0; seqIdx < fdsToKeep.getSequenceStorage().length(); seqIdx++) {
                Object pyFd = fdsToKeep.getSequenceStorage().getItemNormalized(seqIdx);

                if (!(pyFd instanceof Long) && !(pyFd instanceof Integer)) {
                    return true;
                }

                long iterFd = pyFd instanceof Long ? (Long) pyFd : (Integer) pyFd;

                //TODO compare Integer.MAX_VALUE to the native implementation
                if (iterFd < 0 || iterFd < prevFd || iterFd > Integer.MAX_VALUE) {
                    /*negative, overflow, unsorted, too big for fd*/
                    return true;
                }

                prevFd = iterFd;
            }

            return false;
        }

        private int getMaxFd() {
            //TODO which value should it be?

            return 255; //<- legacy variable
        }

        private boolean isFdInSortedFdSequence(Integer fd, PTuple fdSequence) {

            return
                    Arrays.binarySearch(fdSequence.getArray(), fd, new Comparator<Object>() {
                        @Override
                        public int compare(Object x, Object y) {
                            return ((Integer) x).compareTo((Integer) y);
                        }
                    }) >= 0;

        }

        @Specialization
        @CompilerDirectives.TruffleBoundary
        Object forkExecGeneral(PList args, PTuple executable_list, boolean close_fds, PTuple fds_to_keep, Object cwd,
                       Object env, int p2cread, int p2cwrite, int c2pread, int c2pwrite, int erread, int errwrite,
                       int errpipe_read, int errpipe_write, boolean restore_signals, boolean call_setsid,
                       Object preexec) {

            //TODO see cpython documentation for fds_to_keep
            System.out.println("fork_exec: start");

            //TODO check PyArg_ParseTuple function

            if (close_fds && errpipe_write < 3) {
                throw raise(ValueError, "errpipe_write must be >= 3");
            }

            /*
              TODO adopt for differnt types of fds_to_keep
              if (sanityCheckPythonFdSequence(fds_to_keep)) {
                throw raise(ValueError, "bad value(s) in fds_to_keep");
            }*/

            //TODO check why to disable gc when preexec is present

            String[] jArgs = new String[args.getSequenceStorage().length()];

            //TODO better would be iterator!
            for (int i = 0; i < jArgs.length; i++) {
                Object currItem = args.getSequenceStorage().getItemNormalized(i);
                if (currItem instanceof String) {
                    jArgs[i] = (String) args.getSequenceStorage().getItemNormalized(i);
                } else {
                    //TODO not handled in standard? (check parse function for string)
                    throw raise(ValueError, "value was not of type string");
                }
            }

            ProcessBuilder processBuilder = new ProcessBuilder(jArgs);

            if (env != null && (env instanceof PList) ) {
                PList envPList = (PList) env;
                //TODO iterator? Possible 0(n²) loop!
                for (int i = 0; i < envPList.getSequenceStorage().length(); i++) {
                    //TODO is of type pbytes, bad (what other option?) to go over toString() representation!
                    String str = envPList.getSequenceStorage().getItemNormalized(i).toString();
                    str = str.substring(2,str.length()-1);
                    String[] splitted = str.split("=");
                    processBuilder.environment().put(splitted[0], splitted[1]);
                }
            }


            assert p2cread == -1 && p2cwrite == -1 || p2cread != -1 && p2cwrite != -1;

            if(p2cread==-1 && p2cwrite == -1){
                processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }else{
                //TODO: pipe input
            }

            assert c2pread == -1 && c2pwrite == -1 || c2pread != -1 && c2pwrite != -1;

            if(c2pread == -1 && c2pwrite == -1){
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }else{
                // TODO: pipe output
            }

            //TODO set other parameters



            Process process = null;
            try {
                process = processBuilder.start();


            } catch (IOException e) {
                e.printStackTrace();
                //TODO right error handling

                return -1;
            }

            //TODO error handling
            long pid = getPidOfProcess(process);

            childProcesses.add(process);

            System.out.println("fork_exec: end");

            return pid;
        }


        @Fallback
        Object forkExecGeneralFallback(Object args, Object executable_list, Object close_fds, Object fds_to_keep, Object cwd,
                               Object env, Object p2cread, Object p2cwrite, Object c2pread, Object c2pwrite, Object erread, Object errwrite,
                               Object errpipe_read, Object errpipe_write, Object restore_signals, Object call_setsid,
                               Object preexec) {

            throw raise(NotImplementedError,"No implementation for this specialization!");
        }

    }



}
