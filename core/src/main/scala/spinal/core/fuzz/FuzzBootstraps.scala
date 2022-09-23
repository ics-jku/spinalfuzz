/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Core                         **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */

package spinal.core.fuzz

import java.io.File

import org.apache.commons.io.FileUtils

import spinal.core.internals.{DeclarationStatement, GraphUtils}
import spinal.core.{BaseType, Component, SpinalReport, InComponent, Bits, Bool, UInt, SInt, Mem, SpinalConfig, SpinalEnumCraft, Verilator, MemSymbolesTag, GlobalData}
import spinal.sim.fuzz._
import spinal.sim._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import sys.process._


case class SpinalFuzzBackendConfig[T <: Component](
  rtl               : SpinalReport[T],
  workspacePath     : String = "./",
  workspaceName     : String = null,
  optimisationLevel : Int = 3,
  fuzzTime          : Int = 10,
  withWave          : Boolean,
  withLcov          : Boolean,
  withLineCovOnly   : Boolean,
  withToggleCovOnly : Boolean,
  withFileMode      : Boolean,
  withCrashes       : Boolean,
  withSysChange     : Boolean,
  withInputCombined : Boolean,
  withSleepStmnt    : Boolean,
  withLlvm          : Boolean,
  signalsToFuzz     : ArrayBuffer[String] = ArrayBuffer[String]()
)
  
object SpinalFuzzBackend {

  def apply[T <: Component](config: SpinalFuzzBackendConfig[T]) = {

    import config._

    val vconfig = new FuzzBackendConfig()

    vconfig.rtlIncludeDirs ++= rtl.rtlIncludeDirs
    vconfig.rtlSourcesPaths ++= rtl.rtlSourcesPaths
    vconfig.toplevelName      = rtl.toplevelName
    vconfig.workspaceName     = workspaceName
    vconfig.workspacePath     = workspacePath
    vconfig.optimisationLevel = optimisationLevel
    vconfig.fuzzTime          = fuzzTime
    vconfig.withWave          = withWave
    vconfig.withLcov          = withLcov
    vconfig.withLineCovOnly   = withLineCovOnly
    vconfig.withToggleCovOnly = withToggleCovOnly
    vconfig.withFileMode      = withFileMode
    vconfig.withCrashes       = withCrashes
    vconfig.withSysChange     = withSysChange
    vconfig.withInputCombined = withInputCombined
    vconfig.withSleepStmnt    = withSleepStmnt
    vconfig.withLlvm          = withLlvm

    // TODO: convert string from signalsToFuzz SpinalFuzzBackendConfig to signals from signalsToFuzz FuzzBackendConfig
    var signalsToFuzz = Map[String,Int]()
    var signalId = 0

    //vconfig.clockNames += rtl.toplevel.clockDomain.clock.getName()
    //vconfig.resetNames += rtl.toplevel.clockDomain.reset.getName()

    for (io <- rtl.toplevel.getAllIo){
      if (io.isInputOrInOut) {
        if (io.getName().contains("clk")) {
          vconfig.clockNames += io.getName()
        }
        else if (io.getName().contains("reset")) {
          vconfig.resetNames += io.getName()
        }
        // check if signals are clock or reset signals
//        else if (!vconfig.clockNames.exists(name => {name == io.getName()}) &&
//          !vconfig.resetNames.exists(name => {name == io.getName()})) {
        else {
          val bitWidth = io.getBitsWidth
          var byteWidth = 0
          if (bitWidth <= 8) byteWidth = 1
          else if (bitWidth <= 16) byteWidth = 2
          else if (bitWidth <= 32) byteWidth = 4
          else if (bitWidth <= 64) byteWidth = 8
          signalsToFuzz += (io.getName() -> byteWidth)
        }
      }
    }

    println(s"[FuzzBootstraps] Clock(s): ${vconfig.clockNames}")
    println(s"[FuzzBootstraps] Reset(s): ${vconfig.resetNames}")
    println(s"[FuzzBootstraps] Input(s): ${signalsToFuzz}")

    //println(s"[FuzzBootstraps] Clock: ${rtl.toplevel.clockDomain.clock.getName()}")
    //println(s"[FuzzBootstraps] Reset: ${rtl.toplevel.clockDomain.reset.getName()}")

    vconfig.signalsToFuzz = signalsToFuzz
    println("[FuzzBootstraps] workspacePath: "+vconfig.workspacePath)
    println("[FuzzBootstraps] workspaceName: "+vconfig.workspaceName)
      
    new FuzzBackend(vconfig)
  }

}

