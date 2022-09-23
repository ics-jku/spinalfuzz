package mylib

import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._

import spinal.lib.com.spi.ddr._

import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._


object SpiXdrMasterFuzz {

  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600) // in seconds
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .compile (SpiXdrMasterCtrl(
        SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(dataWidth = 4, ioRate = 2, ssWidth = 3))
          .addFullDuplex(0, rate = 1, ddr = false)
          .addFullDuplex(1, rate = 1, ddr = true)
          .addFullDuplex(2, rate = 2, ddr = false)
          .addFullDuplex(3, rate = 2, ddr = true)

          .addHalfDuplex(4, rate = 1, ddr = false, spiWidth = 2)
          .addHalfDuplex(5, rate = 1, ddr = true, spiWidth = 2)
          .addHalfDuplex(6, rate = 2, ddr = false, spiWidth = 2)
          .addHalfDuplex(7, rate = 2, ddr = true, spiWidth = 2)

          .addHalfDuplex(8, rate = 1, ddr = false, spiWidth = 4)
          .addHalfDuplex(9, rate = 1, ddr = true, spiWidth = 4)
          .addHalfDuplex(10, rate = 2, ddr = false, spiWidth = 4)
          .addHalfDuplex(11, rate = 2, ddr = true, spiWidth = 4)
      ))
  }
}

object SpiXdrMasterSim {

  def main(args: Array[String]) {
    SimConfig
      .withConfig(SpinalConfigDebug)
      .withCoverage
      .withWave
      .compile (SpiXdrMasterCtrl(
        SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(dataWidth = 4, ioRate = 2, ssWidth = 3))
          .addFullDuplex(0, rate = 1, ddr = false)
          .addFullDuplex(1, rate = 1, ddr = true)
          .addFullDuplex(2, rate = 2, ddr = false)
          .addFullDuplex(3, rate = 2, ddr = true)

          .addHalfDuplex(4, rate = 1, ddr = false, spiWidth = 2)
          .addHalfDuplex(5, rate = 1, ddr = true, spiWidth = 2)
          .addHalfDuplex(6, rate = 2, ddr = false, spiWidth = 2)
          .addHalfDuplex(7, rate = 2, ddr = true, spiWidth = 2)

          .addHalfDuplex(8, rate = 1, ddr = false, spiWidth = 4)
          .addHalfDuplex(9, rate = 1, ddr = true, spiWidth = 4)
          .addHalfDuplex(10, rate = 2, ddr = false, spiWidth = 4)
          .addHalfDuplex(11, rate = 2, ddr = true, spiWidth = 4)
      ))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val randMax12 : Int = scala.math.pow(2,12).toInt
        val randMax4 : Int = scala.math.pow(2,4).toInt
        val randMax3 : Int = scala.math.pow(2,3).toInt
        val randMax8 : Int = scala.math.pow(2,8).toInt
        val randMax2 : Int = scala.math.pow(2,2).toInt

        val deadline = 3600.seconds.fromNow

        var randConfigKindCpol     : Boolean = false
        var randConfigKindCpha     : Boolean = false
        var randConfigSclkToogle   : Int = 0
        var randConfigMod          : Int = 0
        var randConfigSsActiveHigh : Int = 0
        var randConfigSsSetup      : Int = 0
        var randConfigSsHold       : Int = 0
        var randConfigSsDisable    : Int = 0
        var randCmdValid           : Boolean = false
        var randCmdPayloadKind     : Boolean = false
        var randCmdPayloadRead     : Boolean = false
        var randCmdPayloadWrite    : Boolean = false
        var randCmdPayloadData     : Int = 0
        var randSpiData0Read       : Int = 0
        var randSpiData1Read       : Int = 0
        var randSpiData2Read       : Int = 0
        var randSpiData3Read       : Int = 0

        var randWait  : Int = 0

        sleep(10)
        //DoReset(dut.clockDomain.reset, 2, HIGH)

