package mylib

import spinal.core._
import spinal.sim._
import spinal.core.sim._

import spinal.core.fuzz._
import spinal.sim.fuzz._

import scala.util.Random
import scala.math.pow
import scala.concurrent.duration._


object CnnBufferSim {
  def main(args: Array[String]) {
    SimConfig.withCoverage.compile(new CnnBuffer((10,10,1))).doSim{ dut =>

      dut.clockDomain.forkStimulus(period = 10)

      var iteration = 0

      val randMaxPlFrag = scala.math.pow(2,1).toInt

      val deadline = 3600.seconds.fromNow

      var randValid  : Boolean = false;
      var randPlLast : Boolean = false;
      var randPlFrag : Int     = 0;
      var randReady  : Boolean = false;

      var randWait : Int = 0

      while (deadline.hasTimeLeft) {

        randValid  = Random.nextBoolean()
        randPlLast = Random.nextBoolean()
        randPlFrag = Random.nextInt(randMaxPlFrag)
        randReady  = Random.nextBoolean()

        randWait = Random.nextInt(10)

        //println("id: "+iteration+" valid:"+randValid+" - PlLast:"+randPlLast+" - PlFrag:"+randPlFrag+" - ready:"+randReady)

        dut.io.in.valid    #= randValid
        dut.io.in.fragment #= randPlFrag
        dut.io.out.ready   #= randReady
        dut.io.in.last     #= randPlLast

        //println("- Wait:"+randWait)
        //dut.clockDomain.waitSampling(randWait)

        dut.clockDomain.waitRisingEdge()

        iteration = iteration + 1
      }
    }
  }
}

object CnnBufferVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new CnnBuffer((10,10,1)))
  }
}


object CnnBufferFuzz {
  def main(args: Array[String]) {
    FuzzConfig
      .fuzzTime(3600)
      .withInputCombined
      .withLlvm
      .withSysChange
      .withLineCovOnly
      .compile(new CnnBuffer((10,10,1)))
  }
}