/**
  * Run fuzzer
  */
abstract class FuzzCompiled[T <: Component](val report: SpinalReport[T]){
  def dut = report.toplevel

  val testNameMap = mutable.HashMap[String, Int]()

  def allocateTestName(name: String): String = {
    testNameMap.synchronized{
      val value = testNameMap.getOrElseUpdate(name, 0)
      testNameMap(name) = value + 1
      if(value == 0){
        return name
      }else{
        val ret = name + "_" + value
        println(s"[Info] Test '$name' was reallocated as '$ret' to avoid collision")
        return ret
      }
    }
  }

  // fuzzing addon
  def doFuzzApi(name: String = "test", seed: Int = Random.nextInt(2000000000), joinAll: Boolean)(body: T => Unit): Unit = {
    Random.setSeed(seed)

    val allocatedName = allocateTestName(name)
    println(f"[Testing] Start Fuzzing simulation '$allocatedName' for DUT '${dut.definitionName}' with seed '$seed'")
    val backendSeed   = if(seed == 0) 1 else seed

//    val sim = newSimRaw(allocatedName, backendSeed)

    val manager = new FuzzManager(){
      val spinalGlobalData =  GlobalData.get
/*      override def setupJvmThread(thread: Thread): Unit = {
        super.setupJvmThread(thread)
        GlobalData.it.set(spinalGlobalData)
      }
 */
    }
    manager.userData = dut
    manager.run(body(dut))
  }

}


/**
  * Fuzzing Workspace
  */
object FuzzWorkspace {
  private var uniqueId = 0

  def allocateUniqueId(): Int = {
    this.synchronized {
      uniqueId = uniqueId + 1
      uniqueId
    }
  }

  val workspaceMap = mutable.HashMap[(String, String), Int]()

  def allocateWorkspace(path: String, name: String): String = {
    workspaceMap.synchronized{
      val value = workspaceMap.getOrElseUpdate((path,name), 0)
      workspaceMap((path, name)) = value + 1
      if(value == 0){
        return name
      }else{
        val ret = name + "_" + value
        println(s"[Info] Workspace '$name' was reallocated as '$ret' to avoid collision")
        return ret
      }
    }
  }
}

/**
  * SpinalFuzz configuration
  */