        dut.clockDomain.assertReset()
        sleep(10)
        dut.clockDomain.deassertReset()

        var iter = 0

        while (deadline.hasTimeLeft) {

        //println(iter)

          if (iter == 5) {
            dut.clockDomain.assertReset()
            sleep(10)
            dut.clockDomain.deassertReset()
            iter = 0
          }

          iter = iter + 1

          dut.clockDomain.waitRisingEdge()

          randConfigKindCpol     = Random.nextBoolean()
          randConfigKindCpha     = Random.nextBoolean()
          randConfigSclkToogle   = Random.nextInt(randMax12)
          randConfigMod          = Random.nextInt(randMax4)
          randConfigSsActiveHigh = Random.nextInt(randMax3)
          randConfigSsSetup      = Random.nextInt(randMax12)
          randConfigSsHold       = Random.nextInt(randMax12)
          randConfigSsDisable    = Random.nextInt(randMax12)
          randCmdValid           = Random.nextBoolean()
          randCmdPayloadKind     = Random.nextBoolean()
          randCmdPayloadRead     = Random.nextBoolean()
          randCmdPayloadWrite    = Random.nextBoolean()
          randCmdPayloadData     = Random.nextInt(randMax8)
          randSpiData0Read       = Random.nextInt(randMax2)
          randSpiData1Read       = Random.nextInt(randMax2)
          randSpiData2Read       = Random.nextInt(randMax2)
          randSpiData3Read       = Random.nextInt(randMax2)

          randWait = Random.nextInt(50)

          //dut.io.en #= randEn
          dut.io.config.kind.cpol     #= randConfigKindCpol
          dut.io.config.kind.cpha     #= randConfigKindCpha
          dut.io.config.sclkToogle    #= randConfigSclkToogle
          dut.io.config.mod           #= randConfigMod
          dut.io.config.ss.activeHigh #= randConfigSsActiveHigh
          dut.io.config.ss.setup      #= randConfigSsSetup
          dut.io.config.ss.hold       #= randConfigSsHold
          dut.io.config.ss.disable    #= randConfigSsDisable
          dut.io.cmd.valid            #= randCmdValid
          dut.io.cmd.payload.kind     #= randCmdPayloadKind
          dut.io.cmd.payload.read     #= randCmdPayloadRead
          dut.io.cmd.payload.write    #= randCmdPayloadWrite
          dut.io.cmd.payload.data     #= randCmdPayloadData
          dut.io.spi.data(0).read     #= randSpiData0Read
          dut.io.spi.data(1).read     #= randSpiData1Read
          dut.io.spi.data(2).read     #= randSpiData2Read
          dut.io.spi.data(3).read     #= randSpiData3Read

          dut.clockDomain.waitSampling(randWait)
        }
      }

  }
}



object SpiXdrMasterVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(SpiXdrMasterCtrl(
          SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(dataWidth = 4, ioRate = 2, ssWidth = 3))
            .addFullDuplex(0, rate = 1, ddr = false)
            .addFullDuplex(1, rate = 1, ddr = true)
            .addFullDuplex(2, rate = 2, ddr = false)
            .addFullDuplex(3, rate = 2, ddr = true)

            .addHalfDuplex(4, rate = 1, ddr = false, spiWidth = 2)
            .addHalfDuplex(5, rate = 1, ddr = true, spiWidth = 2)
            .addHalfDuplex(6, rate = 2, ddr = false, spiWidth = 2)
            .addHalfDuplex(7, rate = 2, ddr = true, spiWidth = 2)

            .addHalfDuplex(8, rate = 1, ddr = false, spiWidth = 4)
            .addHalfDuplex(9, rate = 1, ddr = true, spiWidth = 4)
            .addHalfDuplex(10, rate = 2, ddr = false, spiWidth = 4)
            .addHalfDuplex(11, rate = 2, ddr = true, spiWidth = 4)
    ))
  }
}
