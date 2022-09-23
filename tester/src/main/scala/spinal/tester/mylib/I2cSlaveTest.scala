package mylib


import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._

import spinal.lib._
import spinal.lib.com.i2c._

import scala.collection.mutable
import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._

object I2cSlaveVerilog {

  def main (args: Array[String]) {
    SpinalVerilog(new I2cSlave(new I2cSlaveGenerics()))
  }

}

object I2cSlaveFuzz {

  def main (args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600) // in seconds
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .compile(new I2cSlave(new I2cSlaveGenerics()))
  }

}


object I2cSlaveSim {

  def main (args: Array[String]) {
    SimConfig
      .withCoverage
      .compile(new I2cSlave(new I2cSlaveGenerics()))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)


        var iteration = 0

        val randMax10 : Int = scala.math.pow(2,10).toInt
        val randMax20 : Int = scala.math.pow(2,20).toInt
        val randMax6  : Int = scala.math.pow(2,6).toInt

        val deadline = 3600.seconds.fromNow

        var randSdaRead         : Boolean = false
        var randSclRead         : Boolean = false
        var randConfSamplClkDiv : Int     = 0 // [9:0]
        var randConfTimeOut     : Int     = 0 // [19:0]
        var randConfTsuData     : Int     = 0 // [5:0]
        var randBusRspValid     : Boolean = false
        var randBusRspEn        : Boolean = false
        var randBusRspData      : Boolean = false

        var randWait : Int = 0

        while (deadline.hasTimeLeft) {

          randSdaRead         = Random.nextBoolean()
          randSclRead         = Random.nextBoolean()
          randConfSamplClkDiv = Random.nextInt(randMax10)
          randConfTimeOut     = Random.nextInt(randMax20)
          randConfTsuData     = Random.nextInt(randMax6)
          randBusRspValid     = Random.nextBoolean()
          randBusRspEn        = Random.nextBoolean()
          randBusRspData      = Random.nextBoolean()


          randWait = Random.nextInt(50)

          dut.io.i2c.sda.read                #= randSdaRead
          dut.io.i2c.scl.read                #= randSclRead
          dut.io.config.samplingClockDivider #= randConfSamplClkDiv
          dut.io.config.timeout              #= randConfTimeOut
          dut.io.config.tsuData              #= randConfTsuData
          dut.io.bus.rsp.valid               #= randBusRspValid
          dut.io.bus.rsp.enable              #= randBusRspEn
          dut.io.bus.rsp.data                #= randBusRspData

          //dut.clockDomain.waitSampling(randWait)
          dut.clockDomain.waitRisingEdge()

        }
      }
  }
}
