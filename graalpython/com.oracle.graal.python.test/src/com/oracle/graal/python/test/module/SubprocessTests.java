package com.oracle.graal.python.test.module;


import org.junit.Test;

import static com.oracle.graal.python.test.PythonTests.assertPrints;
import static com.oracle.graal.python.test.PythonTests.runScript;

public class SubprocessTests {

    @Test
    public void importSmokeTest(){
        String code = "import subprocess";

        assertPrints("",code);
    }


    @Test
    public void lsSmokeTestRun(){
        String code = "import subprocess\n" +
                "n = subprocess.run([\"ls\",\"-a\"]) \n"+
                "print(n)";
        //assertPrints("",code);

        runScript(new String[0],code,System.out,System.err);
    }

    @Test
    public void lsTestCall(){
        String code = "import subprocess\n" +
                "n = subprocess.call([\"ls\",\"-a\"]) \n"+
                "print(n)";

        //assertPrints("0\n",code);
        runScript(new String[0],code,System.out,System.err);
    }




    @Test
    public void pipeSmokeTest(){
        String code = "import os\n" +
                "os.pipe()\n";

        runScript(new String[0],code,System.out,System.err);

    }


    @Test
    public void envParamSmokeTest(){
        String code = "import subprocess\n" +
                "subprocess.run([\"ls\",\"-a\"], env={'test':'test'})";
        runScript(new String[0],code,System.out,System.err);
    }



}
