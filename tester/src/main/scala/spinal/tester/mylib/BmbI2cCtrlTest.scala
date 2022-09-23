package mylib

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba3.ahblite._
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc._
import spinal.lib.com.i2c._

import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._


object BmbI2cCtrlVerilog {

  val i2cGenerics = I2cSlaveMemoryMappedGenerics(
      ctrlGenerics = I2cSlaveGenerics(
        samplingWindowSize = 3,
        samplingClockDividerWidth = 10 bits,
        timeoutWidth = 20 bits
      ))
  val bmbParams = BmbParameter(
      addressWidth = 12,
      dataWidth = 32,
      sourceWidth = 0,
      contextWidth = 0,
      lengthWidth = 2
  )

  def main(args: Array[String]) {
    SpinalVerilog(new BmbI2cCtrl (i2cGenerics, bmbParams))
  }
}

object BmbI2cCtrlFuzz {

  val i2cGenerics = I2cSlaveMemoryMappedGenerics(
      ctrlGenerics = I2cSlaveGenerics(
        samplingWindowSize = 3,
        samplingClockDividerWidth = 10 bits,
        timeoutWidth = 20 bits
      ))
  val bmbParams = BmbParameter(
      addressWidth = 12,
      dataWidth = 32,
      sourceWidth = 0,
      contextWidth = 0,
      lengthWidth = 2
  )

  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600) // in seconds
      .withInputCombined
      //.withSleepStmnt
      .withLlvm
      .withSysChange
      .withLineCovOnly
//      .withCrashes
      .compile(new BmbI2cCtrl(i2cGenerics, bmbParams))
  }

}


object BmbI2cCtrlSim {

  val i2cGenerics = I2cSlaveMemoryMappedGenerics(
      ctrlGenerics = I2cSlaveGenerics(
        samplingWindowSize = 3,
        samplingClockDividerWidth = 10 bits,
        timeoutWidth = 20 bits
      ))
  val bmbParams = BmbParameter(
      addressWidth = 12,
      dataWidth = 32,
      sourceWidth = 0,
      contextWidth = 0,
      lengthWidth = 2
  )

  def main(args: Array[String]) {
    SimConfig
      .withCoverage
//      .withWave
      .compile(new BmbI2cCtrl(i2cGenerics, bmbParams))
      .doSim { dut =>

        dut.clockDomain.forkStimulus(period = 10)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitRisingEdge()
        dut.clockDomain.deassertReset()

        val randMax1 : Int = scala.math.pow(2,1).toInt
        val randMax2 : Int = scala.math.pow(2,2).toInt
        val randMax4 : Int = scala.math.pow(2,4).toInt
        val randMax12 : Int = scala.math.pow(2,12).toInt
        val randMax32 : Int = scala.math.pow(2,32).toInt


        val deadline = 3600.seconds.fromNow

        var randCtrlCmdValid                  : Boolean = false
        var randCtrlCmdPayloadLast            : Boolean = false
        var randCtrlCmdPayloadFragmentOpcode  : Int = 0 // [0:0]
        var randCtrlCmdPayloadFragmentAddress : Int = 0 // [11:0]
        var randCtrlCmdPayloadFragmentLength  : Int = 0 // [1:0]
        var randCtrlCmdPayloadFragmentData    : Int = 0 // [31:0]
        var randCtrlCmdPayloadFragmentMask    : Int = 0 // [3:0]
        var randCtrlRspReady                  : Boolean = false
        var randI2cSdaRead                    : Boolean = false
        var randI2cSclRead                    : Boolean = false

        var randWait : Int = 0

        dut.clockDomain.waitRisingEdge()

        while (deadline.hasTimeLeft) {

          randCtrlCmdValid                  = Random.nextBoolean()
          randCtrlCmdPayloadLast            = Random.nextBoolean()
          randCtrlCmdPayloadFragmentOpcode  = Random.nextInt(randMax1)
          randCtrlCmdPayloadFragmentAddress = Random.nextInt(randMax12)
          randCtrlCmdPayloadFragmentLength  = Random.nextInt(randMax2)
          randCtrlCmdPayloadFragmentData    = Random.nextInt(randMax32)
          randCtrlCmdPayloadFragmentMask    = Random.nextInt(randMax4)
          randCtrlRspReady                  = Random.nextBoolean()
          randI2cSdaRead                    = Random.nextBoolean()
          randI2cSclRead                    = Random.nextBoolean()

          randWait = Random.nextInt(256)


          dut.io.ctrl.cmd.valid                    #= randCtrlCmdValid
          dut.io.ctrl.cmd.payload.last             #= randCtrlCmdPayloadLast
          dut.io.ctrl.cmd.payload.fragment.opcode  #= randCtrlCmdPayloadFragmentOpcode
          dut.io.ctrl.cmd.payload.fragment.address #= randCtrlCmdPayloadFragmentAddress
          dut.io.ctrl.cmd.payload.fragment.length  #= randCtrlCmdPayloadFragmentLength
          dut.io.ctrl.cmd.payload.fragment.data    #= randCtrlCmdPayloadFragmentData
          dut.io.ctrl.cmd.payload.fragment.mask    #= randCtrlCmdPayloadFragmentMask
          dut.io.ctrl.rsp.ready                    #= randCtrlRspReady
          dut.io.i2c.sda.read                      #= randI2cSdaRead
          dut.io.i2c.scl.read                      #= randI2cSclRead

          dut.clockDomain.waitRisingEdge()
          //dut.clockDomain.waitSampling(randWait)

        }
      }

  }

}
