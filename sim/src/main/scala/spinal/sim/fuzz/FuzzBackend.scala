package spinal.sim.fuzz

import java.io.{File, PrintWriter, FileOutputStream, IOException, BufferedWriter, FileWriter}
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.filefilter.FileFilterUtils

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import sys.process._
import scala.io.StdIn.{readLine}
import scala.util.matching.Regex
import scala.util.control.Breaks._
import scala.concurrent.duration._

import spinal.sim.{Signal, Backend}

class FuzzBackendConfig {
  var optimisationLevel: Int = 3
  var fuzzTime: Int          = 0
  val rtlSourcesPaths        = ArrayBuffer[String]()
  val rtlIncludeDirs         = ArrayBuffer[String]()
  var toplevelName: String   = null
  var workspacePath: String  = null
  var workspaceName: String  = null
  var withWave               = false
  var withLcov               = false
  var withLineCovOnly        = false
  var withToggleCovOnly      = false
  var withFileMode           = false
  var withCrashes            = false
  var withSysChange          = false
  var withInputCombined      = false
  var withSleepStmnt         = false
  var withLlvm               = false
  var clockNames             = ArrayBuffer[String]()
  var resetNames             = ArrayBuffer[String]()
  var signalsToFuzz          = Map[String,Int]()
}


class FuzzBackend (config: FuzzBackendConfig) extends Backend{
  import spinal.sim.Backend._

  val workspaceName  = config.workspaceName
  val workspacePath  = config.workspacePath
  val withSleepStatement = config.withSleepStmnt
  //val withSleepStatement = false
  //val withSleepStatement = true

  def clean(): Unit = {
    FileUtils.deleteQuietly(new File(s"${workspacePath}/${workspaceName}"))
  }

  class Logger extends ProcessLogger {
    override def err(s: => String): Unit = { if(!s.startsWith("ar: creating ")) println(s) }
    override def out(s: => String): Unit = {}
    override def buffer[T](f: => T) = f
  }

  def doVerilator(): Unit = {

    println("Generate Verilator script")
    val rtlIncludeDirsArgs = config.rtlIncludeDirs.map(e => s"-I${new File(e).getAbsolutePath}").mkString(" ")
    //val covParam = if (config.withLcov) "" else "--coverage"
    val covParam = if (config.withLcov) "" else if (config.withLineCovOnly) "--coverage-line" else if (config.withToggleCovOnly) "--coverage-toggle" else "--coverage"
    val compParam = if (config.withLlvm) "--compiler clang" else ""
    val waveParam = if (config.withWave) "--trace" else ""

    //       | -Wno-WIDTH -Wno-UNOPTFLAT -Wno-CMPCONST -Wno-UNSIGNED

    val verilatorScript = s""" set -e ;
       | verilator
       | --assert
       | ${covParam}
       | ${compParam}
       | ${waveParam}
       | --output-split 5000
       | --output-split-cfuncs 500
       | --output-split-ctrace 500
       | -Wno-WIDTH -Wno-CMPCONST -Wno-UNSIGNED
       | --x-assign unique
       | --Mdir ${workspaceName}
       | --top-module ${config.toplevelName}
       | $rtlIncludeDirsArgs
       | -cc ${config.rtlSourcesPaths.filter(e => e.endsWith(".v") || 
                                                  e.endsWith(".sv") || 
                                                  e.endsWith(".h"))
                                     .map(new File(_).getAbsolutePath)
                                     .map('"' + _.replace("\\","/") + '"')
                                     .mkString(" ")}""".stripMargin.replace("\n", "")

    val verilatorScriptFile = new PrintWriter(new File(workspacePath + "/verilatorScript.sh"))
    verilatorScriptFile.write(verilatorScript)
    verilatorScriptFile.close

    println("Generate RTL with Verilator script")
    val shCommand = "sh"
    assert(Process(Seq(shCommand, "verilatorScript.sh"), 
      new File(workspacePath)).! (new Logger()) == 0, "Verilator invocation failed")

    println("Generate fuzzer harness / verilator testbench main cpp file")
    genWrapperCpp()
    if (config.withCrashes) 
      adaptAssertionsInCpp()
  }