case class SpinalFuzzConfig(
  var _workspacePath        : String = System.getenv().getOrDefault("SPINALFUZZ_WORKSPACE","./fuzzWorkspace"),
  var _workspaceName        : String = null,
  var _spinalConfig         : SpinalConfig = SpinalConfig(),
  var _additionalRtlPath    : ArrayBuffer[String] = ArrayBuffer[String](),
  var _additionalIncludeDir : ArrayBuffer[String] = ArrayBuffer[String](),
  var _optimisationLevel    : Int = 3,
  var _fuzzTime             : Int = 100,
  var _withWave             : Boolean = false,
  var _withLcov             : Boolean = false,
  var _withLineCovOnly      : Boolean = false,
  var _withToggleCovOnly    : Boolean = false,
  var _withFileMode         : Boolean = false,
  var _withCrashes          : Boolean = false,
  var _withSysChange        : Boolean = false,
  var _withInputCombined    : Boolean = false,
  var _withSleepStmnt       : Boolean = false,
  var _withLlvm             : Boolean = false,
  var _signalsToFuzz        : ArrayBuffer[String] = ArrayBuffer[String]()
){

  def workspacePath(path: String): this.type = {
    _workspacePath = path
    this
  }

  def workspaceName(name: String): this.type = {
    _workspaceName = name
    this
  }

  def withConfig(config: SpinalConfig): this.type = {
    _spinalConfig = config
    this
  }

  def addRtl(that : String) : this.type = {
    _additionalRtlPath += that
    this
  }

  def addIncludeDir(that : String) : this.type = {
    _additionalIncludeDir += that
    this
  }

  def noOptimisation: this.type = {
    _optimisationLevel = 0
    this
  }
  def fewOptimisation: this.type = {
    _optimisationLevel = 1
    this
  }
  def normalOptimisation: this.type = {
    _optimisationLevel = 2
    this
  }
  def allOptimisation: this.type = {
    _optimisationLevel = 3
    this
  }

  def setOptimisation(that : Int) : this.type = {
    if (that < 0)
      _optimisationLevel = 0
    else if (that > 3)
      _optimisationLevel = 3
    else
      _optimisationLevel = that
    this
  }

  def fuzzTime(that : Int): this.type = {
    _fuzzTime = that
    this
  }

  def withFileMode: this.type = {
    _withFileMode = true
    this
  }

  def withCrashes: this.type = {
    _withCrashes = true
    this
  }

  def withSysChange: this.type = {
    _withSysChange = true
    this
  }

  def withWave: this.type = {
    _withWave = true
    this
  }

  def withLcov: this.type = {
    _withLcov = true
    this
  }

  def withLineCovOnly: this.type = {
    _withLineCovOnly = true
    this
  }

  def withToggleCovOnly: this.type = {
    _withToggleCovOnly = true
    this
  }

  def withInputCombined: this.type = {
    _withInputCombined = true
    this
  }

  def withSleepStmnt: this.type = {
    _withSleepStmnt = true
    this
  }

  def withLlvm: this.type = {
    _withLlvm = true
    this
  }

  def compile[T <: Component](rtl: => T) {
    val uniqueId = FuzzWorkspace.allocateUniqueId()
    new File(s"tmp").mkdirs()
    new File(s"tmp/job_${uniqueId}").mkdirs()
    val config = _spinalConfig.copy(targetDirectory = s"tmp/job_${uniqueId}")
    val report = config.generateVerilog(rtl)
    // blackboxe handling neede?
    compile[T](report)
  }

  def compile[T <: Component](report: SpinalReport[T]) {
    if (_workspacePath.startsWith("~"))
      _workspacePath = System.getProperty("user.home") + _workspacePath.drop(1)

    if (_workspaceName == null)
      _workspaceName = s"${report.toplevelName}"

    _workspaceName = FuzzWorkspace.allocateWorkspace(_workspacePath, _workspaceName)

    println(f"[Progress] Fuzzing workspace in ${new File(s"${_workspacePath}/${_workspaceName}").getAbsolutePath}")
    new File(s"${_workspacePath}").mkdirs()
    FileUtils.deleteQuietly(new File(s"${_workspacePath}/${_workspaceName}"))
    new File(s"${_workspacePath}/${_workspaceName}").mkdirs()
    new File(s"${_workspacePath}/${_workspaceName}/rtl").mkdirs()
    report.generatedSourcesPaths.foreach { srcPath =>
      FileUtils.copyFileToDirectory(new File(srcPath), new File (s"${_workspacePath}/${_workspaceName}/rtl"))
    }

    println(f"[Progress] Verilator compilation for fuzzing started")
    val startAt = System.nanoTime()
    val vConfig = SpinalFuzzBackendConfig[T](
      rtl               = report,
      workspacePath     = s"${_workspacePath}/${_workspaceName}",
      workspaceName     = "src",
      optimisationLevel = _optimisationLevel,
      fuzzTime          = _fuzzTime,
      withWave          = _withWave,
      withLcov          = _withLcov,
      withLineCovOnly   = _withLineCovOnly,
      withToggleCovOnly = _withToggleCovOnly,
      withFileMode      = _withFileMode,
      withCrashes       = _withCrashes,
      withSysChange     = _withSysChange,
      withInputCombined = _withInputCombined,
      withSleepStmnt    = _withSleepStmnt,
      withLlvm          = _withLlvm,
      signalsToFuzz     = _signalsToFuzz
    )
    val backend = SpinalFuzzBackend(vConfig)

    val deltaTime = (System.nanoTime() - startAt) * 1e-6
    println(f"[Progress] Verilator compilation done in $deltaTime%1.3f ms")
  }

}

