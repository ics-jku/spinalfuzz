package mylib

import spinal.core._
import spinal.core.sim._
import spinal.core.fuzz._
import spinal.sim.fuzz._
//import spinal.lib.bus.amba3.apb.sim.Apb3Driver

import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._

object Apb3TimerVerilog {
  def main (args: Array[String]) {
    SpinalVerilog(new Apb3Timer())
  }

}
object Apb3TimerFuzz {
  def main (args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600)
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .withCrashes
      .compile(new Apb3Timer())
  }

}


object Apb3TimerSim {
  val addressWidth = 8
  val dataWidth    = 32

  def main (args: Array[String]) {
    SimConfig
      .withCoverage
      .compile(new Apb3Timer())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        dut.io.apb.PENABLE #= false
        dut.io.apb.PSEL #= 0
        dut.clockDomain.waitSampling(1)

        var iteration = 0

        val randMaxAddress : Int = scala.math.pow(2,addressWidth).toInt
        val randMaxData    : Int = scala.math.pow(2,dataWidth).toInt
        val randMaxSel     : Int = scala.math.pow(2,1).toInt

        val deadline = 3600.seconds.fromNow

        var randTick    : Boolean = false
        var randClear   : Boolean = false
        var randPaddr   : Int     = 0
        var randPwdata  : Int     = 0
        var randPenable : Boolean = false
        var randPwrite  : Boolean = false
        var randPsel    : Int     = 0

        var randWait : Int = 0

        while (deadline.hasTimeLeft) {

          randTick    = Random.nextBoolean()
          randClear   = Random.nextBoolean()
          randPaddr   = Random.nextInt(randMaxAddress)
          randPwdata  = Random.nextInt(randMaxData)
          randPenable = Random.nextBoolean()
          randPwrite  = Random.nextBoolean()
          randPsel    = Random.nextInt(randMaxSel)
          //for BigInt:
          //BigInt(width, Random)

          randWait    = Random.nextInt(47)

          //println("id: "+iteration+" tick:"+randTick+" - clear:"+randClear+" - address:"+randPaddr+" - data:"+randPwdata+" - en:"+randPenable+" - wr: "+randPwrite+" - sel:"+randPsel)

          dut.io.external.tick  #= randTick
          dut.io.external.clear #= randClear
          dut.io.apb.PADDR      #= randPaddr
          dut.io.apb.PWDATA     #= randPwdata
          dut.io.apb.PENABLE    #= randPenable
          dut.io.apb.PWRITE     #= randPwrite
          dut.io.apb.PSEL       #= randPsel


          //println("- Wait:"+randWait)
          //dut.clockDomain.waitSampling(randWait)
          dut.clockDomain.waitRisingEdge()

          //print("-")

          iteration = iteration + 1
        }
      }

  }

}