  def doCompile() : Unit = {

    // copy sources to coverage directory for lcov execution
//    val covDir = s"${workspacePath}/cov/src"
//    FileUtils.copyDirectory(new File(s"${workspacePath}/${workspaceName}"), new File(covDir))
//    val covSrcDir = "cov/src"

    println("Generate cpp compile script")
    new File(s"${workspacePath}/cov").mkdirs()

    val shCommand = "sh"

    // TODO: find a way to autogen this paths or use config
    val verilatedPath = "/usr/local/share/verilator/include"
    val verilatorIncludes = "-I "+verilatedPath
    // verilatorPath => second/last entry of "whereis -b verilator", first entry is the exe
    // TODO: get via config and/or check automatically 
    var compAFL = "afl-g++-fast"
    if (config.withLlvm) compAFL = "afl-clang-lto++"
    // compAFL => only entry of "whereis -b afl-g++
    var comp = "g++"
    if (config.withLlvm) comp = "clang++"
    val stdParam = "-std=c++11"
    val covParam = if (config.withLcov) "--coverage -DVM_COVERAGE=0" else "-DVM_COVERAGE=1"
    val trcParam = if (config.withWave) "-DVM_TRACE=1" else "-DVM_TRACE=0"
    val wnoParam = "-Wno-bool-operation -Wno-sign-compare -Wno-uninitialized -Wno-unused-parameter -Wno-unused-variable -Wno-shadow"
    val optiParam = s"-O${config.optimisationLevel}"
    val commonParams = stdParam+" "+covParam+" "+trcParam+" "+wnoParam+" "+optiParam+ " "+verilatorIncludes

    val hdlFiles  = (new File(s"${workspacePath}/${workspaceName}")).listFiles.filter(_.isFile).filter(_.getName.contains(s"V${config.toplevelName}")).filter(_.getName.contains(".cpp")).toList

    val hdlFileNames : List[String] = for (file <- hdlFiles) yield FilenameUtils.getBaseName(file.getName())
    println("hdlFileNames: "+hdlFileNames)
    println("---")


    println("Generate Allowlist")
    var allowListStr : String = ""
    hdlFileNames.foreach { allowListStr += _+".cpp\n" }

    val allowListFile = new PrintWriter(new File(workspacePath + "/allowlist"))
    allowListFile.write(allowListStr)
    allowListFile.close


    val compilerFuzzStr = s"""
${val out = for (filename <- hdlFileNames)
   yield s"#${compAFL} ${commonParams} -S -o ${workspaceName}/${filename}.ll ${workspaceName}/${filename}.cpp\n"
out.mkString("")}

#${compAFL} ${commonParams} -S -o ${workspaceName}/verilated.ll ${verilatedPath}/verilated.cpp 
#${compAFL} ${commonParams} -S -o ${workspaceName}/verilated_cov.ll ${verilatedPath}/verilated_cov.cpp 
#${compAFL} ${commonParams} -S -o ${workspaceName}/main_afl_simple.ll ${workspaceName}/main_afl_simple.cpp

${val out = for (filename <- hdlFileNames)
   yield s"${compAFL} ${commonParams} -c -o ${workspaceName}/${filename}.o ${workspaceName}/${filename}.cpp\n"
out.mkString("")}

${compAFL} ${commonParams} -c -o ${workspaceName}/verilated.o ${verilatedPath}/verilated.cpp 
${compAFL} ${commonParams} -c -o ${workspaceName}/verilated_cov.o ${verilatedPath}/verilated_cov.cpp 
${if (config.withWave) s"""${compAFL} ${commonParams} -c -o ${workspaceName}/verilated_vcd_c.o ${verilatedPath}/verilated_vcd_c.cpp""" else ""}
${compAFL} ${commonParams} -c -o ${workspaceName}/main_afl_simple.o ${workspaceName}/main_afl_simple.cpp

${compAFL} ${commonParams} ${val out = for (filename <- hdlFileNames) 
  yield s"${workspaceName}/${filename}.o " 
  out.mkString("")} ${workspaceName}/verilated.o ${if (config.withLcov) "" else s"${workspaceName}/verilated_cov.o"} ${if (config.withWave) s"${workspaceName}/verilated_vcd_c.o" else ""} ${workspaceName}/main_afl_simple.o -o bin/V${config.toplevelName}_fuzz
"""

    println("Compile cpp files")
    val gppCompScriptFile = new PrintWriter(new File(workspacePath + "/gppCompScript.sh"))
    gppCompScriptFile.write(compilerFuzzStr)
    gppCompScriptFile.close

    new File(s"${workspacePath}/bin").mkdirs()

    assert(Process(Seq(shCommand, "gppCompScript.sh"), 
      new File(workspacePath), "AFL_LLVM_ALLOWLIST" -> "./allowlist").! (new Logger()) == 0, "G++ for fuzzing compilation failed")

  }


  def genWrapperCpp(): Unit = {
    val stream = if(config.withFileMode) "file_in" else "cin"
    val wrapperString = s"""
#include <iostream>
#include <memory>
${if (config.withFileMode) "#include <fstream>" else ""}
#include <vector>

// Include common routines
#include "verilated.h"
${if (config.withWave) """#ifdef VM_TRACE
#include "verilated_vcd_c.h"
#endif""" else ""}
// Include model header
#include "V${config.toplevelName}.h"

using namespace std;

//--- clk/reset: driven by the testbench
//--- inputs:    driven by afl
//--- outputs:   don't care

class FuzzTb {

public:
  FuzzTb(int argc, char** argv) {
    pp = false;
    for (int i=0; i<argc; i++) {
      if (strcmp(argv[i], "pp") == 0) {
        pp = true;
      }
    }

    Verilated::commandArgs(argc,argv);
    Verilated::assertOn(false);
    Verilated::debug(0);
    Verilated::randReset(2);
    // Initialize DUT
    top = new V${config.toplevelName}();
${ if (config.withCrashes) s"""    top->pp = pp;
    cout << "postprocessing: " << top->pp << "\\n";""" else ""}

${ if (config.withWave) s"""#ifdef VM_TRACE
    if (pp) {
      Verilated::traceEverOn(true);
      //tfp = new VerilatedVcdC;
      top->trace(&tfp, 99);  // Trace 99 levels of hierarchy
      tfp.open(std::string("wave.vcd").c_str());
    }
#endif""" else ""}   

${val inputStruct = for ((name,width) <- config.signalsToFuzz)
      yield s"""    inputSignals.push_back({$width, &top->$name, "$name"});
    top->$name = 0;\n"""
  inputStruct.mkString("")}
    
    mainTime = 0;


    // Reset DUT
${val resetDut = for ((clk,reset) <- (config.clockNames zip config.resetNames))
      yield s"    resetDut(&top->$clk, &top->$reset, 10);\n"
  resetDut.mkString("")}

    // Set initial reset and clock
${val resetInit = for (name <- config.resetNames)
      yield s"    top->$name = 0;\n"
  resetInit.mkString("")}

${val clkInit = for (name <- config.clockNames)
      yield s"""//    top->$name = 0;
    nextCycle(&top->$name,1);\n"""
  clkInit.mkString("")}
  
    
    Verilated::assertOn(true);
    //VL_PRINTF("[%" VL_PRI64 "d] clk=%x rst=%x", mainTime, top->clk, top->reset);

  }

  ~FuzzTb() {
    
${val clkInit = for (name <- config.clockNames)
      yield s"""//    top->$name = 0;
    nextCycle(&top->$name,1);\n"""
  clkInit.mkString("")}

    for (auto item : inputSignals) {
      item.pvar = NULL;
    }
    top->final();
#if VM_COVERAGE
    if (pp) {
      Verilated::mkdir("cov");
      Verilated::mkdir("cov/logs");
      contextp->coveragep()->write("cov/logs/coverage.dat");
    }
#endif
${if (config.withWave) """#ifdef VM_TRACE
    if (pp) {
      tfp.dump((vluint64_t)mainTime);
      tfp.close();
    }
#endif""" else ""}
    delete top; top = NULL;
  }

  const unique_ptr<VerilatedContext> contextp{new VerilatedContext};
${if (config.withWave) """#ifdef VM_TRACE
    VerilatedVcdC tfp;
#endif""" else ""}
  V${config.toplevelName}* top;
  // parameters
  bool pp; // postprocessing


  ${if (config.withFileMode) "ifstream file_in;" else ""}
  
  // called by variable time in verilog
  double sc_time_stamp () {
    return mainTime;
  }

  bool parseStream(string filename) {
    cout << "Get data for input ports \\n";
    ${if (config.withFileMode) "file_in.open(filename, ios::in | ios::binary);" else "// using cin to read testcase"}
    bool validInput = true;
    while (validInput && ${stream}.peek() != EOF && !Verilated::gotFinish()) {
/*
      char buffer;
      ${stream}.read(reinterpret_cast<char*>(&buffer), sizeof(buffer));
      switch (buffer) {
	// sleep statement
      case 'S':
	cout << "Identified 'Sleep' statement \\n";
	uint8_t cnt;
        if (${stream}.peek() == EOF) {
          validInput = false;
          break;
        }
	${stream}.read(reinterpret_cast<char*>(&cnt), sizeof(cnt));
	cout << "... with cycle count: " << (int)cnt << "\\n";
${val clkNext = for (name <- config.clockNames)
      yield s"    nextHalfCycle(&top->$name,cnt);\n"
  clkNext.mkString("")}
  
        cout << "'S' " << (int)cnt << "\\n";
	break;
	// write statement
      case 'W':
	cout << "Identified 'Write' statement \\n";
	uint8_t id;
        if (${stream}.peek() == EOF) {
          validInput = false;
          break;
        }
*/
    ${if (config.withInputCombined) s"""      cout << "Cycle: " << mainTime/2 << "\\n";
            for (int id=0;id<inputSignals.size();id++) {""" else s"""        ${stream}.read(reinterpret_cast<char*>(&id), sizeof(id));
	cout << "... with id: " << (int)id << "\\n";
        if (id > inputSignals.size() || ${stream}.peek() == EOF) {
          validInput = false;
          break;
        }"""}
        switch (inputSignals[id].size) {
	case sizeof(CData):
	  CData value_c;
	  readValue(id, reinterpret_cast<uint8_t*>(&value_c));
	  writeInput<CData>(id, value_c);
	  break;
	case sizeof(SData):
	  SData value_s;
	  readValue(id, reinterpret_cast<uint8_t*>(&value_s));
	  writeInput<SData>(id, value_s);
	  break;
	case sizeof(IData):
	  IData value_i;
	  readValue(id, reinterpret_cast<uint8_t*>(&value_i));
	  writeInput<IData>(id, value_i);
	  break;
	case sizeof(QData):
	  QData value_q;
	  readValue(id, reinterpret_cast<uint8_t*>(&value_q));
	  writeInput<QData>(id, value_q);
	  break;
	default:
	  cout << "Invalid dataformat \\n";
	  validInput = false;
	}
${if (config.withInputCombined) "}" else ""}
${if (withSleepStatement) s"""      uint8_t cnt;
      cin.read(reinterpret_cast<char*>(&cnt), sizeof(cnt));
      nextCycle(&top->clk,cnt);
      cout << "Sleep for " << (int)cnt << " cycles \\n";
""" else """      cout << "Sleep for 1 cycle \\n";"""}
${if (withSleepStatement) {
   val clkNext = for (clk <- config.clockNames)
       yield s"    nextCycle(&top->$clk,cnt);\n"
   clkNext.mkString("") }
  else {
   val clkNext = for (clk <- config.clockNames)
       yield s"    nextCycle(&top->$clk,1);\n"
   clkNext.mkString("") }
}
      if(pp) printInputs();
    }

${val tmp = if (config.withFileMode) "    file_in.close();" else "// using cin to read testcase"
  tmp.mkString("")}

    return validInput;
  }

  bool nextCycle (vluint8_t* clk, int count=1) {
    return nextHalfCycle(clk, count*2);
  }

private:
  
  struct InputSignal {
    uint8_t     size;
    void*       pvar;
    string      name;
  };

  vector<InputSignal> inputSignals;
  
  // current simulation time
  vluint64_t mainTime;
  
  bool printInputs () {
    bool ret = false;
    VL_PRINTF("[%" VL_PRI64 "d] clk=%x rst=%x", mainTime/2, top->clk, top->reset);
    for (int id=0;id<inputSignals.size();id++) {
      int64_t value = 0;
      switch (inputSignals[id].size) {
      case sizeof(CData):
        value = *static_cast<CData*>(inputSignals[id].pvar);
        break;
      case sizeof(SData):
        value = *static_cast<SData*>(inputSignals[id].pvar);
        break;
      case sizeof(IData):
        value = *static_cast<IData*>(inputSignals[id].pvar);
        break; 
      case sizeof(QData):
        value = *static_cast<QData*>(inputSignals[id].pvar);
        break;
      default:
        cout << "Invalid dataformat \\n";
        ret = false;
      } 
      VL_PRINTF(" %s=%lx", inputSignals[id].name.c_str(), value);
    }
    VL_PRINTF("\\n");
    return ret;
  } 

  bool nextHalfCycle (vluint8_t* clk, int count=1) {
    for (int i = 0; i < count; i++) {
      *clk = !*clk;
      top->eval();
${if (config.withWave) """#ifdef VM_TRACE
  if (pp) tfp.dump((vluint64_t)mainTime);
#endif""" else ""}
      mainTime++; // Time passes...
      if (Verilated::gotFinish())
	return true;
    }
    return false;  
  }

  bool resetDut(vluint8_t* clk, vluint8_t* reset, uint32_t cycleNum) {
    *reset = 1; 
    if (nextCycle(clk, cycleNum))
      return true;  
    *reset = 0;
    return false;
  }

  void readValue(uint8_t id, uint8_t* value) {
    ${stream}.read(reinterpret_cast<char*>(value), inputSignals[id].size);
    //cout << "... and value: " << (long int)value << "\\n";
  }

  template <typename T>
  void writeInput(uint8_t id, T value) {
${val clkNext = for (clk <- config.clockNames)
       yield s"""    if (top->$clk)
     nextHalfCycle(&top->$clk);\n"""
   clkNext.mkString("") }
    if (inputSignals[id].pvar != NULL)  {
      *static_cast<T*>(inputSignals[id].pvar) = value;
      cout << "Write to signal: " << inputSignals[id].name << " value: " << hex << (long int)value << dec << "\\n";
    }
  }
  
};


int main (int argc, char** argv) {

ios_base::sync_with_stdio(false);

${if(config.withFileMode) """
  if (argc < 2) {
    cout << "Missing filename of byte stream to propagate. \\n";
    exit(1);
  }
  string filename = argv[1];
""" else """  string filename;
"""}

  FuzzTb* fuzzTb = new FuzzTb(argc, argv);
  //fuzzTb->pp = pp;
  bool test = fuzzTb->parseStream(filename);
  if (test) {
    cout << "--- File valid --- \\n";
//    int timeout = 100;
//    int i = 1;
//    bool endCond = false;
//    while ((!endCond) || i < timeout) {
//      fuzzTb->nextCycle(&fuzzTb->top->clk,1);
//      i++;
//      endCond = (fuzzTb->top->io_rdy);
//    }
  } else {
    cout << "--- File invalid --- \\n";
  }
  delete fuzzTb; fuzzTb = NULL;
  
  return 0;
}
"""
    val cppName = s"main_afl_simple.cpp"
    val cppPath = new File(s"${workspacePath}/${workspaceName}/$cppName") //.getAbsolutePath

    if (!cppPath.createNewFile())
      println("File not created")

    val outFile = new java.io.FileWriter(cppPath)
    outFile.write(wrapperString)
    outFile.flush()
    outFile.close()
   
  }


  def adaptAssertionsInCpp() : Unit = {

    val filePath = new File(s"${workspacePath}/${workspaceName}/V${config.toplevelName}.cpp")
    val topContentCpp = io.Source.fromFile(filePath).mkString
      .replaceAll("VL_FINISH_MT", """#if VM_COVERAGE
	      Verilated::mkdir("cov");
	      Verilated::mkdir("cov/crashes");
	      VerilatedCov::write("cov/crashes/coverage.dat");
#endif
              if(vlTOPp->pp) {
                VL_FINISH_MT""")
      .replaceAll(", \"\"\\);", """, \"\");
              } else {
                VL_FATAL_MT("rerun with pp for more information", 0, "", "");
              }""")

    new PrintWriter(filePath) {write(topContentCpp); close}


    val filePathH = new File(s"${workspacePath}/${workspaceName}/V${config.toplevelName}.h")
    val topContentH = io.Source.fromFile(filePathH).mkString
      .replaceAll("// PORTS", """bool pp = false;
    // PORTS""")

    new PrintWriter(filePathH) {write(topContentH); close}

  }


  def prepareFuzzer() : Unit = {
    println("Prepare Fuzzer")
    // generate command string
    val newTerminal = "x-terminal-emulator -e "
    //val newTerminal = "gnome-terminal -e "
    //    val aflCommand = s"afl-fuzz -i testcase/ -o fuzz/ -x fuzz.dict -- bin/V${config.toplevelName}_fuzz"
    val fuzzTimeAddon = if (config.fuzzTime != 0) s"-V ${config.fuzzTime}" else ""
    val aflCommand = s"afl-fuzz $fuzzTimeAddon -i testcase/ -o fuzz/ -- bin/V${config.toplevelName}_fuzz"
    val aflCommandAddon = if (config.withFileMode) "@@"  else ""


    // create initial input bytefile
    println("Create initial testcase(s) (input byte file(s))")
    new File(s"${workspacePath}/testcase").mkdirs()

    // multiple input files with random values
    if (true) {

      var inputWidth : Int = 0
      for (width <- config.signalsToFuzz.values)
        inputWidth = inputWidth + width

      val testcaseNum = 1
      val testcaseLen = 1
      for (i <- 1 to testcaseNum) {
        // create n input files / testcases
        var outByte = None : Option[FileOutputStream]
        outByte = Some(new FileOutputStream(s"${workspacePath}/testcase/init${i}.dat"))

        //for (j <- 1 to testcaseLen) {
        for (j <- 1 to i) {

          val bytes = new Array[Byte](inputWidth)
          Random.nextBytes(bytes)
          for (byte <- bytes)
            outByte.get.write(byte)
          if (withSleepStatement) {
            var sleepCnt : Byte = 1
            outByte.get.write(sleepCnt)
          }
        }
        if (outByte.isDefined) outByte.get.close
      }
    }
    else {
      var outByte = None: Option[FileOutputStream]
      //var multiOutByte = None: Option[FileOutputStream]
      outByte = Some(new FileOutputStream(s"${workspacePath}/testcase/init.dat"))
      // bytestream w/o keywords
      if (true) {
        var inputWidth : Int = 0
        for (width <- config.signalsToFuzz.values)
          inputWidth = inputWidth + width

        val bytes : ArrayBuffer[Byte] = ArrayBuffer.fill(inputWidth)(0)
        for (byte <- bytes)
          outByte.get.write(byte)
        var sleepCnt : Byte = 1
        if (withSleepStatement) {
          outByte.get.write(sleepCnt)
        }
        val bytes2 : ArrayBuffer[Byte] = ArrayBuffer.fill(inputWidth)(-1)
        for (byte <- bytes2)
          outByte.get.write(byte)
        if (withSleepStatement) {
          outByte.get.write(sleepCnt)
        }

        //      val bytes3 : ArrayBuffer[Byte] = ArrayBuffer.fill(inputWidth)(42)
        //      for (byte <- bytes3)
        //        outByte.get.write(byte)
        //      outByte.get.write(sleepCnt)
      }
      else {
        val writeStmt : Char = 'W'
        if (!config.withInputCombined) {
          var id : Int = 0
          for ((signal,width) <- config.signalsToFuzz) {
            //multiOutByte = Some(new FileOutputStream(s"${workspacePath}/testcase/write${id}.dat"))
            outByte.get.write(writeStmt)
            //multiOutByte.get.write(writeStmt)
          outByte.get.write(id.toByte)
              //multiOutByte.get.write(id.toByte)
            val bytes : ArrayBuffer[Byte] = ArrayBuffer.fill(width)(0)
            for (byte <- bytes) {
              outByte.get.write(byte)
              //multiOutByte.get.write(byte)
            }
            //if (multiOutByte.isDefined) multiOutByte.get.close
            id = id + 1;
          }
        } else {
          outByte.get.write(writeStmt)
          var inputWidth : Int = 0
          for (width <- config.signalsToFuzz.values) {
            inputWidth = inputWidth + width
          }
          val bytes : ArrayBuffer[Byte] = ArrayBuffer.fill(inputWidth)(0)
          for (byte <- bytes)
            outByte.get.write(byte)
        }

        //multiOutByte = Some(new FileOutputStream(s"${workspacePath}/testcase/sleep.dat"))
        val sleepStmt : Char = 'S'
        var sleepCnt : Byte = 1
        if (withSleepStatement) {
          outByte.get.write(sleepStmt)
          //multiOutByte.get.write(sleepStmt)
          outByte.get.write(sleepCnt)
          //multiOutByte.get.write(sleepCnt)
        }
        //val writeStmt : Char = 'W'
        if (!config.withInputCombined) {
          var id : Int = 0
          for ((signal,width) <- config.signalsToFuzz) {
            //multiOutByte = Some(new FileOutputStream(s"${workspacePath}/testcase/write${id}.dat"))
            outByte.get.write(writeStmt)
            //multiOutByte.get.write(writeStmt)
            outByte.get.write(id.toByte)
            //multiOutByte.get.write(id.toByte)
            val bytes : ArrayBuffer[Byte] = ArrayBuffer.fill(width)(0)
            for (byte <- bytes) {
            outByte.get.write(byte)
              //multiOutByte.get.write(byte)
            }
            //if (multiOutByte.isDefined) multiOutByte.get.close
            id = id + 1;
          }
        } else {
          outByte.get.write(writeStmt)
          var inputWidth : Int = 0
        for (width <- config.signalsToFuzz.values) {
          inputWidth = inputWidth + width
        }
          val bytes : ArrayBuffer[Byte] = ArrayBuffer.fill(inputWidth)(-1)
          for (byte <- bytes)
            outByte.get.write(byte)
        }

        //multiOutByte = Some(new FileOutputStream(s"${workspacePath}/testcase/sleep.dat"))
        //val sleepStmt : Char = 'S'
        sleepCnt  = 'F'
        if (withSleepStatement) {
          outByte.get.write(sleepStmt)
          //multiOutByte.get.write(sleepStmt)
          outByte.get.write(sleepCnt)
          //multiOutByte.get.write(sleepCnt)
        }
      }
      if (outByte.isDefined) outByte.get.close
      //if (multiOutByte.isDefined) multiOutByte.get.close
    }
    val dict_str = """write_stmt = "W"
sleep_stmt = "S"
"""
    val dictFile = new java.io.FileWriter(new File(s"${workspacePath}/fuzz.dict"))
    dictFile.write(dict_str)
    dictFile.flush()
    dictFile.close()

      //    println("afl-analyze of input")
      // check input bytefile with afl-analyze
      //    println(Process(s"afl-analyze -i testcase/init.dat -- bin/V${config.toplevelName}_fuzz", new File(workspacePath)).!!)

    val lcovParam = "--rc lcov_branch_coverage=1 --no-checksum --no-external"
    if (config.withLcov) {
      println(Process(s"lcov ${lcovParam} -z -d src", new File(workspacePath)).!!)
      println(Process(s"lcov ${lcovParam} -c --initial -d src -o cov/lcov_base", new File(workspacePath)).!!)
    }

    // setup system for afl_fuzz (command: afl-system-config) and execute the fuzzer
    var aflSettings = "1"
    if (config.withSysChange) {
      println("Enter system password to continue: ")
      println(Process("sudo afl-system-config", new File(workspacePath)).!!)
      aflSettings = "0"
    }
    println("Start fuzzer")
    println("Command: "+newTerminal+" "+aflCommand+" "+aflCommandAddon)
    var fuzzProcess : Process = null
    if (config.withSysChange) {
      // AFL_DISABLE_TRIM
      // AFL_CUSTOM_MUTATOR_ONLY
      //"AFL_CUSTOM_MUTATOR_LIBRARY" -> "/home/ruep/AFLplusplus/custom_mutators/examples/double_n_rand_mutator.so"
      // AFL_BENCH_UNTIL_CRASH

      fuzzProcess = Process(newTerminal+" "+aflCommand+" "+aflCommandAddon, new File(workspacePath)).run()
      //fuzzProcess = Process(newTerminal+" "+aflCommand+" "+aflCommandAddon, new File(workspacePath), "AFL_BENCH_UNTIL_CRASH" -> "1").run()
    } else {
      fuzzProcess = Process(newTerminal+" "+aflCommand+" "+aflCommandAddon, new File(workspacePath), "AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES" -> aflSettings, "AFL_SKIP_CPUFREQ" -> aflSettings).run()
    }
    println("Fuzzer should run right now")
    val lcovProgressPattern : Regex = "([a-z]+).+: ([0-9]+.[0-9]+)%".r
    val queueFileIdPattern : Regex = "id:([0-9]+)".r
    val verCovPattern : Regex = "([0-9]+.[0-9]+)%".r
    var endLoop = false
    var fileCnt = 0
    var iteration = 0
    var fileListDone = ArrayBuffer[File]()
    val fuzzResDir = new File(s"${workspacePath}/fuzz/default/queue/")
    fileCnt = getListOfFiles(fuzzResDir).size
    //println(s"Testcases found: ${fileCnt}")
//    var covResCsv : java.io.FileWriter = null;
    // create new file to store coverage data over time
//    if (!config.withLcov) {
    new File(s"${workspacePath}/cov/tmp").mkdirs()
    new File(s"${workspacePath}/cov/annotation").mkdirs()
    val covResCsvFile = new File(s"${workspacePath}/cov/coverage.csv")
    if (!covResCsvFile.createNewFile())
      println("File not created")
    val covResCsv = new java.io.FileWriter(covResCsvFile)
    covResCsv.write(s"time[sec];coverage[%];testcases\n")
    //    }
    // TODO: if fuzzTime == 0; do fuzzing infinity until user input

    Thread.sleep(1000)
    // for fuzz mode for a defined time span
    var deadline = config.fuzzTime.seconds.fromNow
    if (config.withCrashes && config.fuzzTime == 0) 
      deadline = 1.hour.fromNow
    // for fuzz mode to fuzz until 100%
    val start = System.nanoTime()
    var covPerDouble = 0.0
    var crashOccured = false
    var timeout = false

//    while(!endLoop) {
    while((config.fuzzTime != 0 && deadline.hasTimeLeft) ||
         (config.fuzzTime == 0 && !config.withCrashes && covPerDouble < 100.0) ||
         (config.fuzzTime == 0 && config.withCrashes && !crashOccured)) {
      var curFileCnt = getListOfFiles(fuzzResDir).size
      if (curFileCnt > fileCnt) {
        println(s"Testcases found: ${fileCnt}")
        println(s"Time: ${deadline.timeLeft.toSeconds} seconds")
        if (config.withLcov) {
          val gcovOut = Process(s"gcov -b -c src/V${config.toplevelName}.cpp", new File(workspacePath)).!!

          Process(s"lcov ${lcovParam} -c -d src -o cov/lcov_tmp", new File(workspacePath)).!!
          val lcovSummary = Process(s"lcov ${lcovParam} --summary cov/lcov_tmp", new File(workspacePath)).!!
          //println(lcovSummary)
          var covProgressMap = Map[String,Float]()
          for(data <- lcovProgressPattern.findAllMatchIn(lcovSummary)) {
            // println(s"${data.group(1)}: ${data.group(2)}")
            covProgressMap += (data.group(1) -> data.group(2).toFloat)
          }
          println(covProgressMap)

          // if gcovOut.coverages == 100 bail out of while
          if (covProgressMap.contains("branches") && covProgressMap("branches") >= 100)
            endLoop = true
        }
        else { // verilator coverage
          val fileList = getListOfFiles(fuzzResDir)
          val diffList = fileList diff fileListDone

          for (file <- diffList) {
            val id = queueFileIdPattern.findFirstMatchIn(file.toString).get.group(1)
            //println((s"cat ../../${file}" #| s"${workspacePath}/bin/V${config.toplevelName}_fuzz pp").!!)
            Process(Seq("/bin/sh","-c",s"cat ../../${file} | bin/V${config.toplevelName}_fuzz pp"), new File(workspacePath)).!!
            FileUtils.copyFile(new File (s"${workspacePath}/cov/logs/coverage.dat"), new File (s"${workspacePath}/cov/tmp/coverage${id}.dat"))
            fileListDone += file
          }
//          if (diffList.length > 0) {
            Process(Seq("/bin/sh", "-c", "verilator_coverage -write cov/merged.dat cov/tmp/coverage*.dat"), new File(workspacePath)).!!
            val verilatorCovStr = Process("verilator_coverage --annotate cov/annotation cov/merged.dat", new File (workspacePath)).!!.split("\n").toList.head
            println(verilatorCovStr)
            val covPer = verCovPattern.findFirstMatchIn(verilatorCovStr).get.group(1)
            if (config.fuzzTime == 0) {
              val diffTime = (System.nanoTime()-start)*1e-9
              covResCsv.write(s"${diffTime};${covPer};${fileCnt}\n")
            } else {
              covResCsv.write(s"${config.fuzzTime-deadline.timeLeft.toSeconds};${covPer};${fileCnt}\n")
            }
            covPerDouble = covPer.toDouble
//          }
          println("---------------------------------------------")
        }
      }

      if (config.fuzzTime != 0) {
        Thread.sleep(1000)
//      } else {
//        Thread.sleep(100)
      }
      fileCnt = curFileCnt
      crashOccured = getListOfFiles(new File (s"${workspacePath}/fuzz/default/crashes")).size > 0
      if (config.fuzzTime == 0 && config.withCrashes && !deadline.hasTimeLeft)
        crashOccured = true
    }
    if (config.fuzzTime == 0) {
      val timeDiff = (System.nanoTime()-start)*1e-9
      println(f"""++++ Full coverage reached within ${timeDiff}%1.3f seconds. ++++""")
      val bw = new BufferedWriter(new FileWriter(new File("/tmp/file.out"), true))
      bw.write(f"${timeDiff}%1.3f \n")
      bw.close
    }

    //    if (fuzzProcess.isAlive())
    if (config.withLcov && !deadline.hasTimeLeft) {
      println("Full branche coverage reached, end fuzzer")
    }
    else {
      println("FuzzTime is over")
    }
    println(s"fuzzProcess: ${fuzzProcess}")
    fuzzProcess.destroy()
    println("----------------------------------")
    println("Please end the fuzzing process")
    println("----------------------------------")

    if (config.withLcov) {
      println(Process(s"lcov ${lcovParam} -c -d src -o cov/lcov_info", new File(workspacePath)).!!)
      println(Process(s"lcov ${lcovParam} -a cov/lcov_base -a cov/lcov_info -o cov/lcov_final", new File(workspacePath)).!!)

      println(Process("genhtml --branch-coverage -o cov/web  cov/lcov_final", new File(workspacePath)).!!)
    }
    else { // verilator coverage
      covResCsv.close()
      // run shell script to generate coverage data from verilator, merge them and  generate annotation
      val shPostProcess = s"""
#!/bin/bash

LOG=cov/queue.log
if test -f "$$LOG"; then
    rm "$$LOG"
fi

TRACE=cov/trace
if [ ! -d "$$TRACE" ]; then
    mkdir "$$TRACE"
fi

ID=0
for TESTCASE in fuzz/default/queue/id:*
do
    echo "$$(basename $$TESTCASE)" >> "$$LOG" 
    hd -v "$$TESTCASE" >> "$$LOG"
    cat "$$TESTCASE" | bin/V${config.toplevelName}_fuzz pp >> "$$LOG"
    mv cov/logs/coverage.dat cov/logs/coverage$$ID.dat
    mv wave.vcd cov/trace/wave$$ID.vcd
    ID=$$((ID+1))
done

verilator_coverage -write cov/logs/merged.dat cov/logs/coverage*.dat

#if [ -z "$$(ls -A fuzz/default/crashes)" ]; then

#   if (! test -f "cov/crashes"); then 
#      mkdir cov/crashes
#   fi

#   echo "*********** crashes occured ***********"
#   for CRASH in fuzz/default/crashes/id:*
#   do
#     echo "$$(basename $$CRASH)" >> "$$LOG" 
#     hd -v "$$CRASH" >> "$$LOG"
#     cat "$$CRASH" | bin/V${config.toplevelName}_fuzz pp >> "$$LOG"
#     mv cov/logs/coverage.dat cov/crashes/coverage$$ID.dat
#     mv wave.vcd cov/crashes/wave$$ID.vcd
#     ID=$$((ID+1))
#   done
#fi

if [ ! -z "$$(ls -A fuzz/default/crashes)" ]; then
    
   echo "*********** crashes occured ***********"

   CRASHLOG=cov/crashes.log
   if test -f "$$CRASHLOG"; then
       rm "$$CRASHLOG"
   fi

   if (! test -d "cov/crashes"); then 
       mkdir cov/crashes
   fi

   if (! test -d "cov/crashes/annotation"); then 
       mkdir cov/crashes/annotation
   fi
   
   ID=0
   
   for CRASH in fuzz/default/crashes/id:*
   do
       echo "$$(basename $$CRASH)" >> "$$CRASHLOG" 
       hd -v "$$CRASH" >> "$$CRASHLOG"
       echo "execute crash $$ID"
       cat "$$CRASH" | bin/V${config.toplevelName}_fuzz pp >> "$$CRASHLOG"
       mv cov/crashes/coverage.dat cov/crashes/coverage$$ID.dat
       mv wave.vcd cov/crashes/wave$$ID.vcd
       
       verilator_coverage --annotate cov/crashes/annotation cov/crashes/coverage$$ID.dat
       mv cov/crashes/annotation/${config.toplevelName}.v cov/crashes/annotation/${config.toplevelName}_crash$$ID.v
       ID=$$((ID+1))
   done
fi
"""

      val shPostProcessFile = new PrintWriter(new File(workspacePath + "/covPostProcess.sh"))
      shPostProcessFile.write(shPostProcess)
      shPostProcessFile.close

      val shCommand = "sh"
      assert(Process(Seq(shCommand, "covPostProcess.sh"),
        new File(workspacePath)).! (new Logger()) == 0, "verilator_coverage invocation failed")

      println(Process("verilator_coverage --annotate cov cov/logs/merged.dat", new File(workspacePath)).!!)

    }

  }


  clean()
  doVerilator()
  doCompile()
  prepareFuzzer()

  def getListOfFiles(dir: File):List[File] = {
    if (dir.exists && dir.isDirectory) {
      dir.listFiles.filter(_.isFile).toList
    } else {
      List[File]()
    }
  }

  


  override def isBufferedWrite: Boolean = false

}
